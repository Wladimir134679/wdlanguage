package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Expr;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.ArrayValue;
import ru.wds.wdl.value.FloatValue;
import ru.wds.wdl.value.IntValue;
import ru.wds.wdl.value.StringValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpreterTest {

    private static Value eval(String code) {
        return eval(code, ExecutionContext.fresh());
    }

    private static Value eval(String code, ExecutionContext context) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Expr expr = Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        return new Interpreter().eval(expr, context);
    }

    /** Пользовательское представление результата — то, что напечатает CLI. */
    private static String show(String code) {
        return eval(code).display();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> eval(code));
    }

    // --- числа ---------------------------------------------------------------

    @Test
    @DisplayName("целая арифметика остаётся целой")
    void integerArithmetic() {
        assertEquals("4", show("2 + 2"));
        assertEquals("-1", show("2 - 3"));
        assertEquals("42", show("6 * 7"));
        assertEquals("1", show("7 % 2"));
        assertInstanceOf(IntValue.class, eval("2 + 2"));
    }

    @Test
    @DisplayName("один вещественный операнд переводит весь пример в вещественные")
    void floatContagion() {
        assertEquals("6.0", show("2 * 3.0"));
        assertInstanceOf(FloatValue.class, eval("2 * 3.0"));
        assertEquals("0.5", show("1 - 0.5"));
    }

    @Test
    @DisplayName("деление целых точное — целое, неточное — вещественное")
    void division() {
        assertEquals("2", show("6 / 3"));
        assertInstanceOf(IntValue.class, eval("6 / 3"));
        assertEquals("3.5", show("7 / 2"));
        assertInstanceOf(FloatValue.class, eval("7 / 2"));
    }

    @Test
    @DisplayName("деление на ноль — ошибка, а не бесконечность")
    void divisionByZero() {
        assertTrue(errorOf("1 / 0").getMessage().contains("деление на ноль"));
        assertTrue(errorOf("1.0 / 0").getMessage().contains("деление на ноль"));
        assertTrue(errorOf("1 % 0").getMessage().contains("остаток от деления на ноль"));
    }

    @Test
    @DisplayName("переполнение 64 бит переводит результат в вещественный, а не заворачивает разряды")
    void overflowPromotesToFloat() {
        Value value = eval("9223372036854775807 + 1");
        assertInstanceOf(FloatValue.class, value);
        assertTrue(((FloatValue) value).value() > 9.2e18);
    }

    @Test
    @DisplayName("приоритеты действуют и на результат, а не только на форму дерева")
    void precedenceAffectsResult() {
        assertEquals("7", show("1 + 2 * 3"));
        assertEquals("9", show("(1 + 2) * 3"));
        assertEquals("5", show("10 - 3 - 2"));
    }

    // --- строки --------------------------------------------------------------

    @Test
    @DisplayName("сложение со строкой — конкатенация с любой стороны")
    void stringConcatenation() {
        assertEquals("итого: 5", show("\"итого: \" + 5"));
        assertEquals("5 штук", show("5 + \" штук\""));
        assertEquals("2.5!", show("2.5 + \"!\""));
        assertEquals("true?", show("true + \"?\""));
    }

    @Test
    @DisplayName("строки сравниваются по кодовым точкам")
    void stringComparison() {
        assertEquals("true", show("\"абв\" < \"абг\""));
        assertEquals("true", show("\"я\" > \"а\""));
    }

    @Test
    @DisplayName("к строке можно обратиться по номеру символа")
    void stringIndexing() {
        assertEquals("п", show("\"привет\"[0]"));
        assertEquals("т", show("\"привет\"[5]"));
        assertTrue(errorOf("\"привет\"[6]").getMessage().contains("вне границ строки"));
    }

    // --- сравнения и логика --------------------------------------------------

    @Test
    @DisplayName("равенство сравнивает числа по величине, но не приводит типы")
    void equality() {
        assertEquals("true", show("1 == 1.0"));
        assertEquals("false", show("1 == \"1\""));
        assertEquals("true", show("null == null"));
        assertEquals("true", show("1 != true"));
    }

    @Test
    @DisplayName("массивы и объекты сравниваются по ссылке")
    void referenceEquality() {
        assertEquals("false", show("[1, 2] == [1, 2]"));
        assertEquals("false", show("{a: 1} == {a: 1}"));
    }

    @Test
    @DisplayName("ложны только null и false")
    void truthiness() {
        assertEquals("true", show("!false"));
        assertEquals("true", show("!null"));
        assertEquals("false", show("!0"));
        assertEquals("false", show("!\"\""));
    }

    @Test
    @DisplayName("&& и || ленивы и возвращают операнд, а не приведённое логическое")
    void shortCircuit() {
        // Правая часть не вычисляется: неопределённая переменная не приводит к ошибке.
        assertEquals("false", show("false && неизвестная"));
        assertEquals("true", show("true || неизвестная"));
        assertEquals("по умолчанию", show("null || \"по умолчанию\""));
        assertEquals("5", show("true && 5"));
    }

    @Test
    @DisplayName("условное выражение вычисляет только выбранную ветку")
    void ternary() {
        assertEquals("больше", show("5 > 3 ? \"больше\" : \"меньше\""));
        assertEquals("1", show("true ? 1 : неизвестная"));
    }

    // --- биты ----------------------------------------------------------------

    @Test
    @DisplayName("побитовые операции и сдвиги над целыми")
    void bitwise() {
        assertEquals("255", show("0xF0 | 0x0F"));
        assertEquals("16", show("0xFF & 0x10"));
        assertEquals("1024", show("1 << 10"));
        assertEquals("-1", show("~0"));
        assertEquals("-4", show("-16 >> 2"));
        assertEquals("4611686018427387900", show("-16 >>> 2"));
    }

    @Test
    @DisplayName("побитовая операция над вещественным — ошибка, а не тихое округление")
    void bitwiseRejectsFloat() {
        assertTrue(errorOf("1.5 & 1").getMessage().contains("целыми числами"));
        assertTrue(errorOf("~2.5").getMessage().contains("целым числам"));
    }

    // --- обращение: одно для массива, объекта и строки -----------------------

    @Test
    @DisplayName("массив читается по индексу")
    void arrayAccess() {
        assertEquals("2", show("[1, 2, 3][1]"));
        assertEquals("6", show("[1, [5, 6]][1][1]"));
    }

    @Test
    @DisplayName("выход за границы массива — ошибка с размером в сообщении")
    void arrayOutOfBounds() {
        assertTrue(errorOf("[1, 2][5]").getMessage().contains("вне границ массива размером 2"));
        assertTrue(errorOf("[1, 2][-1]").getMessage().contains("вне границ"));
        assertTrue(errorOf("[1, 2][\"a\"]").getMessage().contains("целым числом"));
    }

    @Test
    @DisplayName("точка и скобки читают объект одинаково")
    void objectAccessIsOneMechanism() {
        assertEquals("1", show("{a: 1}.a"));
        assertEquals("1", show("{a: 1}[\"a\"]"));
        assertEquals(show("{\"имя\": \"Точка\"}.имя"), show("{\"имя\": \"Точка\"}[\"имя\"]"));
    }

    @Test
    @DisplayName("отсутствующий ключ объекта даёт null, а не ошибку")
    void missingKeyIsNull() {
        assertEquals("null", show("{a: 1}.b"));
        assertEquals("значение по умолчанию", show("{a: 1}.b || \"значение по умолчанию\""));
    }

    @Test
    @DisplayName("ключ объекта нормализуется: 1 и 1.0 — один ключ")
    void objectKeyNormalization() {
        assertEquals("да", show("{1: \"да\"}[1.0]"));
        assertEquals("да", show("{1.0: \"да\"}[1]"));
    }

    @Test
    @DisplayName("ключ можно вычислить")
    void computedKey() {
        assertEquals("зелёный", show("{\"цветФона\": \"зелёный\"}[\"цвет\" + \"Фона\"]"));
    }

    @Test
    @DisplayName("обращение к значению, у которого нет содержимого, объясняет форму записи")
    void accessOnScalar() {
        assertTrue(errorOf("5 .x").getMessage().contains("через точку"));
        assertTrue(errorOf("true[0]").getMessage().contains("по индексу"));
        assertTrue(errorOf("null.поле").getMessage().contains("null"));
    }

    // --- коллекции как значения ----------------------------------------------

    @Test
    @DisplayName("литерал массива строит новое значение при каждом вычислении")
    void arrayLiteral() {
        Value value = eval("[1, \"два\", [3]]");
        ArrayValue array = assertInstanceOf(ArrayValue.class, value);
        assertEquals(3, array.size());
        assertEquals(ValueType.ARRAY, array.type());
        assertEquals("[1, \"два\", [3]]", array.display());
    }

    @Test
    @DisplayName("массивы складываются в новый массив")
    void arrayConcatenation() {
        assertEquals("[1, 2, 3]", show("[1, 2] + [3]"));
    }

    @Test
    @DisplayName("объект сохраняет порядок записи")
    void objectPreservesOrder() {
        assertEquals("{\"б\": 1, \"а\": 2}", show("{б: 1, а: 2}"));
    }

    // --- окружение и ошибки --------------------------------------------------

    @Test
    @DisplayName("имя берётся из окружения")
    void variablesComeFromEnvironment() {
        Scope scope = Scope.root();
        scope.define("ставка", IntValue.of(250));
        scope.define("название", StringValue.of("wdl"));
        ExecutionContext context = ExecutionContext.of(scope);

        assertEquals("500", eval("ставка * 2", context).display());
        assertEquals("wdl!", eval("название + \"!\"", context).display());
    }

    @Test
    @DisplayName("вложенная область перекрывает имя, не портя внешнюю")
    void nestedScope() {
        Scope root = Scope.root();
        root.define("x", IntValue.of(1));
        ExecutionContext nested = ExecutionContext.of(root).nested();
        nested.scope().define("x", IntValue.of(2));

        assertEquals("2", eval("x", nested).display());
        assertEquals("1", eval("x", ExecutionContext.of(root)).display());
    }

    @Test
    @DisplayName("неизвестное имя — ошибка с местом в скрипте")
    void unknownVariable() {
        WdlRuntimeError error = errorOf("1 + неизвестная");
        assertTrue(error.getMessage().contains("не определена"));
        assertFalse(error.span().isNone(), "ошибка обязана знать место в исходнике");
    }

    @Test
    @DisplayName("несовместимые типы операции называются по-русски")
    void typeErrors() {
        assertTrue(errorOf("true + 1").getMessage().contains("логическое"));
        assertTrue(errorOf("null - 1").getMessage().contains("не применима"));
        assertTrue(errorOf("[1] < [2]").getMessage().contains("массив"));
    }

    @Test
    @DisplayName("один интерпретатор обслуживает независимые окружения")
    void interpreterIsStateless() {
        Interpreter interpreter = new Interpreter();
        Scope first = Scope.root();
        first.define("x", IntValue.of(1));
        Scope second = Scope.root();
        second.define("x", IntValue.of(100));

        Source source = Source.ofString("x + 1");
        Diagnostics diagnostics = new Diagnostics(source);
        Expr shared = Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics);

        assertEquals("2", interpreter.eval(shared, ExecutionContext.of(first)).display());
        assertEquals("101", interpreter.eval(shared, ExecutionContext.of(second)).display());
        // Одно и то же дерево, разные окружения — и никакого состояния между запусками.
        assertSame(shared, shared);
    }
}
