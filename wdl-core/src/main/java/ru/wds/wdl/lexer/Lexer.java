package ru.wds.wdl.lexer;

import ru.wds.wdl.diagnostic.DiagnosticCode;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

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
            } else if (!operator()) {
                int start = pos++;
                diagnostics.error(new Span(start, pos), DiagnosticCode.UNKNOWN_CHARACTER,
                        "неизвестный символ " + describe(current));
                trivia(TokenType.BAD_CHARACTER, start);
            }
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
        int start = pos;
        pos++; // открывающая кавычка
        buffer.setLength(0);
        boolean closed = false;
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
            case '0' -> append('\0');
            case '\\' -> append('\\');
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
