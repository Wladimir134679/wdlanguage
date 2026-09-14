package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;
import ru.wds.wdl.value.types.BytesValue;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тип {@code bytes}: значение, обращение, операторы, члены.
 * <p>
 * Проверяется ровно то, чем обосновано решение сделать байты типом ядра, а не классом
 * из библиотеки: на каждый вопрос, который тип обязан иметь, у них есть ответ, и ни
 * одного нового правила для этого не понадобилось. Байты здесь берутся из
 * {@code "…".bytes} — единственного входа, который работает без импорта.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class BytesTest {

    private static Value eval(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Expr expr = Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        return new Interpreter().eval(expr, ExecutionContext.fresh());
    }

    private static String show(String code) {
        return eval(code).display();
    }

    private static String printed(String code) {
        StringBuilder out = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(out::append));
        return out.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> eval(code));
    }

    // --- значение -------------------------------------------------------------

    @Test
    @DisplayName("typeof отвечает про байты честно: это свой тип, а не объект")
    void isOwnType() {
        Value data = eval("\"PNG\".bytes");
        assertInstanceOf(BytesValue.class, data);
        assertEquals(ValueType.BYTES, data.type());
        assertEquals("bytes", show("typeof(\"PNG\".bytes)"));
        assertEquals("true", show("\"PNG\".bytes is Bytes"));
    }

    @Test
    @DisplayName("Печать даёт длину и превью, а не содержимое")
    void printsPreview() {
        assertEquals("bytes(3: 50 4e 47)", show("\"PNG\".bytes"));
        assertEquals("bytes(0)", show("\"\".bytes"));
        // Девять байтов — восемь и многоточие: println мегабайта не должен
        // выводить мегабайт.
        assertEquals("bytes(9: 61 61 61 61 61 61 61 61 …)", show("\"aaaaaaaaa\".bytes"));
    }

    @Test
    @DisplayName("Равенство по содержимому: то, ради чего bytes неизменяем")
    void equalsByContent() {
        assertEquals("true", show("\"PNG\".bytes == \"PNG\".bytes"));
        assertEquals("false", show("\"PNG\".bytes == \"png\".bytes"));
        // И как ключ объекта — тем же содержимым.
        assertEquals("есть", printed("m = {}\nm[\"ab\".bytes] = \"есть\"\nprintln(m[\"ab\".bytes])"));
        assertNotEquals(BytesValue.of(new byte[]{1}), BytesValue.of(new byte[]{2}));
    }

    @Test
    @DisplayName("Копия при создании: массив, отданный фабрике, потом не влияет")
    void copiesOnCreate() {
        byte[] source = {1, 2, 3};
        BytesValue data = BytesValue.of(source);
        source[0] = 9;
        assertEquals("010203", data.hex());
    }

    // --- обращение ------------------------------------------------------------

    @Test
    @DisplayName("Байт наружу выходит знаковым, беззнаковый просят по имени")
    void signedOut() {
        // 0x89 — первый байт подписи PNG. Знаковым это -119, и в этом вся цена
        // решения: сравнивать с документацией формата надо через uint8.
        assertEquals("-119", show("\"\\u0089\".encode(\"iso-8859-1\")[0]"));
        assertEquals("137", show("\"\\u0089\".encode(\"iso-8859-1\").uint8(0)"));
    }

    @Test
    @DisplayName("Индекс, отрицательный индекс и срез — те же правила, что у массива")
    void indexingIsShared() {
        assertEquals("80", show("\"PNG\".bytes[0]"));
        assertEquals("71", show("\"PNG\".bytes[-1]"));
        assertEquals("bytes(2: 50 4e)", show("\"PNG\".bytes[0..1]"));
        // Срез подрезается, адресация строга — ровно как у массива и строки.
        assertEquals("bytes(3: 50 4e 47)", show("\"PNG\".bytes[0..100]"));
        assertTrue(errorOf("\"PNG\".bytes[3]").getMessage()
                .contains("индекс 3 вне границ байтов размером 3"));
    }

    @Test
    @DisplayName("Срез — копия, а не окно: длина исходного значения на него не влияет")
    void sliceIsCopy() {
        assertEquals("bytes(1: 4e)", show("\"PNGPNGPNG\".bytes[1..1]"));
    }

    @Test
    @DisplayName("Записать по индексу нельзя, и сообщение говорит, чем собирать")
    void immutable() {
        WdlRuntimeError refused = assertThrows(WdlRuntimeError.class,
                () -> printed("d = \"PNG\".bytes\nd[0] = 1"));
        assertTrue(refused.getMessage().contains("bytes неизменяем"), refused.getMessage());
        assertTrue(refused.getMessage().contains("bin.writer()"), refused.getMessage());
    }

    @Test
    @DisplayName("Обход даёт байты числами, с ключом — номер")
    void iterates() {
        assertEquals("80 78 71", printed("for (b in \"PNG\".bytes) { print(b, \" \") }"));
        assertEquals("0=80 1=78 2=71", printed(
                "for (i, b in \"PNG\".bytes) { print(i, \"=\", b, \" \") }"));
    }

    // --- операторы ------------------------------------------------------------

    @Test
    @DisplayName("Склейка '+' работает с байтами и только с ними")
    void concatenates() {
        assertEquals("bytes(4: 50 4e 47 21)", show("\"PNG\".bytes + \"!\".bytes"));
        // Строка съедает '+' насовсем — общее правило языка, и байты его не меняют:
        // справа от строки получается текст, и это её превью.
        assertEquals("итого: bytes(3: 50 4e 47)", show("\"итого: \" + \"PNG\".bytes"));
        assertTrue(errorOf("\"PNG\".bytes + 1").getMessage()
                .contains("операция '+' не применима к типам байты и число"));
    }

    @Test
    @DisplayName("'in' ищет и байт в любой записи, и целую последовательность")
    void membership() {
        assertEquals("true", show("80 in \"PNG\".bytes"));
        assertEquals("true", show("\"NG\".bytes in \"PNG\".bytes"));
        assertEquals("false", show("1 in \"PNG\".bytes"));
        // 200 и -56 — две записи одного байта, и искать можно любой.
        assertEquals("true", show("200 in \"\\u00c8\".encode(\"iso-8859-1\")"));
        assertEquals("true", show("-56 in \"\\u00c8\".encode(\"iso-8859-1\")"));
        assertTrue(errorOf("256 in \"PNG\".bytes").getMessage()
                .contains("в байтах ищется байт (-128..255) или bytes"));
    }

    // --- члены ----------------------------------------------------------------

    @Test
    @DisplayName("Свойства: size, empty, first, last, hex, base64, numbers")
    void properties() {
        assertEquals("3", show("\"PNG\".bytes.size"));
        assertEquals("false", show("\"PNG\".bytes.empty"));
        assertEquals("true", show("\"\".bytes.empty"));
        assertEquals("80", show("\"PNG\".bytes.first"));
        assertEquals("71", show("\"PNG\".bytes.last"));
        assertEquals("null", show("\"\".bytes.first"));
        assertEquals("504e47", show("\"PNG\".bytes.hex"));
        assertEquals("UE5H", show("\"PNG\".bytes.base64"));
        assertEquals("[80, 78, 71]", show("\"PNG\".bytes.numbers"));
    }

    @Test
    @DisplayName("Подпись сравнивают целиком: startsWith и endsWith")
    void prefixes() {
        assertEquals("true", show("\"PNG\".bytes.startsWith(\"PN\".bytes)"));
        assertEquals("false", show("\"PNG\".bytes.startsWith(\"NG\".bytes)"));
        assertEquals("true", show("\"PNG\".bytes.endsWith(\"NG\".bytes)"));
        // Начало длиннее самих байтов — ложь, а не вылет за границу.
        assertEquals("false", show("\"PN\".bytes.startsWith(\"PNG\".bytes)"));
    }

    @Test
    @DisplayName("indexOf ищет и байт, и последовательность; slice подрезается")
    void searching() {
        assertEquals("1", show("\"PNG\".bytes.indexOf(78)"));
        assertEquals("-1", show("\"PNG\".bytes.indexOf(1)"));
        assertEquals("1", show("\"PNG\".bytes.indexOf(\"NG\".bytes)"));
        assertEquals("bytes(2: 4e 47)", show("\"PNG\".bytes.slice(1)"));
        assertEquals("bytes(1: 4e)", show("\"PNG\".bytes.slice(1, 2)"));
        assertEquals("bytes(0)", show("\"PNG\".bytes.slice(9, 100)"));
    }

    @Test
    @DisplayName("Числа читаются по девяти видам, порядок байтов — аргументом")
    void numbers() {
        // 00 00 01 00: 256 в big-endian и 65536 в little-endian.
        String four = "\"\\u0000\\u0000\\u0001\\u0000\".encode(\"iso-8859-1\")";
        assertEquals("256", show(four + ".int32(0)"));
        assertEquals("65536", show(four + ".int32(0, \"le\")"));
        assertEquals("1", show(four + ".uint8(2)"));
        assertEquals("0", show(four + ".int16(0)"));
        // float64 туда-обратно: словарь видов один и у чтения, и у записи.
        assertEquals("1.0", show(
                "\"\\u003f\\u00f0\\u0000\\u0000\\u0000\\u0000\\u0000\\u0000\""
                        + ".encode(\"iso-8859-1\").float64(0)"));
    }

    @Test
    @DisplayName("Чтение числа не помещается в остаток — ошибка адресации с числами")
    void numberOutOfBounds() {
        assertTrue(errorOf("\"PNG\".bytes.int32(0)").getMessage()
                .contains("int32 занимает 4 байт, а от позиции 0 до конца их 3"));
    }

    @Test
    @DisplayName("Неизвестный вид числа и неизвестный порядок называют себя списком")
    void vocabularyIsClosed() {
        assertTrue(errorOf("\"PNG\".bytes.int16(0, \"middle\")").getMessage()
                .contains("порядок байтов — это \"be\""));
        // Синонимов у вида числа нет: 'i32' — это промах по имени члена.
        assertTrue(errorOf("\"PNG\".bytes.i32(0)").getMessage().contains("i32"));
    }

    // --- текст и байты --------------------------------------------------------

    @Test
    @DisplayName("Текст в байты и обратно: UTF-8 по умолчанию, кодировка — по имени")
    void textRoundTrip() {
        assertEquals("21", show("\"привет, мир!\".bytes.size"));
        assertEquals("привет", show("\"привет\".bytes.text()"));
        assertEquals("6", show("\"привет\".encode(\"cp1251\").size"));
        assertEquals("привет", show("\"привет\".encode(\"cp1251\").text(\"cp1251\")"));
    }

    @Test
    @DisplayName("Невалидная последовательность — ошибка, а не подмена вопросиками")
    void brokenTextIsRefused() {
        // 0xC8 в UTF-8 начинает двухбайтовую последовательность, а продолжения нет.
        WdlRuntimeError broken = errorOf("\"\\u00c8\".encode(\"iso-8859-1\").text()");
        assertTrue(broken.getMessage().contains("не декодируется"), broken.getMessage());
        // Терпимость просят вторым аргументом, и тогда это осознанное решение.
        assertEquals("\uFFFD", show("\"\\u00c8\".encode(\"iso-8859-1\").text(\"utf-8\", true)"));
    }

    @Test
    @DisplayName("Неизвестная кодировка называется ошибкой, а не подменяется UTF-8")
    void unknownCharset() {
        assertTrue(errorOf("\"а\".encode(\"koi-нет\")").getMessage()
                .contains("неизвестная кодировка"));
    }
}
