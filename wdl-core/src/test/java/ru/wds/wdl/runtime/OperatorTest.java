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
 * Перегрузка операторов при выполнении: оператор у левого операнда и зеркальный
 * у правого.
 * <p>
 * Главное, что здесь проверяется, — не то, что перегрузка работает, а то, что она
 * <b>не вмешивается</b>: {@code 24 * 23} по-прежнему считает ядро, {@code 6 / 0}
 * по-прежнему говорит «деление на ноль», а строка по-прежнему съедает {@code +}.
 * Перегрузка живёт ровно там, где ядро раньше выбрасывало ошибку типов.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class OperatorTest {

    /** Класс из замысла: деление во все четыре стороны. */
    private static final String POINT = """
            class Point(x, y) {
                def `+`(right) {
                    if (right is Number) return new Point(x + right, y + right);
                    return new Point(x + right.x, y + right.y);
                }

                def `/`(right) {
                    if (right is Number) return new Point(x / right, y / right);
                    return new Point(x / right.x, y / right.y);
                }

                mirror def `/`(left) {
                    if (left is Number) return new Point(left / x, left / y);
                    return new Point(left.x / x, left.y / y);
                }

                def show() => "(" + x + ", " + y + ")"
            }
            """;

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(printed::append));
        return printed.toString().strip();
    }

    private static String withPoint(String code) {
        return run(POINT + code);
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    // --- оператор у левого операнда ------------------------------------------

    @Test
    @DisplayName("оператор класса считает то, чего не умеет ядро")
    void ownOperator() {
        assertEquals("(3, 3)", withPoint("""
                print((new Point(6, 6) / new Point(2, 2)).show())
                """));
        assertEquals("(1, 1)", withPoint("print((new Point(6, 6) / 6).show())"));
        assertEquals("(8, 8)", withPoint("""
                print((new Point(6, 6) + new Point(2, 2)).show())
                """));
    }

    @Test
    @DisplayName("составное присваивание берёт тот же член: отдельного не бывает")
    void compoundAssignment() {
        assertEquals("(6, 6)", withPoint("""
                acc = new Point(0, 0)
                acc += new Point(6, 6)
                print(acc.show())
                """));
    }

    @Test
    @DisplayName("оператор достаётся от трейта и от родителя — таблица методов плоская")
    void inheritedOperator() {
        assertEquals("11", run("""
                trait Addable {
                    def `+`(right) => size + right
                }
                class Box(size) with Addable {}
                print(new Box(10) + 1)
                """));
    }

    // --- зеркало -------------------------------------------------------------

    @Test
    @DisplayName("получатель справа: 12 / point считает зеркальный оператор")
    void mirrorOperator() {
        assertEquals("(2, 2)", withPoint("print((12 / new Point(6, 6)).show())"));
    }

    @Test
    @DisplayName("'extend Number' даёт зеркало числу, но арифметику чисел не трогает")
    void extendNumber() {
        assertEquals("(23, 46) 552", run("""
                class Point(x, y) {
                    def show() => "(" + x + ", " + y + ")"
                }
                extend Point {
                    mirror def `*`(left) => new Point(left * this.x, left * this.y)
                }
                print((23 * new Point(1, 2)).show())
                print(" ")
                print(24 * 23)
                """));
    }

    @Test
    @DisplayName("оператор, добавленный расширением, работает как объявленный в классе")
    void extendOperator() {
        assertEquals("(2, 4)", run("""
                class Point(x, y) {
                    def show() => "(" + x + ", " + y + ")"
                }
                extend Point {
                    def `*`(right) => new Point(this.x * right, this.y * right)
                }
                print((new Point(1, 2) * 2).show())
                """));
    }

    // --- ядро важнее ---------------------------------------------------------

    @Test
    @DisplayName("ядро считает само: ни одного поиска члена на арифметике чисел")
    void coreWins() {
        assertEquals("552", run("print(24 * 23)"));
        assertEquals("3", run("print(1 + 2)"));
    }

    @Test
    @DisplayName("строка съедает '+': член класса её никогда не увидит")
    void stringEatsPlus() {
        assertEquals("сумма: (6, 6)", withPoint("""
                p = new Point(6, 6)
                print("сумма: " + p.show())
                """));
        // И слева от строки тоже: конкатенация — это ядро, оно справилось.
        assertTrue(withPoint("""
                print("итого: " + new Point(1, 2))
                """).startsWith("итого: "));
    }

    @Test
    @DisplayName("деление на ноль остаётся делением на ноль, а не «нет оператора»")
    void honestErrorsStay() {
        assertTrue(errorOf("print(6 / 0)").getMessage().contains("деление на ноль"));
        assertTrue(errorOf("print(6 % 0)").getMessage().contains("остаток от деления на ноль"));
        assertTrue(errorOf("print(1.5 & 2)").getMessage()
                .contains("работает только с целыми числами"));
        assertTrue(errorOf("print(~1.5)").getMessage().contains("только к целым числам"));
    }

    @Test
    @DisplayName("без экземпляров сообщение об ошибке типов не изменилось ни на символ")
    void oldMessagesStay() {
        assertEquals("операция '+' не применима к типам null и число",
                errorOf("print(null + 1)").getMessage());
        assertEquals("операция '-' не применима к типам строка и число",
                errorOf("print(\"a\" - 1)").getMessage());
        assertTrue(errorOf("print(1 in 2)").getMessage()
                .contains("искать можно в массиве, строке, объекте или диапазоне"));
    }

    // --- когда оператора нет -------------------------------------------------

    @Test
    @DisplayName("нет оператора у левого — сказано, у кого и для чего")
    void missingOperator() {
        assertEquals("у Point нет оператора '-': справа строка (\"текст\")",
                errorOf(POINT + "print(new Point(1, 2) - \"текст\")").getMessage());
        // Оператор нашёлся и упал сам — это ошибка его тела, а не «оператора нет».
        assertTrue(errorOf(POINT + "print(new Point(1, 2) / \"текст\")").getMessage()
                .contains("нет члена 'x'"));
    }

    @Test
    @DisplayName("нет зеркального — типы названы в том порядке, как написано")
    void missingMirror() {
        assertEquals("слева строка, справа Point: у Point нет зеркального оператора '-'",
                errorOf(POINT + "print(\"a\" - new Point(1, 2))").getMessage());
    }

    @Test
    @DisplayName("оператор, зовущий сам себя, упирается в глубину вызовов, а не в стек Java")
    void recursion() {
        FatalError error = assertThrows(FatalError.class, () -> run("""
                class Loop(x) {
                    def `+`(right) => this + right
                }
                print(new Loop(1) + new Loop(2))
                """));

        assertTrue(error.getMessage().contains("слишком глубокая рекурсия"), error.getMessage());
    }

    // --- ничего не сломалось -------------------------------------------------

    @Test
    @DisplayName("образец в match идёт тем же путём, что оператор в 'if'")
    void matchStillWorks() {
        assertEquals("точка", withPoint("""
                p = new Point(1, 2)
                print(match (p) {
                    case is Point => "точка"
                    else => "что-то другое"
                })
                """));
    }
}
