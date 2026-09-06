package ru.wds.wdl.tools.workspace;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.AccessStyle;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.diagnostic.Severity;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.analysis.FileAnalysis;
import ru.wds.wdl.tools.analysis.Symbol;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.ModuleDescriptor;
import ru.wds.wdl.tools.catalog.ReceiverResolver;
import ru.wds.wdl.tools.catalog.ReceiverType;
import ru.wds.wdl.tools.catalog.SymbolDescriptor;
import ru.wds.wdl.tools.service.Document;
import ru.wds.wdl.tools.service.DocumentId;
import ru.wds.wdl.tools.service.Location;
import ru.wds.wdl.tools.service.WorkspaceSymbol;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Безопасный индекс WDL-файлов рабочей папки.
 *
 * <p>Индекс читает и разбирает только текст. Он не запускает скрипты и не вызывает
 * импорты. Опубликованное состояние неизменно, поэтому completion всегда читает
 * цельный снимок, пока следующая правка строит новый.</p>
 */
public final class WorkspaceIndex {

    private final AtomicReference<State> state = new AtomicReference<>(State.empty());
    private final Map<Path, Document> overlays = new HashMap<>();
    private final Map<Path, SourceFile> files = new HashMap<>();
    private List<Path> roots = List.of();

    public WorkspaceIndex() {
    }

    /** Источники — каталоги, относительно которых {@code app/shapes.wdl} есть {@code app/shapes}. */
    public synchronized void configure(Collection<Path> sourceRoots) {
        Objects.requireNonNull(sourceRoots, "sourceRoots");
        roots = sourceRoots.stream().filter(Objects::nonNull)
                .map(WorkspaceIndex::normal).distinct().sorted().toList();
        files.clear();
        for (Path root : roots) {
            scan(root);
        }
        overlays.forEach((path, document) -> {
            if (underRoot(path)) {
                files.put(path, SourceFile.of(document));
            }
        });
        publish();
    }

    /** Открытый текст сильнее диска и заменяет только его собственный снимок. */
    public synchronized void openOrChange(Document document) {
        Objects.requireNonNull(document, "document");
        Path path = pathOf(document.id());
        if (path == null || !underRoot(path)) {
            return;
        }
        overlays.put(path, document);
        files.put(path, SourceFile.of(document));
        publish();
    }

    /** После закрытия документа снова читается диск; несуществующий файл исчезает. */
    public synchronized void close(DocumentId id) {
        Path path = pathOf(id);
        if (path == null || !underRoot(path)) {
            return;
        }
        overlays.remove(path);
        refreshDisk(path);
        publish();
    }

    /** Уведомление watcher-а: перезагружается только названный файл. */
    public synchronized void changedOnDisk(Path changed) {
        Path path = normal(changed);
        if (!underRoot(path) || overlays.containsKey(path)) {
            return;
        }
        refreshDisk(path);
        publish();
    }

    /** Снимок, который присоединяется к host/native каталогу через {@link Catalog#merged}. */
    public Catalog catalog() {
        return state.get().catalog();
    }

    public Collection<Path> roots() {
        return roots;
    }

    /** Анализ файла из опубликованного снимка или {@code null}, если его нет в workspace. */
    public FileAnalysis analysis(DocumentId id) {
        FileModule module = state.get().byDocument().get(id);
        return module == null ? null : module.analysis();
    }

    /** Исходник файла из опубликованного снимка или {@code null}. */
    public Source source(DocumentId id) {
        FileAnalysis analysis = analysis(id);
        return analysis == null ? null : analysis.source();
    }

    /** Экспорты для {@code workspace/symbol}; запрос пустой значит «все». */
    public List<WorkspaceSymbol> symbols(String query) {
        String needle = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        List<WorkspaceSymbol> found = new ArrayList<>();
        for (FileModule module : state.get().byDocument().values()) {
            for (Symbol symbol : module.exports().values()) {
                if (needle.isEmpty() || symbol.name().toLowerCase(java.util.Locale.ROOT)
                        .contains(needle)) {
                    found.add(new WorkspaceSymbol(symbol.name(), symbol.kind(), module.key(),
                            module.document(), symbol.nameSpan()));
                }
            }
        }
        found.sort(Comparator.comparing(WorkspaceSymbol::name)
                .thenComparing(symbol -> symbol.document().uri()));
        return List.copyOf(found);
    }

