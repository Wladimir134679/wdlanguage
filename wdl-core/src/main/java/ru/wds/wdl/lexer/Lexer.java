package ru.wds.wdl.lexer;

import ru.wds.wdl.diagnostic.DiagnosticCode;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Лексер: превращает текст скрипта в список токенов.
 * <p>
 * Три решения, определяющие всё остальное в этом классе:
 * <ol>
 *   <li><b>Ошибка не прерывает разбор.</b> Всё найденное складывается в
 *       {@link Diagnostics}, лексер сдвигается вперёд и продолжает. За один запуск
 *       пользователь видит все проблемы файла, а не первую.</li>
 *   <li><b>Перевод строки — обычный пробел.</b> Токена он не порождает: форматирование
 *       на разбор не влияет, {@code def f(a, b)\n    return a + b} и
 *       {@code def f(a, b) return a + b} — один и тот же код. Границы инструкций
 *       выводит парсер из грамматики. Для форматтера и диагностики факт переноса
 *       всё же сохраняется — в флаге {@link Token#afterNewline()}, не в потоке.</li>
 *   <li><b>Позиция — смещение</b> ({@link Span}), а не пара «строка:столбец».
 *       Лексер не считает строки: это работа {@link Source}, и делается она один раз
 *       при выводе диагностики, а не на каждом символе.</li>
 *   <li><b>Пробелы и комментарии видны только тому, кто их попросил.</b> В режиме
 *       {@link LexerMode#RUNTIME} их в потоке нет — разбору они не нужны никогда;
 *       в {@link LexerMode#LOSSLESS} поток покрывает документ встык и годится
 *       для подсветки, форматтера и подсказки по наведению. Значимые токены в обоих
 *       режимах одни и те же.</li>
 * </ol>
 * Экземпляр одноразовый; точка входа — {@link #tokenize(Source, Diagnostics)}.
 */
public final class Lexer {

    private static final Map<String, TokenType> KEYWORDS;
    private static final Map<String, TokenType> OPERATORS;
    private static final int MAX_OPERATOR_LENGTH;
    /** Маркер кодировки в начале файла (U+FEFF). */
    private static final char BOM = '\uFEFF';
    /** Быстрая проверка «символ может начинать оператор» — таблица по ASCII. */
    private static final boolean[] OPERATOR_START = new boolean[128];

    static {
        Map<String, TokenType> keywords = new HashMap<>();
        Map<String, TokenType> operators = new HashMap<>();
        int maxLength = 0;
        for (TokenType type : TokenType.values()) {
            if (type.isKeyword()) {
                keywords.put(type.text(), type);
            } else if (type.isOperator()) {
                operators.put(type.text(), type);
                maxLength = Math.max(maxLength, type.text().length());
                OPERATOR_START[type.text().charAt(0)] = true;
            }
        }
        KEYWORDS = Map.copyOf(keywords);
        OPERATORS = Map.copyOf(operators);
        MAX_OPERATOR_LENGTH = maxLength;
    }

    /**
     * Разбирает исходник на токены. Список всегда заканчивается {@link TokenType#EOF}
     * и всегда возвращается целиком — даже если были ошибки; проверять их надо
     * через {@link Diagnostics#hasErrors()}.
     */
    public static List<Token> tokenize(Source source, Diagnostics diagnostics) {
        return tokenize(source, diagnostics, LexerMode.RUNTIME);
    }

    /**
     * То же, но с выбором режима: {@link LexerMode#LOSSLESS} добавляет в поток
     * пробелы, комментарии и мусор, покрывая документ целиком.
     */
    public static List<Token> tokenize(Source source, Diagnostics diagnostics, LexerMode mode) {
        return new Lexer(source, diagnostics, mode).run();
    }

    private final String text;
    private final int length;
    private final Diagnostics diagnostics;
    private final LexerMode mode;
    private final List<Token> tokens = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder(32);
    /**
     * Строки с подстановкой, внутри которых сейчас читается код.
     * <p>
     * Стек, а не флаг: внутри {@code ${...}} снова бывает строка с подстановкой,
     * и уровней у неё столько, сколько написано.
     */
    private final ArrayDeque<Hole> holes = new ArrayDeque<>();
    /**
     * Многострочные строки, у которых ещё не снят общий отступ.
     * <p>
     * Куски такой строки уже стоят в потоке, но их текст известен только на
     * закрывающих кавычках: до них неизвестен левый край. Поэтому кусок ставится
     * пустым, а его сырые строки ждут здесь.
     */
    private final ArrayDeque<Block> blocks = new ArrayDeque<>();

    private int pos;
    /** Между прошлым токеном и текущей позицией был перевод строки. */
    private boolean afterNewline;

    private Lexer(Source source, Diagnostics diagnostics, LexerMode mode) {
        Objects.requireNonNull(source, "source");
        this.text = source.text();
        this.length = text.length();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    private List<Token> run() {
        // Редакторы Windows охотно ставят в начало UTF-8 файла BOM. Это маркер кодировки,
        // а не символ программы: пропускаем его молча, иначе первый же скрипт, сохранённый
        // «Блокнотом», падает с ошибкой про неизвестный символ в первой позиции.
        // Пропускаем, но не выбрасываем из позиций: смещения остаются индексами в тексте,
        // который дали. Редактор поэтому обязан лексить текст своего документа (BOM там
        // уже снят), а не байты файла — иначе позиции разъедутся на единицу.
        if (length > 0 && text.charAt(0) == BOM) {
            pos = 1;
            // В потоке с тривией маркер всё же виден — иначе покрытие документа начиналось
            // бы не с нуля, и «встык от начала до конца» перестало бы быть правдой.
            trivia(TokenType.WHITESPACE, 0);
        }
        while (pos < length) {
            char current = text.charAt(pos);
            if (current == '\n' && breaksHole()) {
                abandonHole("строка кончилась раньше");
            }
            if (Character.isWhitespace(current)) {
                whitespace();
            } else if (current == '/' && peek(1) == '/') {
                lineComment();
            } else if (current == '/' && peek(1) == '*') {
                blockComment();
            } else if (isDigit(current)) {
                number();
            } else if (isIdentifierStart(current)) {
                word();
            } else if (current == '"') {
                string();
            } else if (current == '`') {
                quotedName();
            } else if (current == '\'') {
                interpolatedString();
            } else if (current == '}' && closesHole()) {
                // Закрылась подстановка — дальше снова текст строки, а не код.
                if (holes.peek().multiline) {
                    multilinePiece(pos, false);
                } else {
                    stringPiece(pos, false);
                }
            } else {
                countBrace(current);
                if (!operator()) {
                    int start = pos++;
                    diagnostics.error(new Span(start, pos), DiagnosticCode.UNKNOWN_CHARACTER,
                            "неизвестный символ " + describe(current));
                    trivia(TokenType.BAD_CHARACTER, start);
                }
            }
        }
        // Файл кончился, а подстановка — нет. Закрываем сами: парсеру нужен конец
        // строки, иначе к одной беде он допишет вторую про «неожиданный конец файла».
        while (!holes.isEmpty()) {
            abandonHole("файл кончился раньше");
        }
        // Блок, не закрывшийся до конца файла, всё равно получает свой текст:
        // прочитанное — это то, что автор успел написать, и терять его незачем.
        while (!blocks.isEmpty()) {
            finishBlock(blocks.pop());
        }
        tokens.add(new Token(TokenType.EOF, "", Span.point(length), afterNewline));
        return tokens;
    }

    // --- крупные лексемы ----------------------------------------------------

    /**
     * Число: {@code 42}, {@code 1_000}, {@code 3.14}, {@code 6.02e23}, {@code 0xFF}, {@code 0b1010}.
     * <p>
     * Точка съедается только если за ней идёт цифра — иначе {@code 1.toString()} и
     * будущий диапазон {@code 1..5} стали бы неразбираемыми.
     */
    private void number() {
        int start = pos;
        if (text.charAt(pos) == '0' && pos + 1 < length) {
            char marker = text.charAt(pos + 1);
            if (marker == 'x' || marker == 'X') {
                radixNumber(start, TokenType.HEX, 16, "шестнадцатеричное");
                return;
            }
            if (marker == 'b' || marker == 'B') {
                radixNumber(start, TokenType.BIN, 2, "двоичное");
                return;
            }
        }

        buffer.setLength(0);
        digits(10);
        boolean fractional = false;
        if (peek(0) == '.' && isDigit(peek(1))) {
            fractional = true;
            buffer.append('.');
            pos++;
            digits(10);
        }
        char marker = peek(0);
        if ((marker == 'e' || marker == 'E') && hasExponentDigits()) {
            fractional = true;
            buffer.append('e');
            pos++;
            char sign = peek(0);
            if (sign == '+' || sign == '-') {
                buffer.append(sign);
                pos++;
            }
            digits(10);
        }
        rejectSuffix(start, "число");
        add(fractional ? TokenType.FLOAT : TokenType.INT, buffer.toString(), start);
    }

    private boolean hasExponentDigits() {
        int at = pos + 1;
        if (at < length && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
            at++;
        }
        return at < length && isDigit(text.charAt(at));
    }

    private void radixNumber(int start, TokenType type, int radix, String name) {
        pos += 2; // 0x или 0b
        buffer.setLength(0);
        if (digits(radix) == 0) {
            diagnostics.error(new Span(start, pos), name + " число без цифр");
            buffer.append('0');
        }
        rejectSuffix(start, name + " число");
        add(type, buffer.toString(), start);
    }

    /**
     * Собирает цифры в {@link #buffer}, пропуская разделители {@code _}.
     *
     * @return сколько цифр собрано
     */
    private int digits(int radix) {
        int count = 0;
        boolean trailingSeparator = false;
        while (pos < length) {
            char current = text.charAt(pos);
            if (current == '_') {
                trailingSeparator = true;
                pos++;
                continue;
            }
            if (digitValue(current, radix) < 0) {
                break;
            }
            buffer.append(current);
            trailingSeparator = false;
            count++;
            pos++;
        }
        if (trailingSeparator) {
            diagnostics.error(new Span(pos - 1, pos), "разделитель '_' не может стоять в конце числа");
        }
        return count;
    }

    /**
     * Буква сразу после числа — почти всегда опечатка ({@code 10px}, {@code 0xFFg}).
     * Съедаем её вместе с продолжением, чтобы не породить лавину ошибок на хвосте.
     */
    private void rejectSuffix(int start, String what) {
        if (pos >= length || !isIdentifierPart(text.charAt(pos))) {
            return;
        }
        int suffixStart = pos;
        while (pos < length && isIdentifierPart(text.charAt(pos))) {
            pos++;
        }
        diagnostics.error(new Span(start, pos),
                what + " не может заканчиваться на '" + text.substring(suffixStart, pos) + "'");
    }

    private void word() {
        int start = pos;
        while (pos < length && isIdentifierPart(text.charAt(pos))) {
            pos++;
        }
        String word = text.substring(start, pos);
        // Пропуск читается здесь, а не таблицей ключевых слов: '_' пишется буквами
        // имени, но именем не является, и в OPERATORS ему тоже не место. Проверка
        // одна на весь лексер — ровно потому, что таких слов ровно одно.
        if (word.equals(TokenType.HOLE.text())) {
            add(TokenType.HOLE, start);
            return;
        }
        TokenType keyword = KEYWORDS.get(word);
        if (keyword != null) {
            add(keyword, start);
        } else {
            add(TokenType.WORD, word, start);
        }
    }

    /**
     * Строковый литерал в двойных кавычках. Незакрытая строка обрывается на конце
     * строки исходника, а не съедает весь остаток файла: одна забытая кавычка не должна
     * превращаться в сотню ошибок ниже по тексту.
     */
    private void string() {
        // Три кавычки подряд — многострочная строка, и правила у неё свои.
        if (peek(1) == '"' && peek(2) == '"') {
            textBlock();
            return;
        }
        int start = pos;
        pos++; // открывающая кавычка
        buffer.setLength(0);
        boolean closed = false;
        boolean noted = false;
        while (pos < length) {
            char current = text.charAt(pos);
            if (current == '"') {
                pos++;
                closed = true;
                break;
            }
            if (current == '\n') {
                break;
            }
            if (current == '$' && peek(1) == '{' && !noted) {
                noted = true;
                noteLiteralHole();
            }
            if (current == '\\') {
                escape();
                continue;
            }
            buffer.append(current);
            pos++;
        }
        if (!closed) {
            diagnostics.error(new Span(start, pos), DiagnosticCode.UNCLOSED_STRING,
                    "строка не закрыта кавычкой");
        }
        add(TokenType.STRING, buffer.toString(), start);
    }

    /**
     * Строка в одинарных кавычках: с подстановками {@code ${...}} или без них.
     * <p>
     * <b>Без единой подстановки получается обычный {@link TokenType#STRING}.</b>
     * Иначе литерал в одинарных кавычках нельзя было бы писать там, где нужен именно
     * литерал, — в пути {@code import} или ключом объекта, — и разница между двумя
     * видами кавычек стала бы разницей «где вообще можно писать строку». Отката для
     * этого не нужно: первый же кусок сам говорит, чем он кончился.
     * <p>
     * С подстановками строка режется на куски прямо здесь, а между ними идут обычные
     * токены кода. Почему не вторым проходом по готовому литералу — сказано
     * у {@link TokenType#STRING_START}.
     */
    private void interpolatedString() {
        if (peek(1) == '\'' && peek(2) == '\'') {
            multilineString();
            return;
        }
        stringPiece(pos, true);
    }

    /**
     * Многострочная строка с подстановкой: {@code '''} , перевод строки, текст,
     * {@code '''}.
     * <p>
     * Правила отступа те же, что у блока в двойных кавычках, и считает их тот же
     * {@link TextBlocks}. Разница одна: отступ мерится только по строкам, которые
     * в блоке начались, — см. {@link #finishBlock(Block)}.
     */
    private void multilineString() {
        int start = pos;
        pos += 3;
        openingLine("'''");
        blocks.push(new Block());
        multilinePiece(start, true);
    }

    /**
     * Кусок многострочной строки: от кавычек или от закрытой подстановки до
     * следующего {@code ${} либо до закрывающих кавычек.
     * <p>
     * Текст куска здесь ещё не собирается — только сырые строки: общий отступ станет
     * известен на закрывающих кавычках, а escape разворачиваются уже после него.
     */
    private void multilinePiece(int start, boolean opening) {
        if (!opening) {
            pos++; // скобка, закрывшая подстановку
        }
        List<TextBlocks.Line> lines = new ArrayList<>();
        boolean closed = false;
        boolean hole = false;
        while (pos < length) {
            int lineStart = pos;
            boolean broke = false;
            while (pos < length) {
                char current = text.charAt(pos);
                if (current == '\'' && peek(1) == '\'' && peek(2) == '\'') {
                    closed = true;
                    break;
                }
                if (current == '$' && peek(1) == '{') {
                    hole = true;
                    break;
                }
                if (current == '\n' || current == '\r') {
                    broke = true;
                    break;
                }
                if (current == '\\') {
                    char next = peek(1);
                    pos += next == '\n' || next == '\r' || pos + 1 >= length ? 1 : 2;
                    continue;
                }
                pos++;
            }
            lines.add(new TextBlocks.Line(text.substring(lineStart, pos), lineStart));
            if (closed) {
                pos += 3;
                break;
            }
            if (hole) {
                pos += 2;
                break;
            }
            if (!broke) {
                break;
            }
            skipLineBreak();
        }
        if (!closed && !hole) {
            diagnostics.error(new Span(start, pos), DiagnosticCode.UNCLOSED_STRING,
                    "многострочная строка не закрыта: нет закрывающих '''");
        }
        if (opening) {
            add(hole ? TokenType.STRING_START : TokenType.STRING, "", start);
        } else {
            add(hole ? TokenType.STRING_PART : TokenType.STRING_END, "", start);
        }
        blocks.peek().chunks.add(new Chunk(tokens.size() - 1, lines));
        if (hole) {
            if (opening) {
                holes.push(new Hole(true));
            }
            return;
        }
        if (!opening) {
            holes.pop();
        }
        finishBlock(blocks.pop());
    }

    /**
     * Дочитанная многострочная строка: снять общий отступ, развернуть escape
     * и подставить получившийся текст в уже поставленные токены.
     * <p>
     * <b>Отступ мерится только по строкам, которые в блоке начались.</b> Кусок после
     * {@code ${...}} продолжает чужую строку, и его левый край принадлежит не ему;
     * иначе подстановка, записанная в начале строки, сдвигала бы весь текст. По той же
     * причине строке, кончившейся подстановкой, не режется хвост: пробел перед
     * {@code ${} стоит в середине текста, а не на его краю.
     */
    private void finishBlock(Block block) {
        List<Chunk> chunks = block.chunks;
        List<TextBlocks.Line> measured = new ArrayList<>();
        for (int c = 0; c < chunks.size(); c++) {
            List<TextBlocks.Line> lines = chunks.get(c).lines();
            for (int i = 0; i < lines.size(); i++) {
                if (startsLine(c, i)) {
                    measured.add(lines.get(i));
                }
            }
        }
        // Закрывающие кавычки задают край, только если их строка началась в блоке.
        int last = chunks.size() - 1;
        boolean closingOwnsLine = startsLine(last, chunks.get(last).lines().size() - 1);
        int indent = TextBlocks.indent(measured, closingOwnsLine);
        if (TextBlocks.mixedIndentation(measured)) {
            diagnostics.warning(new Span(chunks.get(0).lines().get(0).offset(), pos),
                    "в отступе многострочной строки смешаны пробелы и табуляции:"
                            + " таб считается за один символ, и сдвиг получится не тем,"
                            + " что видно в редакторе");
        }
        for (int c = 0; c < chunks.size(); c++) {
            Chunk chunk = chunks.get(c);
            List<TextBlocks.Line> lines = chunk.lines();
            boolean lastChunk = c == chunks.size() - 1;
            buffer.setLength(0);
            for (int i = 0; i < lines.size(); i++) {
                boolean lastLine = i == lines.size() - 1;
                TextBlocks.Line line = TextBlocks.strip(lines.get(i), indent, startsLine(c, i),
                        !lastLine || lastChunk);
                boolean joined = expand(line);
                if (!lastLine && !joined) {
                    buffer.append('\n');
                }
            }
            Token piece = tokens.get(chunk.token());
            tokens.set(chunk.token(), new Token(piece.type(), buffer.toString(), piece.span(),
                    piece.afterNewline()));
        }
    }

    /**
     * Один кусок строки: от кавычки или от закрытой подстановки до ближайшего
     * {@code ${}, закрывающей кавычки или конца строки.
     * <p>
     * Разделители в интервал куска входят, а своих токенов не образуют: поток так
     * покрывает документ встык, и по интервалу видно, где именно кончился текст.
     *
     * @param start   начало интервала: открывающая кавычка или закрывающая скобка
     * @param opening кусок открывает строку, а не продолжает её после подстановки
     */
    private void stringPiece(int start, boolean opening) {
        boolean multiline = !opening && holes.peek().multiline;
        pos++; // открывающая кавычка либо скобка, закрывшая подстановку
        buffer.setLength(0);
        boolean closed = false;
        boolean hole = false;
        while (pos < length) {
            char current = text.charAt(pos);
            if (current == '\'') {
                pos++;
                closed = true;
                break;
            }
            if (current == '\n' && !multiline) {
                break;
            }
            if (current == '$' && peek(1) == '{') {
                pos += 2;
                hole = true;
                break;
            }
            if (current == '\\') {
                escape();
                continue;
            }
            buffer.append(current);
            pos++;
        }
        if (!closed && !hole) {
            diagnostics.error(new Span(start, pos), DiagnosticCode.UNCLOSED_STRING,
                    "строка не закрыта кавычкой");
        }
        if (opening) {
            add(hole ? TokenType.STRING_START : TokenType.STRING, buffer.toString(), start);
            if (hole) {
                holes.push(new Hole(multiline));
            }
            return;
        }
        add(hole ? TokenType.STRING_PART : TokenType.STRING_END, buffer.toString(), start);
        if (!hole) {
            holes.pop();
        }
    }

    /** Сейчас читается код подстановки, и скобка её закрывает, а не чей-то объект. */
    private boolean closesHole() {
        return !holes.isEmpty() && holes.peek().braces == 0;
    }

    /**
     * Перевод строки обрывает подстановку однострочной строки.
     * <p>
     * Иначе одна опечатка — открытая подстановка без закрывающей скобки — съедала бы
     * остаток файла как код. Правило то же, что у незакрытой строки: беда остаётся
     * в своей строке.
     */
    private boolean breaksHole() {
        return !holes.isEmpty() && !holes.peek().multiline;
    }

    /**
     * Закрывает подстановку, которая не закрылась сама: говорит об ошибке и ставит
     * пустой {@link TokenType#STRING_END} нулевой длины в точке обрыва.
     * <p>
     * Токен именно ставится, а не пропускается: разбор ждёт конца строки, и без него
     * к одной беде добавилось бы второе сообщение — про то, чего автор не писал.
     */
    private void abandonHole(String reason) {
        diagnostics.error(Span.point(pos), DiagnosticCode.UNCLOSED_INTERPOLATION,
                "подстановка не закрыта: " + reason + " закрывающей скобки");
        holes.pop();
        tokens.add(new Token(TokenType.STRING_END, "", Span.point(pos), afterNewline));
        afterNewline = false;
    }

    /**
     * Считает фигурные скобки внутри подстановки.
     * <p>
     * Без счёта объект в подстановке закрывал бы строку своей же скобкой. Это
     * единственная неоднозначность записи, и снимается она здесь целиком.
     */
    private void countBrace(char current) {
        if (holes.isEmpty()) {
            return;
        }
        if (current == '{') {
            holes.peek().braces++;
        } else if (current == '}') {
            holes.peek().braces--;
        }
    }

    /**
     * Похоже на подстановку, но написано в двойных кавычках.
     * <p>
     * Предупреждение, а не ошибка: буквальный текст со скобками — законная строка,
     * например шаблон для чужого движка. Но промах между двумя видами кавычек —
     * самая частая ошибка первых дней, и молчать о нём дороже.
     */
    private void noteLiteralHole() {
        diagnostics.warning(new Span(pos, Math.min(pos + 2, length)),
                DiagnosticCode.LITERAL_INTERPOLATION,
                "в двойных кавычках подстановка не работает: строка с подстановкой"
                        + " пишется одинарными кавычками");
    }

    /**
     * Началась ли строка в самом блоке — то есть принадлежит ли ей её левый край.
     * <p>
     * Не начинается только первая строка куска, идущего после подстановки: она
     * продолжает строку, начатую до {@code ${...}}. У первого куска первая строка —
     * это начало текста блока, и отступ у неё свой.
     */
    private static boolean startsLine(int chunk, int line) {
        return chunk == 0 || line > 0;
    }

    /** Многострочная строка, читаемая сейчас: её куски ждут общего отступа. */
    private static final class Block {

        private final List<Chunk> chunks = new ArrayList<>();
    }

    /** Кусок текста: место уже поставленного токена и сырые строки, из которых он выйдет. */
    private record Chunk(int token, List<TextBlocks.Line> lines) {
    }

    /** Открытая подстановка: чем закрывается строка и сколько скобок внутри кода. */
    private static final class Hole {

        private final boolean multiline;
        private int braces;

        private Hole(boolean multiline) {
            this.multiline = multiline;
        }
    }
    /**
     * Имя в обратных кавычках: {@code `+`}, {@code `class`}.
     * <p>
     * Лексер здесь не знает ни про операторы, ни про члены типов: кавычки — это
     * <b>экранированное имя вообще</b>. Внутри лежит что угодно, кроме перевода
     * строки и самой кавычки, а вопрос «бывает ли такое имя в этом месте» решает
     * разбор — там, где у него есть ответ. Отсюда даром получается и будущее
     * {@code `class`} для поля, открытого мостом в Java.
     * <p>
     * Незакрытая кавычка обрывается на конце строки — по той же причине, что и
     * незакрытая строка: одна опечатка не должна съедать остаток файла. Токена при
     * этом не появляется вовсе: дописывать за автора несуществующее имя значит
     * породить вторую ошибку там, где виновата первая.
     */
    private void quotedName() {
        int start = pos;
        pos++; // открывающая кавычка
        int from = pos;
        while (pos < length && text.charAt(pos) != '`' && text.charAt(pos) != '\n') {
            pos++;
        }
        String name = text.substring(from, pos);
        if (pos >= length || text.charAt(pos) == '\n') {
            diagnostics.error(new Span(start, pos), "не закрыта обратная кавычка: имя '" + name
                    + "' не кончилось до конца строки");
            return;
        }
        pos++; // закрывающая кавычка
        if (name.isEmpty()) {
            diagnostics.error(new Span(start, pos),
                    "пустое имя в обратных кавычках: между кавычками должно быть имя");
            return;
        }
        tokens.add(new Token(TokenType.WORD, name, new Span(start, pos), afterNewline, true));
        afterNewline = false;
    }

    /**
     * Многострочная строка: {@code """} , перевод строки, текст, {@code """}.
     * <p>
     * Значение собирается в три фазы, и порядок между ними принципиален — тот же,
     * что в спецификации Java. Сначала читается сырой текст построчно, потом
     * {@link TextBlocks} снимает общий отступ, и только затем разворачиваются
     * escape-последовательности. Слить фазы нельзя: написанный автором {@code \n}
     * не должен участвовать в делении на строки и влиять на отступ.
     * <p>
     * Перевод строки любого вида в значение не попадает — строки соединяются через
     * {@code '\n'}. CRLF нормализуется этим даром, и это не мелочь: без нормализации
     * один и тот же файл давал бы разное значение строки в зависимости от настроек
     * git у того, кто его выгрузил.
     * <p>
     * Незакрытый блок съедает остаток файла, в отличие от обычной строки, которая
     * обрывается на конце строки. Обрыв по строкам тут невозможен по определению,
     * а прецедент в языке есть — незакрытый блочный комментарий ведёт себя так же.
     */
    private void textBlock() {
        int start = pos;
        pos += 3;
        openingLine("\"\"\"");
        List<TextBlocks.Line> lines = new ArrayList<>();
        boolean closed = false;
        boolean noted = false;
        while (pos < length) {
            int lineStart = pos;
            while (pos < length) {
                char current = text.charAt(pos);
                if (current == '"' && peek(1) == '"' && peek(2) == '"') {
                    closed = true;
                    break;
                }
                if (current == '\n' || current == '\r') {
                    break;
                }
                if (current == '$' && peek(1) == '{' && !noted) {
                    noted = true;
                    noteLiteralHole();
                }
                if (current == '\\') {
                    // Пара съедается целиком: иначе \\""" закрыло бы блок, а \\ в конце
                    // строки притворилось бы склейкой. Обратная косая перед переводом
                    // строки остаётся последним символом строки — с ней разбирается expand.
                    char next = peek(1);
                    pos += next == '\n' || next == '\r' || pos + 1 >= length ? 1 : 2;
                    continue;
                }
                pos++;
            }
            lines.add(new TextBlocks.Line(text.substring(lineStart, pos), lineStart));
            if (closed) {
                pos += 3;
                break;
            }
            if (pos >= length) {
                break;
            }
            skipLineBreak();
        }
        if (!closed) {
            diagnostics.error(new Span(start, pos), DiagnosticCode.UNCLOSED_STRING,
                    "многострочная строка не закрыта: нет закрывающих '\"\"\"'");
        }
        add(TokenType.STRING, blockValue(lines), start);
    }

    /**
     * Первая строка блока: после открывающих кавычек до конца строки допустимы только
     * пробелы.
     * <p>
     * Требование не церемониальное. Именно оно делает левый край текста задаваемым
     * отступом, а не позицией открывающих кавычек, — иначе первая строка жила бы
     * по одним правилам, а остальные по другим. Написанное здесь по ошибке в значение
     * не попадает: дописывать за автора несуществующий текст значит породить вторую
     * беду там, где уже названа первая.
     */
    private void openingLine(String quotes) {
        int from = pos;
        while (pos < length && text.charAt(pos) != '\n' && text.charAt(pos) != '\r') {
            pos++;
        }
        if (!text.substring(from, pos).isBlank()) {
            diagnostics.error(new Span(from, pos),
                    "после открывающих '" + quotes + "' должен идти перевод строки:"
                            + " текст многострочной строки начинается со следующей строки");
        }
        if (pos < length) {
            skipLineBreak();
        }
    }

    /** Перевод строки любого вида — один шаг: {@code \r\n} тоже. */
    private void skipLineBreak() {
        pos += text.charAt(pos) == '\r' && peek(1) == '\n' ? 2 : 1;
    }

    /** Значение блока: снять общий отступ, развернуть escape, соединить через {@code '\n'}. */
    private String blockValue(List<TextBlocks.Line> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        int indent = TextBlocks.indent(lines);
        if (TextBlocks.mixedIndentation(lines)) {
            diagnostics.warning(new Span(lines.get(0).offset(), pos),
                    "в отступе многострочной строки смешаны пробелы и табуляции:"
                            + " таб считается за один символ, и сдвиг получится не тем,"
                            + " что видно в редакторе");
        }
        List<TextBlocks.Line> stripped = TextBlocks.strip(lines, indent);
        buffer.setLength(0);
        for (int i = 0; i < stripped.size(); i++) {
            boolean joined = expand(stripped.get(i));
            if (i < stripped.size() - 1 && !joined) {
                buffer.append('\n');
            }
        }
        return buffer.toString();
    }

    /**
     * Разворачивает escape-последовательности одной строки блока в {@link #buffer}
     * и говорит, кончилась ли строка склейкой.
     * <p>
     * Читает из исходника, а не из готовой подстроки: текст строки — всегда кусок
     * файла, поэтому позиции у диагностики про escape остаются настоящими.
     * Обратная косая последним символом строки — это склейка: перевод строки после
     * неё в значение не попадает, и длинный абзац можно уложить в ширину экрана.
     */
    private boolean expand(TextBlocks.Line line) {
        int saved = pos;
        int to = line.end();
        pos = line.offset();
        boolean joined = false;
        while (pos < to) {
            char current = text.charAt(pos);
            if (current == '\\') {
                if (pos + 1 >= to) {
                    joined = true;
                    break;
                }
                escape();
                continue;
            }
            buffer.append(current);
            pos++;
        }
        pos = saved;
        return joined;
    }

    private void escape() {
        int start = pos;
        pos++; // обратная косая
        if (pos >= length) {
            diagnostics.error(new Span(start, pos), "незавершённая escape-последовательность");
            return;
        }
        char current = text.charAt(pos);
        switch (current) {
            case 'n' -> append('\n');
            case 't' -> append('\t');
            case 'r' -> append('\r');
            case 'b' -> append('\b');
            case 'f' -> append('\f');
            // Пробел, который переживёт срез хвостовых пробелов в многострочной
            // строке. В обычной строке резать нечего, но и запрещать там '\s'
            // незачем: одна escape-последовательность не должна значить разное
            // в двух записях.
            case 's' -> append(' ');
            case '0' -> append('\0');
            case '\\' -> append('\\');
            // Буквальный доллар перед скобкой: без него подстановку не записать
            // текстом в строке, которая подстановки понимает.
            case '$' -> append('$');
            case '"' -> append('"');
            case '\'' -> append('\'');
            case 'u' -> unicodeEscape(start);
            default -> {
                diagnostics.error(new Span(start, pos + 1),
                        "неизвестная escape-последовательность '\\" + current + "'");
                // Берём символ как есть: скорее всего человек имел в виду именно его.
                append(current);
            }
        }
    }

    private void append(char value) {
        buffer.append(value);
        pos++;
    }

    private void unicodeEscape(int start) {
        pos++; // u
        int value = 0;
        int count = 0;
        while (count < 4 && pos < length) {
            int digit = digitValue(text.charAt(pos), 16);
            if (digit < 0) {
                break;
            }
            value = value * 16 + digit;
            count++;
            pos++;
        }
        if (count < 4) {
            diagnostics.error(new Span(start, pos), "'\\u' требует ровно четыре шестнадцатеричные цифры");
            return;
        }
        buffer.append((char) value);
    }

    /**
     * Оператор по принципу самого длинного совпадения: {@code >>>=} разбирается как
     * один токен, а не как {@code >>} + {@code >=}. Проверка идёт от длинных лексем
     * к коротким по единственной таблице, поэтому оператор, префикс которого сам
     * оператором не является, тоже разберётся правильно.
     *
     * @return {@code false}, если ни один оператор не подошёл
     */
    private boolean operator() {
        char first = text.charAt(pos);
        if (first >= OPERATOR_START.length || !OPERATOR_START[first]) {
            return false;
        }
        int max = Math.min(MAX_OPERATOR_LENGTH, length - pos);
        for (int len = max; len >= 1; len--) {
            TokenType type = OPERATORS.get(text.substring(pos, pos + len));
            if (type == null) {
                continue;
            }
            int start = pos;
            pos += len;
            add(type, start);
            return true;
        }
        return false;
    }

    /**
     * Пробельный кусок целиком — одним токеном, а не по символу: подсветке и форматтеру
     * нужен интервал «между этими двумя токенами ничего нет», а не список пробелов.
     */
    private void whitespace() {
        int start = pos;
        while (pos < length && Character.isWhitespace(text.charAt(pos))) {
            if (text.charAt(pos) == '\n') {
                afterNewline = true;
            }
            pos++;
        }
        trivia(TokenType.WHITESPACE, start);
    }

    private void lineComment() {
        int start = pos;
        while (pos < length && text.charAt(pos) != '\n') {
            pos++;
        }
        trivia(TokenType.LINE_COMMENT, start);
    }

    private void blockComment() {
        int start = pos;
        pos += 2; // /*
        while (pos < length) {
            char current = text.charAt(pos);
            if (current == '*' && peek(1) == '/') {
                pos += 2;
                trivia(TokenType.BLOCK_COMMENT, start);
                return;
            }
            if (current == '\n') {
                // Комментарий на несколько строк — это тоже перенос: важно форматтеру.
                afterNewline = true;
            }
            pos++;
        }
        diagnostics.error(new Span(start, Math.min(start + 2, length)),
                DiagnosticCode.UNCLOSED_COMMENT, "комментарий не закрыт '*/'");
        // Диагностика показывает на открывающие '/*' — там ошибка, — а токен берёт всё
        // до конца документа: незакрытый комментарий и правда съедает остаток файла,
        // и подсветка обязана показать это так же, как показала бы IDE.
        trivia(TokenType.BLOCK_COMMENT, start);
    }

    // --- служебное ----------------------------------------------------------

    private void add(TokenType type, int start) {
        add(type, type.text(), start);
    }

    private void add(TokenType type, String value, int start) {
        tokens.add(new Token(type, value, new Span(start, pos), afterNewline));
        afterNewline = false;
    }

    /**
     * Токен тривии — только в режиме {@link LexerMode#LOSSLESS}.
     * <p>
     * Текст пустой: содержимое читается из {@link Source} по интервалу, как и у строк.
     * Флаг {@code afterNewline} тривия не несёт и <b>не сбрасывает</b> — он принадлежит
     * следующему значимому токену, и комментарий между строками не должен его съедать.
     */
    private void trivia(TokenType type, int start) {
        if (mode == LexerMode.LOSSLESS) {
            tokens.add(new Token(type, "", new Span(start, pos)));
        }
    }

    private char peek(int offset) {
        int at = pos + offset;
        return at < length ? text.charAt(at) : '\0';
    }

    /**
     * Только ASCII-цифры. {@link Character#isDigit(char)} принимает и восточноарабские,
     * и деванагари — такое число потом не разберёт ни один {@code parseLong}.
     */
    private static boolean isDigit(char current) {
        return current >= '0' && current <= '9';
    }

    private static int digitValue(char current, int radix) {
        int value;
        if (current >= '0' && current <= '9') {
            value = current - '0';
        } else if (current >= 'a' && current <= 'f') {
            value = current - 'a' + 10;
        } else if (current >= 'A' && current <= 'F') {
            value = current - 'A' + 10;
        } else {
            return -1;
        }
        return value < radix ? value : -1;
    }

    /** Буквы любого алфавита: имена на кириллице — осознанно разрешены. */
    private static boolean isIdentifierStart(char current) {
        return Character.isLetter(current) || current == '_' || current == '$';
    }

    private static boolean isIdentifierPart(char current) {
        return isIdentifierStart(current) || isDigit(current);
    }

    private static String describe(char current) {
        if (current < ' ' || current == 127) {
            return String.format("U+%04X", (int) current);
        }
        return "'" + current + "'";
    }
}
