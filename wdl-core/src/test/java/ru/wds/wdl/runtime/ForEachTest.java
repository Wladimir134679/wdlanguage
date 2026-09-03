package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перебор с ключом: {@code for (i, tile in row)}, {@code for (k, v in config)}.
 * <p>
 * Правило, которое здесь проверяется, одно: <b>первое имя — то, чем обращаются</b>.
 * Отсюда массив и строка дают номер, объект и экземпляр — ключ, а диапазон не даёт
 * ничего. Распаковку элемента этот список имён не делает и делать не должен —
 * она пишется отдельной строкой в теле.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ForEachTest {

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(printed::append));
        return printed.toString();
    }

    private static String printed(String code) {
        return run(code).replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    private static String errorsOf(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- второе имя по каждому источнику -------------------------------------

    @Test
    @DisplayName("массив с двумя именами даёт индекс и элемент")
    void arrayGivesIndexAndItem() {
        assertEquals("0:a 1:b 2:c",
                printed("for (i, tile in [\"a\", \"b\", \"c\"]) println(i, \":\", tile)"));
    }

    @Test
    @DisplayName("объект с двумя именами даёт ключ и значение")
    void objectGivesKeyAndValue() {
        assertEquals("a=1 b=2", printed("for (k, v in {a: 1, b: 2}) println(k, \"=\", v)"));
    }

    @Test
    @DisplayName("экземпляр даёт имя поля и значение")
    void instanceGivesFieldAndValue() {
        assertEquals("x=1 y=2", printed("""
                class Point(x, y) { }
                for (name, value in new Point(1, 2)) println(name, "=", value)
                """));
    }

    @Test
    @DisplayName("строка с двумя именами даёт индекс и символ")
    void stringGivesIndexAndChar() {
        assertEquals("0h 1i", printed("for (i, ch in \"hi\") println(i, ch)"));
    }

    @Test
    @DisplayName("пропуск годится в любой позиции и имени не заводит")
    void holeInAnyPosition() {
        assertEquals("1 2", printed("for (_, v in {a: 1, b: 2}) println(v)"));
        assertEquals("0 1", printed("for (i, _ in [\"a\", \"b\"]) println(i)"));
        assertEquals("", printed("for (_, _ in [1, 2]) { }"));
    }

    @Test
    @DisplayName("у диапазона ключа нет: номер прохода здесь и есть значение")
    void rangeHasNoKey() {
        assertEquals("у диапазона нет ключа: номер прохода здесь и есть значение —"
                        + " оставьте одно имя, 'n'",
                errorOf("for (i, n in 0..10) println(i)").getMessage());
    }

    @Test
    @DisplayName("больше двух имён — ошибка разбора, и место у неё на лишнем имени")
    void threeNamesAreRejected() {
        assertTrue(errorsOf("for (a, b, c in items) println(a)")
                .contains("в 'for' бывает одно имя (значение) или два (ключ и значение)"));
    }

    // --- старая форма не сломана ---------------------------------------------

    @Test
    @DisplayName("одно имя ведёт себя ровно как раньше, включая «объект даёт ключи»")
    void singleNameIsUnchanged() {
        assertEquals("a b c", printed("for (item in [\"a\", \"b\", \"c\"]) println(item)"));
        assertEquals("a b", printed("for (k in {a: 1, b: 2}) println(k)"));
        assertEquals("h i", printed("for (ch in \"hi\") println(ch)"));
        assertEquals("0 1 2", printed("for (i in 0..2) println(i)"));
        assertEquals("x y", printed("""
                class Point(x, y) { }
                for (field in new Point(1, 2)) println(field)
                """));
    }

    @Test
    @DisplayName("перебирать по-прежнему можно только массив, строку, объект и диапазон")
    void otherSourcesAreRejected() {
        assertTrue(errorOf("for (i, x in 5) println(x)").getMessage().contains("перебрать можно"));
    }

    // --- области видимости ---------------------------------------------------

    @Test
    @DisplayName("обе переменные — свои на каждый проход: замыкание видит своё значение")
    void eachIterationHasItsOwnVariables() {
        assertEquals("0a 1b", printed("""
                saved = []
                for (i, tile in ["a", "b"]) saved.push(def() => i + tile)
                for (f in saved) println(f())
                """));
    }

    @Test
    @DisplayName("после цикла ни одно из имён снаружи не существует")
    void namesDoNotLeak() {
        assertTrue(errorOf("for (i, v in [1]) println(v)\nprintln(i)")
                .getMessage().contains("не определена"));
    }

    // --- распаковка элемента остаётся распаковкой ----------------------------

    @Test
    @DisplayName("разложить элемент — отдельная строка в теле, а не второе имя в заголовке")
    void unpackingAnItemIsASeparateStatement() {
        assertEquals("1:2 3:4", printed("""
                points = [[1, 2], [3, 4]]
                for (p in points) {
                    x, y = *p
                    println(x, ":", y)
                }
                """));
    }
}
