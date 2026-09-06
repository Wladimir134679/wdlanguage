package ru.wds.wdl.tools.analysis;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Разобранный и проанализированный файл: дерево, диагностика, области, имена.
 * <p>
 * Это ответ на вопросы редактора — «что здесь за имя», «где оно объявлено», «где ещё
 * оно встречается», «что видно в этой точке». Ни одного из них нельзя задать движку:
 * <b>статической модели имён в языке нет по решению</b>, имя ищется в момент
 * выполнения. Поэтому модель строится здесь, как инструмент, и на путь запуска
 * скрипта не влияет.
 * <p>
 * <b>Пользовательский код не выполняется.</b> Ни ради типа, ни ради значения
 * по умолчанию, ни ради {@code import}: анализ файла, который стирает диск, обязан
 * быть безопасным.
 * <p>
 * Результат неизменяем и не хранит ничего изменяемого — его можно держать в кэше
 * по «файл + версия документа» и читать из любого потока.
 */
public final class FileAnalysis {

    private final Source source;
    private final Program program;
    private final Diagnostics diagnostics;
    private final LexicalScope root;
    private final List<Symbol> symbols;
    private final List<Reference> references;
    private final Comments comments;

    private FileAnalysis(Source source, Program program, Diagnostics diagnostics,
                         ScopeBuilder.Result scopes, Comments comments) {
        this.source = source;
        this.program = program;
        this.diagnostics = diagnostics;
        this.root = scopes.root();
        this.references = scopes.references();
        this.comments = comments;
        this.symbols = comments.documented(scopes.root());
    }

    /** Разбирает и анализирует текст: лексер, парсер, области, комментарии. */
    public static FileAnalysis of(Source source) {
        Objects.requireNonNull(source, "source");
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        return of(source, program, diagnostics);
    }

    /**
     * То же для уже разобранного файла — когда дерево получено раньше и второй разбор
     * не нужен: так делает сервер, у которого дерево уже есть от подсветки.
     */
    public static FileAnalysis of(Source source, Program program, Diagnostics diagnostics) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(diagnostics, "diagnostics");
        return new FileAnalysis(source, program, diagnostics,
                ScopeBuilder.build(program), Comments.of(source));
    }

    public Source source() {
        return source;
    }

    public Program program() {
        return program;
    }

    /** Диагностика разбора. Анализ своей не добавляет — см. {@link #resolve(int)}. */
    public Diagnostics diagnostics() {
        return diagnostics;
    }

    /** Область файла: корень дерева областей. */
    public LexicalScope scopes() {
        return root;
    }

    /** Все объявления файла в текстовом порядке — из этого строится структура файла. */
    public List<Symbol> symbols() {
        return symbols;
    }

    /** Все употребления простых имён — для подсветки и поиска. */
    public List<Reference> references() {
        return references;
    }

    public Comments comments() {
        return comments;
    }

    /** Самая глубокая область, накрывающая смещение; для конца файла — сам файл. */
    public LexicalScope scopeAt(int offset) {
        LexicalScope current = root;
        boolean descended = true;
        while (descended) {
            descended = false;
            for (LexicalScope child : current.children()) {
                if (child.covers(offset)) {
                    current = child;
                    descended = true;
                    break;
                }
            }
        }
        return current;
    }

    /**
     * Имена, видимые в этой точке, — ближние раньше. Ровно то, что предлагают
     * дополнением: правило целиком в {@link Visibility}.
     */
    public List<Symbol> visibleAt(int offset) {
        return Visibility.visibleAt(scopeAt(offset), offset);
    }

    /**
     * Объявление, к которому ведёт смещение: внутри имени объявления — оно само,
     * внутри употребления — то, куда оно ведёт.
     * <p>
     * Пусто — обычное дело, а не ошибка: имя может быть встроенным, приходить
     * из библиотеки или заводиться приложением. Каталог таких имён — следующий этап.
     */
    public Optional<Symbol> resolve(int offset) {
        for (Symbol symbol : symbols) {
            if (covers(symbol.nameSpan(), offset)) {
                return Optional.of(symbol);
            }
        }
        Reference reference = referenceAt(offset);
        return reference == null
                ? Optional.empty()
                : Optional.ofNullable(declarationOf(reference));
    }

    /** Употребление под смещением или {@code null}. */
    public Reference referenceAt(int offset) {
        for (Reference reference : references) {
            if (covers(reference.span(), offset)) {
                return reference;
            }
        }
        return null;
    }

    /**
     * Употребления имени в файле — те, что ведут именно к этому объявлению.
     * <p>
     * Сравнение строк здесь не годится: одноимённая переменная из соседней функции —
     * другое имя, и переименование, построенное на строках, испортило бы её.
     */
    public List<Span> usages(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        List<Span> found = new ArrayList<>();
        for (Reference reference : references) {
            if (reference.name().equals(symbol.name()) && symbol.equals(declarationOf(reference))) {
                found.add(reference.span());
            }
        }
        return List.copyOf(found);
    }

    /** Объявление, к которому ведёт употребление, или {@code null}. */
    public Symbol declarationOf(Reference reference) {
        Objects.requireNonNull(reference, "reference");
        int offset = reference.span().start();
        return Visibility.resolve(reference.name(), scopeAt(offset), offset);
    }

    /** Узел под смещением — тот же ответ, что даёт {@link Nodes#deepestAt}. */
    public Node nodeAt(int offset) {
        return Nodes.deepestAt(program, offset);
    }

    /** Текст по интервалу — то, что редактор показал бы выделением. */
    public String textOf(Span span) {
        return span.isNone() ? "" : source.text().substring(span.start(), span.end());
    }

    private static boolean covers(Span span, int offset) {
        return !span.isNone() && offset >= span.start()
                && (offset < span.end() || span.length() == 0 && offset == span.start());
    }

    @Override
    public String toString() {
        return "FileAnalysis[" + source.name() + ": имён " + symbols.size()
                + ", употреблений " + references.size() + "]";
    }
}
