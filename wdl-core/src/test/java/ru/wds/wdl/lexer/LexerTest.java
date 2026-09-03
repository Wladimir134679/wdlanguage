package ru.wds.wdl.lexer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.source.Source;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexerTest {

    /** Токены без служебного EOF — его наличие проверяется отдельно. */
    private static List<Token> lex(String code) {
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        return tokens.subList(0, tokens.size() - 1);
    }

    private static Diagnostics diagnose(String code) {
        return new Diagnostics(Source.ofString(code));
    }

    private static List<TokenType> types(String code) {
        return lex(code).stream().map(Token::type).collect(Collectors.toList());
    }

    private static String texts(String code) {
        return lex(code).stream().map(Token::text).collect(Collectors.joining("|"));
    }

    @Test
    @DisplayName("поток всегда заканчивается EOF на конце текста")
    void alwaysEndsWithEof() {
        String code = "a = 1";
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnose(code));

        Token last = tokens.get(tokens.size() - 1);
        assertEquals(TokenType.EOF, last.type());
        assertEquals(code.length(), last.span().start());
    }

    @Test
    @DisplayName("одиночное '_' — пропуск, а '_x', '__' и '_1' остаются именами")
    void holeIsOnlyOneUnderscore() {
        assertEquals(List.of(TokenType.HOLE), types("_"));
        assertEquals(List.of(TokenType.WORD, TokenType.WORD, TokenType.WORD), types("_x __ _1"));
        assertEquals("_x|__|_1", texts("_x __ _1"));
        // Текст у пропуска есть — он нужен диагностике, — а место обычное.
        assertEquals("_", lex("_").get(0).text());
        assertEquals(List.of(TokenType.HOLE, TokenType.COMMA, TokenType.WORD), types("_, x"));
    }

    @Test
    @DisplayName("имена, ключевые слова и позиции")
    void wordsAndKeywords() {
        List<Token> tokens = lex("def сумма(a) { return a }");

        assertEquals(List.of(TokenType.DEF, TokenType.WORD, TokenType.LPAREN, TokenType.WORD,
                TokenType.RPAREN, TokenType.LBRACE, TokenType.RETURN, TokenType.WORD, TokenType.RBRACE),
                tokens.stream().map(Token::type).toList());
        assertEquals("сумма", tokens.get(1).text());
        assertEquals(4, tokens.get(1).span().start());
        assertEquals(9, tokens.get(1).span().end());
    }

    @Test
    @DisplayName("true, false и null — литералы, а не обычные имена")
    void literalKeywords() {
        assertEquals(List.of(TokenType.TRUE, TokenType.FALSE, TokenType.NULL), types("true false null"));
    }

    @Test
    @DisplayName("слова классов — ключевые: trait, with, super, is")
    void classKeywords() {
        assertEquals(List.of(TokenType.CLASS, TokenType.TRAIT, TokenType.NEW, TokenType.THIS,
                TokenType.SUPER, TokenType.WITH, TokenType.IS),
                types("class trait new this super with is"));
    }

    @Test
    @DisplayName("const — ключевое слово, а не обычное имя")
    void constKeyword() {
        assertEquals(List.of(TokenType.CONST, TokenType.WORD, TokenType.ASSIGN, TokenType.INT),
                types("const LIMIT = 10"));
    }

    @Test
    @DisplayName("println — обычное имя: печать это функция, а не синтаксис")
    void printIsNotKeyword() {
        assertEquals(List.of(TokenType.WORD, TokenType.LPAREN, TokenType.STRING, TokenType.RPAREN),
                types("println(\"x\")"));
    }

    @Test
    @DisplayName("числа всех форм, разделители выбрасываются")
    void numbers() {
        assertEquals(List.of(TokenType.INT), types("42"));
        assertEquals("1000000", texts("1_000_000"));
        assertEquals(List.of(TokenType.FLOAT), types("63.2"));
        assertEquals("6.02e23", texts("6.02e23"));
        assertEquals("1.6e-19", texts("1.6e-19"));
        assertEquals(List.of(TokenType.FLOAT), types("1e10"));
        assertEquals("DEADBEEF", texts("0xDEAD_BEEF"));
        assertEquals(List.of(TokenType.HEX), types("0XFF"));
        assertEquals("10100101", texts("0b1010_0101"));
    }

    @Test
    @DisplayName("экспонента любой величины — забота парсера, а не лексера")
    void hugeExponent() {
        assertEquals("1e999999", texts("1e999999"));
    }

    @Test
    @DisplayName("точка после числа не съедается: 1.toString() и 1..5 остаются разбираемыми")
    void dotAfterNumber() {
        assertEquals(List.of(TokenType.INT, TokenType.DOT, TokenType.WORD, TokenType.LPAREN, TokenType.RPAREN),
                types("1.round()"));
        // Диапазон ничего лексеру не стоил: '..' — обычный оператор, а числа его
        // не откусывают, потому что дробная часть требует цифру после точки.
        assertEquals(List.of(TokenType.INT, TokenType.DOTDOT, TokenType.INT), types("1..5"));
        assertEquals(List.of(TokenType.FLOAT, TokenType.DOTDOT, TokenType.INT), types("1.5..3"));
        assertEquals(List.of(TokenType.WORD, TokenType.DOTDOT, TokenType.WORD), types("x..y"));
        // На этом держатся члены числа: '213.toString()' разбирается как обращение
        // ровно потому, что дробная часть требует цифру ПОСЛЕ точки. Чинить лексер
        // «правильнее» нельзя — сломается весь набор членов числа.
        assertEquals(List.of(TokenType.INT, TokenType.DOT, TokenType.WORD, TokenType.LPAREN, TokenType.RPAREN),
                types("213.toString()"));
        assertEquals(List.of(TokenType.FLOAT, TokenType.DOT, TokenType.WORD), types("2.7.floor"));
    }

    @Test
    @DisplayName("оператор берётся самым длинным из возможных")
    void maximalMunch() {
        assertEquals(List.of(TokenType.WORD, TokenType.USHRASSIGN, TokenType.INT), types("a >>>= 1"));
        assertEquals(List.of(TokenType.WORD, TokenType.USHR, TokenType.INT), types("a >>> 1"));
        assertEquals(List.of(TokenType.WORD, TokenType.SHR, TokenType.INT), types("a >> 1"));
        assertEquals(List.of(TokenType.WORD, TokenType.GTEQ, TokenType.INT), types("a >= 1"));
        assertEquals(List.of(TokenType.WORD, TokenType.ANDAND, TokenType.WORD), types("a && b"));
        assertEquals(List.of(TokenType.WORD, TokenType.ARROW, TokenType.WORD), types("a -> b"));
        assertEquals(List.of(TokenType.WORD, TokenType.MINUS, TokenType.GT, TokenType.WORD), types("a - > b"));
    }

    @Test
    @DisplayName("строки: escape-последовательности разворачиваются в значение")
    void strings() {
        assertEquals("привет\nмир\t\"да\"\\", texts("\"привет\\nмир\\t\\\"да\\\"\\\\\""));
        assertEquals("✓", texts("\"\\u2713\""));
        assertEquals("", texts("\"\""));
    }

    @Test
    @DisplayName("перевод строки — обычный пробел: перенос не меняет поток токенов")
    void newlineIsWhitespace() {
        List<TokenType> expected = List.of(TokenType.DEF, TokenType.WORD, TokenType.LPAREN, TokenType.WORD,
                TokenType.COMMA, TokenType.WORD, TokenType.RPAREN, TokenType.RETURN, TokenType.WORD,
                TokenType.PLUS, TokenType.WORD);

        assertEquals(expected, types("def f(a, b)\n    return a + b"));
        assertEquals(expected, types("def f(a, b) return a + b"));
        assertEquals(expected, types("def\nf(\na,\nb\n)\nreturn\na\n+\nb\n"));
    }

    @Test
    @DisplayName("строка, начатая со скобки, приклеивается к предыдущей — как и любой пробел")
    void nextLineJoinsPreviousExpression() {
        assertEquals(types("a = b(c + d).print()"), types("a = b\n(c + d).print()"));
        assertEquals(types("x = arr[1, 2]"), types("x = arr\n[1, 2]"));
    }

    @Test
    @DisplayName("пробел всё же меняет границы лексем: '--' против '- -'")
    void whitespaceSplitsLexemes() {
        assertEquals(List.of(TokenType.WORD, TokenType.MINUSMINUS, TokenType.WORD), types("a--b"));
        assertEquals(List.of(TokenType.WORD, TokenType.MINUS, TokenType.MINUS, TokenType.WORD), types("a - -b"));
        assertEquals(List.of(TokenType.WORD), types("ab"));
        assertEquals(List.of(TokenType.WORD, TokenType.WORD), types("a b"));
    }

    @Test
    @DisplayName("пустые строки и отступы не порождают токенов")
    void blankLinesProduceNothing() {
        assertEquals(List.of(TokenType.WORD, TokenType.WORD), types("\n\n a \n\n\t b \n\n"));
    }

    @Test
    @DisplayName("точка с запятой — обычный токен, пустая секция в заголовке for сохраняется")
    void semicolonIsToken() {
        assertEquals(List.of(TokenType.FOR, TokenType.LPAREN, TokenType.SEMICOLON, TokenType.SEMICOLON,
                TokenType.WORD, TokenType.PLUSPLUS, TokenType.RPAREN), types("for ( ; ; i++)"));
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.INT, TokenType.SEMICOLON,
                TokenType.WORD, TokenType.ASSIGN, TokenType.INT), types("a = 1; b = 2"));
    }

    @Test
    @DisplayName("флаг afterNewline помнит перенос, которого нет в потоке токенов")
    void afterNewlineFlag() {
        List<Token> tokens = lex("a = 1\nb = 2 c");

        assertFalse(tokens.get(0).afterNewline(), "первый токен файла");
        assertFalse(tokens.get(2).afterNewline(), "1");
        assertTrue(tokens.get(3).afterNewline(), "b — с новой строки");
        assertFalse(tokens.get(6).afterNewline(), "c — та же строка");
    }

    @Test
    @DisplayName("комментарии не порождают токенов")
    void comments() {
        assertEquals(List.of(TokenType.WORD), types("a // хвост строки"));
        assertEquals(List.of(TokenType.WORD, TokenType.WORD), types("a /* внутри */ b"));
        assertEquals(List.of(TokenType.WORD, TokenType.WORD), types("a /* через\nстроки */ b"));
        assertTrue(lex("a /* через\nстроки */ b").get(1).afterNewline(),
                "многострочный комментарий — это тоже перенос");
    }

    @Test
    @DisplayName("неизвестный символ не останавливает разбор")
    void recoversFromUnknownCharacter() {
        String code = "a = # + b";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertEquals(1, diagnostics.errorCount());
        assertTrue(diagnostics.renderAll().contains("неизвестный символ '#'"));
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.PLUS, TokenType.WORD, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    @DisplayName("незакрытая строка обрывается на конце строки, а не съедает файл")
    void unterminatedString() {
        String code = "a = \"забыли\nb = 2";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("строка не закрыта"));
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.STRING,
                TokenType.WORD, TokenType.ASSIGN, TokenType.INT, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    @DisplayName("каждая ошибка сообщается один раз, разбор идёт до конца файла")
    void collectsEveryError() {
        String code = "a = 10px\nb = 0x\nc = 1_\n";
        Diagnostics diagnostics = diagnose(code);
        Lexer.tokenize(Source.ofString(code), diagnostics);

        assertEquals(3, diagnostics.errorCount(), diagnostics.renderAll());
    }

    @Test
    @DisplayName("диагностика показывает строку исходника и место ошибки")
    void diagnosticPointsAtSource() {
        String code = "a = 1\nb = #2\n";
        Diagnostics diagnostics = diagnose(code);
        Lexer.tokenize(Source.ofString(code), diagnostics);

        String rendered = diagnostics.renderAll();
        assertTrue(rendered.contains("<script>:2:5: ошибка:"), rendered);
        assertTrue(rendered.contains("b = #2"), rendered);
        assertTrue(rendered.contains("^"), rendered);
    }

    @Test
    @DisplayName("восточноарабские цифры — не цифры языка")
    void onlyAsciiDigits() {
        String code = "a = ٥";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    @DisplayName("BOM в начале файла — маркер кодировки, а не символ программы")
    void byteOrderMarkIsSkipped() {
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.INT), types("\uFEFFa = 1"));
    }

    @Test
    @DisplayName("пустой исходник даёт только EOF")
    void emptySource() {
        List<Token> tokens = Lexer.tokenize(Source.ofString(""), diagnose(""));

        assertEquals(1, tokens.size());
        assertEquals(TokenType.EOF, tokens.get(0).type());
    }
}
