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

    // --- имя в обратных кавычках --------------------------------------------

    @Test
    @DisplayName("обратные кавычки дают обычное имя с пометкой: текст без кавычек")
    void quotedName() {
        List<Token> tokens = lex("`+` `<=>` `hello world` `class`");

        assertEquals(List.of(TokenType.WORD, TokenType.WORD, TokenType.WORD, TokenType.WORD),
                tokens.stream().map(Token::type).toList());
        assertEquals("+|<=>|hello world|class", texts("`+` `<=>` `hello world` `class`"));
        assertTrue(tokens.stream().allMatch(Token::quoted), "все четыре имени в кавычках");
        // Обычное имя пометки не несёт: по ней разбор и отличает объявление оператора.
        assertFalse(lex("plus").get(0).quoted());
    }

    @Test
    @DisplayName("место имени в кавычках включает сами кавычки")
    void quotedNameSpan() {
        Token name = lex("`+`").get(0);

        assertEquals(0, name.span().start());
        assertEquals(3, name.span().end());
    }

    @Test
    @DisplayName("незакрытая кавычка обрывается на конце строки, следующая строка цела")
    void unterminatedQuotedName() {
        String code = "def `+ (right)\nb = 2";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("не закрыта обратная кавычка"),
                diagnostics.renderAll());
        // Имени не появилось вовсе: дописывать за автора несуществующее имя значило бы
        // породить вторую ошибку там, где виновата первая.
        assertEquals(List.of(TokenType.DEF, TokenType.WORD, TokenType.ASSIGN,
                        TokenType.INT, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    @DisplayName("пустое имя в кавычках — ошибка, но разбор идёт дальше")
    void emptyQuotedName() {
        String code = "`` b = 2";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("пустое имя в обратных кавычках"),
                diagnostics.renderAll());
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.INT, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    @DisplayName("одинокая кавычка в конце файла — та же ошибка, а не крах")
    void danglingQuote() {
        String code = "a = 1\n`";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("не закрыта обратная кавычка"),
                diagnostics.renderAll());
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());
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

    // --- многострочная строка ------------------------------------------------

    @Test
    @DisplayName("многострочная строка — один токен со снятым отступом")
    void textBlock() {
        String code = "text = \"\"\"\n"
                + "    привет\n"
                + "    мир\n"
                + "    \"\"\"";
        List<Token> tokens = lex(code);

        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.STRING),
                tokens.stream().map(Token::type).toList());
        assertEquals("привет\nмир\n", tokens.get(2).text());
        // Интервал покрывает запись целиком — от первой кавычки до последней.
        assertEquals(code.indexOf('"'), tokens.get(2).span().start());
        assertEquals(code.length(), tokens.get(2).span().end());
    }

    @Test
    @DisplayName("закрывающие кавычки на строке текста: переноса в конце нет")
    void closingOnTextLine() {
        assertEquals("привет", lex("\"\"\"\n    привет\"\"\"").get(0).text());
    }

    @Test
    @DisplayName("CRLF и CR приводятся к одному виду: значение не зависит от того, чем выгружен файл")
    void normalizesLineBreaks() {
        assertEquals("a\nb\n",
                lex("\"\"\"\r\n  a\r\n  b\r\n  \"\"\"").get(0).text());
        assertEquals("a\nb\n",
                lex("\"\"\"\r  a\r  b\r  \"\"\"").get(0).text());
    }

    @Test
    @DisplayName("escape разворачивается после снятия отступа")
    void escapesAfterIndent() {
        // Написанный автором перевод строки — обычный escape: ни на деление строк,
        // ни на отступ он не влияет.
        assertEquals("a\nb", lex("\"\"\"\n    a\\nb\"\"\"").get(0).text());
        assertEquals("три \"кавычки\"",
                lex("\"\"\"\n    три \\\"кавычки\\\"\"\"\"").get(0).text());
        // Пробел, записанный escape-последовательностью, переживает срез хвоста.
        assertEquals("край   ", lex("\"\"\"\n    край  \\s\"\"\"").get(0).text());
    }

    @Test
    @DisplayName("обратная косая в конце строки склеивает строки")
    void joinsLines() {
        assertEquals("длинный текст",
                lex("\"\"\"\n    длинный \\\n    текст\"\"\"").get(0).text());
        // Экранированная косая склейкой не считается.
        assertEquals("конец \\\n",
                lex("\"\"\"\n    конец \\\\\n    \"\"\"").get(0).text());
    }

    @Test
    @DisplayName("незакрытый блок доходит до конца файла и говорит об этом один раз")
    void unterminatedTextBlock() {
        String code = "a = \"\"\"\n    забыли\nb = 2";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("многострочная строка не закрыта"));
        assertEquals(1, diagnostics.errorCount());
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.STRING, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    @DisplayName("после открывающих кавычек нельзя писать текст")
    void textAfterOpeningQuotes() {
        String code = "\"\"\"привет\n\"\"\"";
        Diagnostics diagnostics = diagnose(code);
        Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("должен идти перевод строки"));
    }

    @Test
    @DisplayName("смесь пробелов и табуляций в отступе — предупреждение, а не ошибка")
    void mixedIndentWarning() {
        String code = "\"\"\"\n    пробелы\n\tтабуляция\n    \"\"\"";
        Diagnostics diagnostics = diagnose(code);
        Lexer.tokenize(Source.ofString(code), diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(diagnostics.renderAll().contains("смешаны пробелы и табуляции"));
    }

    // --- строка с подстановкой -----------------------------------------------

    @Test
    @DisplayName("строка без подстановки — обычный STRING, а не куски")
    void singleQuotedWithoutHoles() {
        assertEquals(List.of(TokenType.STRING), types("'просто текст'"));
        assertEquals("просто текст", texts("'просто текст'"));
        // Поэтому такую строку можно писать там, где нужен именно литерал.
        assertEquals(List.of(TokenType.IMPORT, TokenType.STRING), types("import 'lib/math'"));
    }

    @Test
    @DisplayName("подстановка режет строку на куски, между ними идут обычные токены")
    void interpolationPieces() {
        String code = "'итого: ${price * count} руб.'";
        assertEquals(List.of(TokenType.STRING_START, TokenType.WORD, TokenType.STAR,
                TokenType.WORD, TokenType.STRING_END), types(code));
        assertEquals("итого: |price|*|count| руб.", texts(code));
    }

    @Test
    @DisplayName("куски строки покрывают запись встык — от кавычки до кавычки")
    void piecesCoverText() {
        String code = "'a${x}b'";
        List<Token> tokens = lex(code);

        assertEquals(0, tokens.get(0).span().start());
        // Разделители входят в интервалы кусков: "a${" и "}b'".
        assertEquals(code.indexOf("x"), tokens.get(0).span().end());
        assertEquals(code.length(), tokens.get(2).span().end());
    }

    @Test
    @DisplayName("объект внутри подстановки не закрывает строку своей скобкой")
    void objectInsideHole() {
        String code = "'${ {a: 1}.a }'";
        assertEquals(List.of(TokenType.STRING_START, TokenType.LBRACE, TokenType.WORD,
                TokenType.COLON, TokenType.INT, TokenType.RBRACE, TokenType.DOT,
                TokenType.WORD, TokenType.STRING_END), types(code));
    }

    @Test
    @DisplayName("строка с подстановкой бывает внутри подстановки")
    void nestedInterpolation() {
        String code = "'сверху ${ 'внутри ${name}' }'";
        assertEquals(List.of(TokenType.STRING_START, TokenType.STRING_START, TokenType.WORD,
                TokenType.STRING_END, TokenType.STRING_END), types(code));
    }

    @Test
    @DisplayName("экранированный доллар подстановки не открывает")
    void escapedDollar() {
        assertEquals(List.of(TokenType.STRING), types("'\\${name}'"));
        assertEquals("${name}", texts("'\\${name}'"));
    }

    @Test
    @DisplayName("незакрытая подстановка обрывается на конце строки")
    void unterminatedHole() {
        String code = "a = 'итого ${price\nb = 2";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("подстановка не закрыта"));
        // Конец строки ставится сам, и разбор идёт дальше по файлу.
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.STRING_START,
                TokenType.WORD, TokenType.STRING_END, TokenType.WORD, TokenType.ASSIGN,
                TokenType.INT, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    @DisplayName("незакрытая строка в одинарных кавычках не съедает файл")
    void unterminatedSingleQuoted() {
        String code = "a = 'забыли\nb = 2";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("строка не закрыта"));
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.STRING,
                TokenType.WORD, TokenType.ASSIGN, TokenType.INT, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    @DisplayName("подстановка в двойных кавычках — предупреждение, а не ошибка")
    void holeInPlainString() {
        String code = "\"итого: ${total}\"";
        Diagnostics diagnostics = diagnose(code);
        Lexer.tokenize(Source.ofString(code), diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(diagnostics.renderAll().contains("подстановка не работает"));
    }

    // --- многострочная строка с подстановкой ---------------------------------

    @Test
    @DisplayName("три одинарные кавычки: куски текста со снятым отступом")
    void multilineInterpolation() {
        String code = "'''\n"
                + "    Привет, ${name}!\n"
                + "    '''";
        List<Token> tokens = lex(code);

        assertEquals(List.of(TokenType.STRING_START, TokenType.WORD, TokenType.STRING_END),
                tokens.stream().map(Token::type).toList());
        assertEquals("Привет, |name|!\n", texts(code));
    }

    @Test
    @DisplayName("подстановка в начале строки не сдвигает текст блока")
    void holeDoesNotSetMargin() {
        String code = "'''\n"
                + "    ${name} — первый\n"
                + "      второй\n"
                + "    '''";

        // Отступ мерится по строкам, которые в блоке начались: у первой строки
        // он свой, а край подстановки к делу не относится.
        assertEquals("|name| — первый\n  второй\n", texts(code));
    }

    @Test
    @DisplayName("пробел перед подстановкой не считается хвостовым")
    void spaceBeforeHoleSurvives() {
        String code = "'''\n"
                + "    итого:   ${sum}\n"
                + "    '''";

        assertEquals("итого:   |sum|\n", texts(code));
    }

    @Test
    @DisplayName("в подстановке многострочной строки перевод строки разрешён")
    void holeMaySpanLines() {
        String code = "'''\n"
                + "    итого: ${\n"
                + "        price * count\n"
                + "    }\n"
                + "    '''";
        Diagnostics diagnostics = diagnose(code);
        Lexer.tokenize(Source.ofString(code), diagnostics);

        assertFalse(diagnostics.hasErrors(),
                () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
    }

    @Test
    @DisplayName("незакрытая многострочная строка доходит до конца файла")
    void unterminatedMultiline() {
        String code = "a = '''\n    забыли\nb = 2";
        Diagnostics diagnostics = diagnose(code);
        List<Token> tokens = Lexer.tokenize(Source.ofString(code), diagnostics);

        assertTrue(diagnostics.renderAll().contains("многострочная строка не закрыта"));
        assertEquals(List.of(TokenType.WORD, TokenType.ASSIGN, TokenType.STRING, TokenType.EOF),
                tokens.stream().map(Token::type).toList());
    }
}