    /** Workspace-диагностика import плюс диагностика цикла, уже найденная в снимке. */
    public List<Diagnostic> diagnostics(DocumentId id, Catalog externalCatalog) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(externalCatalog, "externalCatalog");
        FileModule module = state.get().byDocument().get(id);
        if (module == null) {
            return List.of();
        }
        List<Diagnostic> found = new ArrayList<>(module.diagnostics());
        for (ImportStmt imported : importsOf(module.analysis())) {
            String key = normalizeKey(imported.path());
            State snapshot = state.get();
            if (snapshot.ambiguous().contains(key)) {
                found.add(error(imported.pathSpan(), "модуль '" + key
                        + "' найден в нескольких source roots"));
            } else if (snapshot.catalog().module(key) == null && externalCatalog.module(key) == null) {
                found.add(error(imported.pathSpan(), "модуль '" + key + "' не найден"));
            }
        }
        return List.copyOf(found);
    }

    /** Переход с {@code shapes.Circle} к экспорту соседнего файла. */
    public Location definition(DocumentId document, FileAnalysis analysis, Catalog catalog, int offset) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(analysis, "analysis");
        for (Node item : Nodes.pathAt(analysis.program(), offset).reversed()) {
            if (!(item instanceof AccessExpr access) || access.style() != AccessStyle.DOT
                    || !covers(access.key().span(), offset)) {
                continue;
            }
            ReceiverType receiver = ReceiverResolver.of(analysis, catalog).resolve(access.target());
            if (!(receiver instanceof ReceiverType.Module module)) {
                continue;
            }
            FileModule target = state.get().unique(module.descriptor().key());
            if (target == null) {
                return null;
            }
            Symbol exported = target.exports().get(access.literalKey());
            return exported == null ? null : new Location(target.document(), exported.nameSpan());
        }
        return null;
    }

    /** Все обращения {@code alias.Name} к одному экспортированному символу. */
    public List<Location> references(DocumentId declaration, Symbol symbol,
                                     boolean includeDeclaration, Catalog catalog) {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(catalog, "catalog");
        State snapshot = state.get();
        FileModule owner = snapshot.byDocument().get(declaration);
        if (owner == null || owner.exports().get(symbol.name()) != symbol) {
            return List.of();
        }
        List<Location> found = new ArrayList<>();
        if (includeDeclaration) {
            found.add(new Location(declaration, symbol.nameSpan()));
        }
        for (FileModule source : snapshot.byDocument().values()) {
            ReceiverResolver resolver = ReceiverResolver.of(source.analysis(), catalog);
            Nodes.walk(source.analysis().program(), node -> {
                if (!(node instanceof AccessExpr access) || access.style() != AccessStyle.DOT
                        || !symbol.name().equals(access.literalKey())) {
                    return;
                }
                ReceiverType receiver = resolver.resolve(access.target());
                if (receiver instanceof ReceiverType.Module module
                        && owner.key().equals(module.descriptor().key())) {
                    found.add(new Location(source.document(), access.key().span()));
                }
            });
        }
        found.sort(Comparator.comparing((Location location) -> location.document().uri())
                .thenComparingInt(location -> location.span().start()));
        return List.copyOf(found);
    }

    private void scan(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walked = Files.walk(root)) {
            walked.filter(Files::isRegularFile).filter(WorkspaceIndex::isWdl).forEach(this::read);
        } catch (IOException ignored) {
            // Недоступная source root не должна ломать сервер. Она может появиться позже.
        }
    }

    private void read(Path file) {
        Path path = normal(file);
        try {
            files.put(path, SourceFile.of(Document.of(DocumentId.of(path.toUri().toString()), 0,
                    Files.readString(path))));
        } catch (IOException ignored) {
            files.remove(path);
        }
    }

    private void refreshDisk(Path path) {
        if (Files.isRegularFile(path) && isWdl(path)) {
            read(path);
        } else {
            files.remove(path);
        }
    }

    private void publish() {
        Map<String, List<FileModule>> byKey = new LinkedHashMap<>();
        Map<DocumentId, FileModule> byDocument = new LinkedHashMap<>();
        for (SourceFile source : files.values()) {
            String key = keyOf(source.path());
            if (key == null) {
                continue;
            }
            FileModule module = FileModule.of(key, source.document());
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(module);
            byDocument.put(module.document(), module);
        }
        byKey.values().forEach(items -> items.sort(Comparator.comparing(item -> item.document().uri())));

        Map<String, ModuleDescriptor> modules = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (Map.Entry<String, List<FileModule>> entry : byKey.entrySet()) {
            if (entry.getValue().size() == 1) {
                FileModule module = entry.getValue().getFirst();
                modules.put(entry.getKey(), new ModuleDescriptor(entry.getKey(),
                        module.descriptors()));
            } else {
                ambiguous.add(entry.getKey());
            }
        }
        Map<DocumentId, List<Diagnostic>> cycleDiagnostics = cycleDiagnostics(byKey);
        Map<DocumentId, FileModule> decorated = new LinkedHashMap<>();
        for (FileModule module : byDocument.values()) {
            decorated.put(module.document(), module.withDiagnostics(
                    cycleDiagnostics.getOrDefault(module.document(), List.of())));
        }
        state.set(new State(new WorkspaceCatalog(modules), copy(byKey), Map.copyOf(decorated),
                Set.copyOf(ambiguous)));
    }

    private Map<DocumentId, List<Diagnostic>> cycleDiagnostics(Map<String, List<FileModule>> byKey) {
        Map<String, List<String>> graph = new HashMap<>();
        for (Map.Entry<String, List<FileModule>> entry : byKey.entrySet()) {
            if (entry.getValue().size() != 1) {
                continue;
            }
            List<String> imports = importsOf(entry.getValue().getFirst().analysis()).stream()
                    .map(ImportStmt::path).map(WorkspaceIndex::normalizeKey)
                    .filter(target -> byKey.getOrDefault(target, List.of()).size() == 1).toList();
            graph.put(entry.getKey(), imports);
        }
        Set<String> cyclic = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Set<String> active = new LinkedHashSet<>();
        for (String key : graph.keySet()) {
            findCycles(key, graph, visited, active, cyclic);
        }
        Map<DocumentId, List<Diagnostic>> result = new HashMap<>();
        for (String key : cyclic) {
            FileModule module = byKey.get(key).getFirst();
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (ImportStmt imported : importsOf(module.analysis())) {
                if (cyclic.contains(normalizeKey(imported.path()))) {
                    diagnostics.add(error(imported.pathSpan(), "циклический import: '" + key + "'"));
                }
            }
            result.put(module.document(), List.copyOf(diagnostics));
        }
        return result;
    }

    private static void findCycles(String key, Map<String, List<String>> graph, Set<String> visited,
                                   Set<String> active, Set<String> cyclic) {
        if (active.contains(key)) {
            cyclic.addAll(active);
            return;
        }
        if (!visited.add(key)) {
            return;
        }
        active.add(key);
        for (String next : graph.getOrDefault(key, List.of())) {
            findCycles(next, graph, visited, active, cyclic);
        }
        active.remove(key);
    }

    private static List<ImportStmt> importsOf(FileAnalysis analysis) {
        List<ImportStmt> found = new ArrayList<>();
        Nodes.walk(analysis.program(), node -> {
            if (node instanceof ImportStmt imported) {
                found.add(imported);
            }
        });
        return List.copyOf(found);
    }

    private boolean underRoot(Path path) {
        return roots.stream().anyMatch(path::startsWith);
    }

    private String keyOf(Path path) {
        for (Path root : roots) {
            if (path.startsWith(root)) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                return normalizeKey(relative);
            }
        }
        return null;
    }

    private static String normalizeKey(String path) {
        String key = path.replace('\\', '/');
        if (key.startsWith("/")) {
            key = key.substring(1);
        }
        return key.endsWith(".wdl") ? key.substring(0, key.length() - ".wdl".length()) : key;
    }

    private static boolean isWdl(Path path) {
        return path.getFileName().toString().endsWith(".wdl");
    }

    private static Path normal(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Path pathOf(DocumentId id) {
        try {
            URI uri = URI.create(id.uri());
            return "file".equalsIgnoreCase(uri.getScheme()) ? normal(Path.of(uri)) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean covers(Span span, int offset) {
        return !span.isNone() && offset >= span.start() && offset < span.end();
    }

    private static Diagnostic error(Span span, String message) {
        return new Diagnostic(Severity.ERROR, message, span);
    }

    private static Map<String, List<FileModule>> copy(Map<String, List<FileModule>> source) {
        Map<String, List<FileModule>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private record SourceFile(Path path, Document document) {
        static SourceFile of(Document document) {
            Path path = pathOf(document.id());
            if (path == null) {
                throw new IllegalArgumentException("документ не является file URI: " + document.id());
            }
            return new SourceFile(path, document);
        }
    }

    private record FileModule(String key, DocumentId document, FileAnalysis analysis,
                              Map<String, Symbol> exports, List<SymbolDescriptor> descriptors,
                              List<Diagnostic> diagnostics) {
        static FileModule of(String key, Document document) {
            ReceiverResolver resolver = ReceiverResolver.of(document.analysis(), Catalog.empty());
            Map<String, Symbol> exports = new LinkedHashMap<>();
            List<SymbolDescriptor> descriptors = new ArrayList<>();
            for (Symbol symbol : document.analysis().scopes().symbols()) {
                switch (symbol.kind()) {
                    case CLASS, TRAIT, FUNCTION, CONSTANT -> {
                        if (exports.putIfAbsent(symbol.name(), symbol) == null) {
                            descriptors.add(resolver.describe(symbol));
                        }
                    }
                    default -> { }
                }
            }
            return new FileModule(key, document.id(), document.analysis(), Map.copyOf(exports),
                    List.copyOf(descriptors), List.of());
        }

        FileModule withDiagnostics(List<Diagnostic> diagnostics) {
            return new FileModule(key, document, analysis, exports, descriptors, List.copyOf(diagnostics));
        }
    }

    private record State(WorkspaceCatalog catalog, Map<String, List<FileModule>> byKey,
                         Map<DocumentId, FileModule> byDocument, Set<String> ambiguous) {
        static State empty() {
            return new State(new WorkspaceCatalog(Map.of()), Map.of(), Map.of(), Set.of());
        }

        FileModule unique(String key) {
            List<FileModule> candidates = byKey.get(key);
            return candidates != null && candidates.size() == 1 ? candidates.getFirst() : null;
        }
    }
}
