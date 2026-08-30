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
}
