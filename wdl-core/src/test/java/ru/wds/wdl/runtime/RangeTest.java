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
import ru.wds.wdl.value.types.RangeValue;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Диапазон: значение, принадлежность, перебор. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RangeTest {

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
        run(code, out);
        return out.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static void run(String code, StringBuilder out) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(out::append));
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> eval(code));
    }

    private static WdlRuntimeError runtimeErrorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code, new StringBuilder()));
    }

    @Test
    @DisplayName("диапазон — обычное значение своего типа")
    void rangeIsAValue() {
        assertInstanceOf(RangeValue.class, eval("1..5"));
        assertEquals(ValueType.RANGE, eval("1..5").type());
        assertEquals("1..5", show("1..5"));
        assertEquals("0..9", show("0..n - 1", 10));
    }

    /** Тот же {@link #eval}, но с готовой переменной: границы — обычные выражения. */
    private static String show(String code, long n) {
        return printed("n = " + n + "\nprintln(" + code + ")");
    }

    @Test
    @DisplayName("границы включительны с обеих сторон")
    void boundsAreInclusive() {
        assertEquals("true", show("1 in 1..5"));
        assertEquals("true", show("5 in 1..5"));
        assertEquals("true", show("3 in 1..5"));
        assertEquals("false", show("0 in 1..5"));
        assertEquals("false", show("6 in 1..5"));
    }

    @Test
    @DisplayName("нецелые границы годятся для принадлежности, но не для перебора")
    void fractionalBounds() {
        assertEquals("true", show("1.5 in 0.5..2.5"));
        assertEquals("false", show("3 in 0.5..2.5"));
        assertTrue(runtimeErrorOf("for (x in 0.5..2.5) println(x)").getMessage()
                .contains("перебрать можно диапазон с целыми границами, а здесь 0.5..2.5"));
    }

    @Test
    @DisplayName("5..1 пуст, а не идёт вниз")
    void descendingRangeIsEmpty() {
        assertEquals("true", show("(5..1).empty"));
        assertEquals("false", show("3 in 5..1"));
        assertEquals("", printed("for (i in 5..1) println(i)"));
        // Ради этого правило и заведено: 0..n - 1 при n == 0 — ноль проходов.
        assertEquals("готово", printed("""
                n = 0
                for (i in 0..n - 1) println(i)
                println("готово")
                """));
    }

    @Test
    @DisplayName("перебор идёт шагом в единицу и включает обе границы")
    void forEachOverRange() {
        assertEquals("1 2 3 4 5", printed("for (i in 1..5) println(i)"));
        assertEquals("7", printed("for (i in 7..7) println(i)"));
        assertEquals("-1 0 1", printed("for (i in -1..1) println(i)"));
    }

    @Test
    @DisplayName("break и continue в переборе диапазона работают как в любом цикле")
    void breakAndContinue() {
        assertEquals("1 2", printed("""
                for (i in 1..10) {
                    if (i > 2) break
                    println(i)
                }
                """));
    }

    @Test
    @DisplayName("границы — числа, и только")
    void boundsMustBeNumbers() {
        assertTrue(errorOf("\"a\"..\"z\"").getMessage().contains("границы диапазона — числа"));
        assertTrue(errorOf("null..5").getMessage().contains("границы диапазона — числа"));
        assertTrue(errorOf("\"x\" in 1..5").getMessage()
                .contains("в диапазоне ищется число, а здесь строка"));
    }

    @Test
    @DisplayName("члены диапазона отвечают о его границах")
    void members() {
        assertEquals("1", show("(1..5).from"));
        assertEquals("5", show("(1..5).to"));
        assertEquals("false", show("(1..5).empty"));
        // Метод и оператор берут ответ из одного места.
        assertEquals(show("3 in 1..5"), show("(1..5).contains(3)"));
    }

    @Test
    @DisplayName("дескриптор Range появился в корневой области сам")
    void typeDescriptor() {
        assertEquals("true", show("1..5 is Range"));
        assertEquals("true", show("typeof(1..5) == Range"));
        assertEquals("range", show("typeof(1..5)"));
        assertEquals("false", show("5 is Range"));
        assertEquals("true", show("(1..5).type == Range"));
        assertEquals("range", show("Range.info.name"));
    }

    @Test
    @DisplayName("диапазон равен диапазону с теми же границами")
    void structuralEquality() {
        assertEquals("true", show("1..5 == 1..5"));
        assertEquals("false", show("1..5 == 1..6"));
    }

    // --- диапазон как ключ: срез -----------------------------------------------

    @Test
    @DisplayName("срез массива берёт обе границы включительно, как for и in")
    void arraySlice() {
        assertEquals("[20, 30, 40]", show("[10, 20, 30, 40, 50][1..3]"));
        // Тип результата не зависит от значений границ: иначе 'a[i..j].push(x)'
        // работало бы через раз.
        assertEquals("[10]", show("[10, 20, 30][0..0]"));
        assertEquals("[]", show("[10, 20, 30][2..1]"));
    }

    @Test
    @DisplayName("отрицательная граница среза считается от конца")
    void negativeSliceBounds() {
        assertEquals("[20, 30, 40]", show("[10, 20, 30, 40][1..-1]"));
        assertEquals("[30, 40]", show("[10, 20, 30, 40][-2..-1]"));
        assertEquals("[10, 20, 30, 40]", show("[10, 20, 30, 40][-100..100]"));
    }

    @Test
    @DisplayName("границы среза подрезаются, а не ошибаются")
    void sliceClamps() {
        // Вопрос у среза другой, чем у элемента: не «какой элемент под номером»,
        // а «какая часть попадает в промежуток», — и ответ есть всегда.
        assertEquals("[30, 40]", show("[10, 20, 30, 40][2..100]"));
        assertEquals("[]", show("[][0..10]"));
        assertTrue(errorOf("[10, 20][9]").getMessage().contains("вне границ"));
    }

    @Test
    @DisplayName("срез строки отдаёт строку")
    void stringSlice() {
        assertEquals("wdl", show("\"wdlanguage\"[0..2]"));
        assertEquals("uage", show("\"wdlanguage\"[-4..-1]"));
        assertEquals("", show("\"wdl\"[2..1]"));
    }

    @Test
    @DisplayName("срез — копия, а не вид на исходный массив")
    void sliceIsCopy() {
        assertEquals("10", printed("a = [10, 20, 30]; b = a[0..1]; b[0] = 0; println(a[0])"));
    }

    @Test
    @DisplayName("границы среза обязаны быть целыми")
    void sliceBoundsMustBeIntegers() {
        // Тот же довод, что у 'for (x in 0.5..2.5)': какие позиции содержит такой
        // диапазон «по одной», языку решать не за что.
        String message = errorOf("[10, 20, 30][0.5..2.5]").getMessage();
        assertTrue(message.contains("должны быть целыми числами"), message);
        assertTrue(message.contains("0.5..2.5"), message);
    }

    @Test
    @DisplayName("в срез нельзя записать: это изменило бы длину")
    void sliceIsReadOnly() {
        String message = runtimeErrorOf("a = [10, 20, 30]; a[1..2] = [0]").getMessage();
        assertTrue(message.contains("в срез 1..2 нельзя записать"), message);
        assertTrue(message.contains("вместе с его длиной"), message);
        assertTrue(runtimeErrorOf("s = \"abc\"; s[1..2] = \"x\"")
                .getMessage().contains("по индексу или срезу"));
    }

    @Test
    @DisplayName("у объекта диапазон остаётся ключом, а не срезом")
    void objectKeepsRangeAsKey() {
        // Это не исключение из правила, а само правило: контейнер толкует ключ
        // по-своему, у массива ключ — позиция, у объекта — ключ.
        assertEquals("промежуток", printed("c = {}; c[1..3] = \"промежуток\"; println(c[1..3])"));
    }
}
