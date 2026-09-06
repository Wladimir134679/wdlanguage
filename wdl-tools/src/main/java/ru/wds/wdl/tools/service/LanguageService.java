package ru.wds.wdl.tools.service;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.tools.analysis.FileAnalysis;
import ru.wds.wdl.tools.analysis.Reference;
import ru.wds.wdl.tools.analysis.Symbol;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.Lookup;
import ru.wds.wdl.tools.catalog.Suggestion;
import ru.wds.wdl.tools.workspace.WorkspaceIndex;
import ru.wds.wdl.source.Source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Языковой сервис: всё, что редактор спрашивает про открытый файл, — обычным Java API.
 * <p>
 * Ни слова про LSP и IntelliJ. Потребители равноправны: языковой сервер переводит
 * эти ответы в JSON-RPC, плагин — в свои точки расширения, а приложение со своим
 * редактором зовёт их прямо в своём процессе, без сокета и сериализации. Ради
 * последнего слой и вынесен наружу — сериализация имеет смысл только на границе
 * процессов.
 * <p>
 * <b>Координаты — интервалы, а не строка и столбец.</b> {@link Span} — система
 * координат ядра, и она же годится редактору: строка и столбец нужны одному
 * протоколу и считаются там, где нужны ({@link ru.wds.wdl.source.Source#positionOf}).
 * <p>
 * <b>Промах — не отказ.</b> Вопрос про закрытый документ, про место, где имени нет,
 * про имя, которого нет в каталоге, — обычное дело: ответом идёт пустой список или
 * {@code null}, а не исключение. Редактор спрашивает про каждое движение курсора,
 * и половина этих вопросов заведомо ни к чему не ведёт.
 * <p>
 * <b>Пользовательский код не выполняется.</b> Правило {@link FileAnalysis}
 * наследуется дословно: анализ файла, который стирает диск, обязан быть безопасным.
 * <p>
 * Класс, а не интерфейс: второй реализации не предвидится, а интерфейс с одной —
 * обещание, которое некому проверить. Состояние — только открытые документы,
 * и оно конкурентное; читать ответы можно из любого потока.
 */
public final class LanguageService {

    private final DocumentStore documents = new DocumentStore();
    private final Catalog catalog;
    private final WorkspaceIndex workspace = new WorkspaceIndex();

    private LanguageService(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Сервис с каталогом внешних имён. Каталог подаётся снаружи: что доступно
     * скрипту, знает тот, кто собрал движок, а не инструмент.
     */
    public static LanguageService of(Catalog catalog) {
        return new LanguageService(catalog);
    }

    /** Сервис, знающий только про файл: имена извне ему никто не назвал. */
    public static LanguageService withoutCatalog() {
        return new LanguageService(Catalog.empty());
    }

    public Catalog catalog() {
        return catalog;
    }

    /** Настраивает безопасный индекс .wdl-файлов; открытые буферы автоматически сильнее диска. */
    public void configureWorkspace(Collection<Path> sourceRoots) {
        workspace.configure(sourceRoots);
        for (DocumentId id : documents.ids()) {
            Document document = documents.get(id);
            if (document != null) {
                workspace.openOrChange(document);
            }
        }
    }

    public WorkspaceIndex workspace() {
        return workspace;
    }

    // --- документы ----------------------------------------------------------

    public Document open(DocumentId id, long version, String text) {
        Document document = documents.open(id, version, text);
        workspace.openOrChange(document);
        return document;
    }

    /** Правка. Устаревшая отбрасывается — см. {@link DocumentStore#change}. */
    public Document change(DocumentId id, long version, String text) {
        Document document = documents.change(id, version, text);
        workspace.openOrChange(document);
        return document;
    }

    public Document close(DocumentId id) {
        Document document = documents.close(id);
        workspace.close(id);
        return document;
    }

    /** Открытый документ или {@code null}. */
    public Document document(DocumentId id) {
        return documents.get(id);
    }

    public boolean isOpen(DocumentId id) {
        return documents.isOpen(id);
    }

    public Collection<DocumentId> openDocuments() {
        return documents.ids();
    }

    /** Забывает все открытые документы: сервер закрывается, редактор — тоже. */
    public void closeAll() {
        documents.clear();
    }

    // --- ответы редактору ---------------------------------------------------

    /** Ошибки и предупреждения разбора. Для закрытого документа — пустой список. */
    public List<Diagnostic> diagnostics(DocumentId id) {
        Document document = documents.get(id);
        if (document == null) {
            return List.of();
        }
        List<Diagnostic> found = new ArrayList<>(document.analysis().diagnostics().all());
        found.addAll(workspace.diagnostics(id, catalog));
        return List.copyOf(found);
    }

    /**
     * Что предложить в этой точке: имена, члены модуля или пути после {@code import}.
     * Правило сложения файла с каталогом живёт в {@link Lookup}.
     */
    public List<Suggestion> complete(DocumentId id, int offset) {
        Lookup lookup = lookup(id);
        return lookup == null ? List.of() : lookup.completeAt(offset);
    }

    /** Что за имя под курсором, или {@code null}. */
    public Hover hover(DocumentId id, int offset) {
        Document document = documents.get(id);
        if (document == null) {
            return null;
        }
        Suggestion described = Lookup.of(document.analysis(), effectiveCatalog()).describeAt(offset);
        return described == null ? null : new Hover(described.signature(),
                described.documentation(), described.kind(), described.origin(),
                nameSpanAt(document.analysis(), offset));
    }

    /**
     * Где объявлено имя под курсором, или {@code null}.
     * <p>
     * Только внутри этого файла: у встроенной функции места в тексте нет вовсе,
     * а переход по {@code import} к чужому файлу — работа с индексом рабочей папки,
     * которой ещё нет.
     */
    public Location definition(DocumentId id, int offset) {
        Document document = documents.get(id);
        if (document == null) {
            return null;
        }
        Symbol symbol = document.analysis().resolve(offset).orElse(null);
        if (symbol != null) {
            return new Location(id, symbol.nameSpan());
        }
        return workspace.definition(id, document.analysis(), effectiveCatalog(), offset);
    }

    /**
     * Где ещё встречается имя под курсором. Сравнение идёт по объявлению, а не по
     * строке: одноимённая переменная соседней функции — другое имя.
     */
    public List<Location> references(DocumentId id, int offset, boolean includeDeclaration) {
        Document document = documents.get(id);
        if (document == null) {
            return List.of();
        }
        Symbol symbol = document.analysis().resolve(offset).orElse(null);
        List<Location> found = new ArrayList<>();
        if (symbol != null) {
            if (includeDeclaration && !symbol.nameSpan().isNone()) {
                found.add(new Location(id, symbol.nameSpan()));
            }
            for (Span usage : document.analysis().usages(symbol)) {
                // Присваивание, заведшее имя, — одновременно объявление и употребление:
                // 'price = 120' и объявляет, и пишет. Показывать его дважды нельзя,
                // а прятать при includeDeclaration = false — обязательно: спрашивали
                // именно «где ещё», а это то самое место.
                if (!usage.equals(symbol.nameSpan())) {
                    found.add(new Location(id, usage));
                }
            }
            found.addAll(workspace.references(id, symbol, includeDeclaration, effectiveCatalog()));
        } else {
            Location definition = workspace.definition(id, document.analysis(), effectiveCatalog(), offset);
            if (definition == null) {
                return List.of();
            }
            Document target = documents.get(definition.document());
            FileAnalysis targetAnalysis = target == null ? workspace.analysis(definition.document())
                    : target.analysis();
            Symbol exported = targetAnalysis == null ? null
                    : targetAnalysis.resolve(definition.span().start()).orElse(null);
            if (exported != null) {
                found.addAll(workspace.references(definition.document(), exported, includeDeclaration,
                        effectiveCatalog()));
            }
        }
        return found.stream().distinct().sorted(Comparator.comparing((Location location) ->
                location.document().uri()).thenComparingInt(location -> location.span().start())).toList();
    }

    /** Состав файла деревом. */
    public List<Outline> outline(DocumentId id) {
        Document document = documents.get(id);
        return document == null ? List.of() : Outlines.of(document.analysis());
    }

    /** Окрашенные куски текста слева направо. */
    public List<HighlightToken> highlight(DocumentId id) {
        Document document = documents.get(id);
        return document == null ? List.of() : Highlights.of(document.analysis(), effectiveCatalog());
    }

    /** Файл плюс каталог одним объектом — для вопросов, которых здесь нет. */
    public Lookup lookup(DocumentId id) {
        Document document = documents.get(id);
        return document == null ? null : Lookup.of(document.analysis(), effectiveCatalog());
    }

    /** Сигнатура функции или метода под курсором; неизвестный динамический вызов молчит. */
    public CallSignature signatureHelp(DocumentId id, int offset) {
        Lookup lookup = lookup(id);
        if (lookup == null) {
            return null;
        }
        Suggestion suggestion = lookup.callAt(offset);
        return suggestion == null || !suggestion.kind().isCallable() ? null
                : new CallSignature(suggestion.signature(), suggestion.documentation(), 0);
    }

    /** Текст открытого или проиндексированного файла: нужен LSP для чужого URI. */
    public Source source(DocumentId id) {
        Document document = documents.get(id);
        return document != null ? document.source() : workspace.source(id);
    }

    public List<WorkspaceSymbol> workspaceSymbols(String query) {
        return workspace.symbols(query);
    }

    private Catalog effectiveCatalog() {
        return Catalog.merged(workspace.catalog(), catalog);
    }

    /**
     * Интервал имени под курсором: сначала употребление, затем объявление,
     * иначе — узел, который там стоит.
     * <p>
     * Нужен подсказке: редактор подсвечивает то, о чём отвечает, и интервал всего
     * выражения на месте одного имени выглядел бы ошибкой.
     */
    private static Span nameSpanAt(FileAnalysis analysis, int offset) {
        Reference reference = analysis.referenceAt(offset);
        if (reference != null) {
            return reference.span();
        }
        for (Symbol symbol : analysis.symbols()) {
            if (covers(symbol.nameSpan(), offset)) {
                return symbol.nameSpan();
            }
        }
        Node node = analysis.nodeAt(offset);
        return node == null ? Span.point(offset) : node.span();
    }

    private static boolean covers(Span span, int offset) {
        return !span.isNone() && offset >= span.start() && offset < span.end();
    }

    @Override
    public String toString() {
        return "языковой сервис: " + documents + ", " + catalog;
    }
}
