package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.AccessStyle;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.tools.analysis.FileAnalysis;
import ru.wds.wdl.tools.analysis.Symbol;
import ru.wds.wdl.tools.analysis.SymbolKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Файл и каталог одним ответом: что видно в этой точке и что это за имя.
 * <p>
 * Анализ отвечает про файл, каталог — про всё остальное, и складывать их каждому
 * потребителю самому незачем: правило сложения одно и живёт здесь. Правило простое —
 * <b>имя файла сильнее одноимённого внешнего</b>: скрипт вправе завести свой
 * {@code println}, и предлагать вместо него встроенный было бы ложью.
 * <p>
 * <b>Про типы здесь нет ничего.</b> После точки отвечается ровно один случай —
 * псевдоним модуля, — и он не требует вывода типов вовсе: путь модуля написан
 * в {@code import} буквально. Всё остальное после точки ждёт своего этапа, и пустой
 * список честнее выдуманного.
 */
public final class Lookup {

    private final FileAnalysis analysis;
    private final Catalog catalog;

    private Lookup(FileAnalysis analysis, Catalog catalog) {
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public static Lookup of(FileAnalysis analysis, Catalog catalog) {
        return new Lookup(analysis, catalog);
    }

    public FileAnalysis analysis() {
        return analysis;
    }

    public Catalog catalog() {
        return catalog;
    }

    /**
     * Что предложить в этой точке: имена, члены модуля или пути модулей — по месту
     * в дереве, а не по догадке о том, что человек набирает.
     */
    public List<Suggestion> completeAt(int offset) {
        List<Node> path = Nodes.pathAtCaret(analysis.program(), offset);
        ImportStmt importing = importPathAt(path, offset);
        if (importing != null) {
            return modulePaths();
        }
        String module = moduleAliasBefore(path, offset);
        if (module != null) {
            return moduleNames(module);
        }
        if (afterDot(path, offset)) {
            // Получатель не модуль: чем он окажется, знают типы, а их пока нет.
            return List.of();
        }
        return namesAt(offset);
    }

    /**
     * Имена, видимые в точке: сначала объявленные в файле, затем внешние.
     * <p>
     * Порядок — это и есть ответ на затенение: одно имя встречается один раз,
     * и побеждает ближнее.
     */
    public List<Suggestion> namesAt(int offset) {
        List<Suggestion> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Symbol symbol : analysis.visibleAt(offset)) {
            if (seen.add(symbol.name())) {
                found.add(Suggestion.of(symbol));
            }
        }
        for (SymbolDescriptor descriptor : catalog.roots()) {
            if (seen.add(descriptor.name())) {
                found.add(Suggestion.of(descriptor));
            }
        }
        return List.copyOf(found);
    }

    /** Имена модуля по его ключу: {@code sys/io} → {@code read}, {@code write}, … */
    public List<Suggestion> moduleNames(String key) {
        ModuleDescriptor module = catalog.module(key);
        if (module == null) {
            return List.of();
        }
        List<Suggestion> found = new ArrayList<>(module.names().size());
        for (SymbolDescriptor descriptor : module.names()) {
            found.add(Suggestion.of(descriptor));
        }
        return List.copyOf(found);
    }

    /** Пути известных модулей — то, что предлагают после {@code import}. */
    public List<Suggestion> modulePaths() {
        List<Suggestion> found = new ArrayList<>();
        for (String key : catalog.moduleKeys()) {
            ModuleDescriptor module = catalog.module(key);
            found.add(new Suggestion(key, SymbolKind.MODULE, key,
                    module == null ? null : module.documentation(), Origin.MODULE, null));
        }
        return List.copyOf(found);
    }

    /**
     * Что за имя под курсором: объявление из файла, имя из каталога, член модуля
     * или сам модуль в строке {@code import}.
     * <p>
     * {@code null} — по-прежнему обычный ответ: имя может завести приложение уже
     * после того, как каталог сняли.
     */
    public Suggestion describeAt(int offset) {
        Symbol declared = analysis.resolve(offset).orElse(null);
        if (declared != null) {
            return Suggestion.of(declared);
        }
        List<Node> path = Nodes.pathAt(analysis.program(), offset);
        ImportStmt importing = importPathAt(path, offset);
        if (importing != null) {
            ModuleDescriptor module = catalog.module(importing.path());
            return module == null ? null : new Suggestion(module.name(), SymbolKind.MODULE,
                    "import " + module.key(), module.documentation(), Origin.MODULE, null);
        }
        SymbolDescriptor member = memberAt(path, offset);
        if (member != null) {
            return Suggestion.of(member);
        }
        var reference = analysis.referenceAt(offset);
        if (reference == null) {
            return null;
        }
        SymbolDescriptor root = catalog.root(reference.name());
        return root == null ? null : Suggestion.of(root);
    }

    /** Член модуля под курсором: {@code io.read} — то, что описано в самом модуле. */
    private SymbolDescriptor memberAt(List<Node> path, int offset) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (!(path.get(i) instanceof AccessExpr access) || access.style() != AccessStyle.DOT) {
                continue;
            }
            String field = access.fieldName();
            if (field == null || !covers(access.key().span(), offset)) {
                continue;
            }
            String key = moduleKeyOf(access.target());
            ModuleDescriptor module = key == null ? null : catalog.module(key);
            if (module != null) {
                return module.get(field);
            }
        }
        return null;
    }

    /**
     * Ключ модуля, к которому идёт обращение слева от точки, или {@code null}.
     * <p>
     * Разрешение идёт через анализ, а не по тексту: одноимённая локальная переменная
     * затеняет псевдоним модуля, и предлагать после неё имена {@code sys.io} было бы
     * враньём.
     */
    private String moduleAliasBefore(List<Node> path, int offset) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (!(path.get(i) instanceof AccessExpr access) || access.style() != AccessStyle.DOT) {
                continue;
            }
            if (offset <= access.target().span().end()) {
                continue;
            }
            String key = moduleKeyOf(access.target());
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private String moduleKeyOf(Node target) {
        if (!(target instanceof VariableExpr variable)) {
            return null;
        }
        Symbol symbol = analysis.resolve(variable.span().start()).orElse(null);
        if (symbol == null || symbol.kind() != SymbolKind.MODULE) {
            return null;
        }
        return symbol.declaration() instanceof ImportStmt statement ? statement.path() : null;
    }

    /** Стоит ли курсор после точки — тогда предлагают члены, а не имена. */
    private static boolean afterDot(List<Node> path, int offset) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof AccessExpr access && access.style() == AccessStyle.DOT
                    && offset > access.target().span().end()) {
                return true;
            }
        }
        return false;
    }

    /** Импорт, у которого курсор стоит на пути модуля. */
    private static ImportStmt importPathAt(List<Node> path, int offset) {
        for (int i = path.size() - 1; i >= 0; i--) {
            if (path.get(i) instanceof ImportStmt statement
                    && covers(statement.pathSpan(), offset)) {
                return statement;
            }
        }
        return null;
    }

    private static boolean covers(Span span, int offset) {
        return !span.isNone() && offset >= span.start()
                && (offset <= span.end());
    }

    @Override
    public String toString() {
        return "поиск по " + analysis + " и " + catalog;
    }
}
