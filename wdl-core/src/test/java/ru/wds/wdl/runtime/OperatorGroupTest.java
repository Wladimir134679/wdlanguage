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
 * Группы операторов и унарные: {@code `==`} на два оператора, {@code `<=>`} на четыре
 * сравнения и сортировку, {@code `in`} на четыре записи принадлежности.
 * <p>
 * Главное здесь — <b>одинаковость ответов</b>. Член один, поэтому {@code a.sort()}
 * не может разойтись с {@code a[0] < a[1]}, {@code a.contains(x)} — с {@code x in a},
 * а {@code a == b} — с {@code a != b}. Проверяется именно это, а не то, что каждая
 * запись по отдельности работает.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class OperatorGroupTest {

    /** Класс с полной группой сравнений: одно '==' и одно '<=>' на всё. */
    private static final String LENGTH = """
            class Len(size) {
                def `==`(right) => right is Len && size == right.size
                def `<=>`(right) => size - right.size
            }
            """;

    /** Контейнер из документа: один член на четыре записи оператора. */
    private static final String DECK = """
            class Deck(cards) {
                def `in`(card) => card in cards
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

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    // --- равенство -----------------------------------------------------------

    @Test
    @DisplayName("один член '==' закрывает и '==', и '!='")
    void equality() {
        assertEquals("true false", run(LENGTH + """
                print(new Len(3) == new Len(3))
                print(" ")
                print(new Len(3) != new Len(3))
                """));
    }

    @Test
    @DisplayName("без члена '==' экземпляры по-прежнему сравниваются по ссылке")
    void equalityWithoutMember() {
        assertEquals("false true", run("""
                class Plain(x) {}
                a = new Plain(1)
                print(a == new Plain(1))
                print(" ")
                print(a == a)
                """));
    }

    @Test
    @DisplayName("о равенстве чисел, строк, логических и null ядро отвечает само")
    void equalityOfPlainValues() {
        assertEquals("false", run("print(1 == \"1\")"));
        assertEquals("true", run("print(1 == 1.0)"));
        assertEquals("true", run("print(null == null)"));
        assertEquals("false", run("print(true == false)"));
    }

    @Test
    @DisplayName("зеркальное '==' отвечает, когда экземпляр оказался справа")
    void mirrorEquality() {
        assertEquals("true", run("""
                class Len(size) {
                    mirror def `==`(left) => left == size
                }
                print(3 == new Len(3))
                """));
    }

    // --- сравнение и сортировка ----------------------------------------------

    @Test
    @DisplayName("один член '<=>' закрывает все четыре сравнения")
    void comparison() {
        assertEquals("true true false false", run(LENGTH + """
                a = new Len(1)
                b = new Len(2)
                print(a < b)
                print(" ")
                print(a <= b)
                print(" ")
                print(a > b)
                print(" ")
                print(a >= b)
                """));
    }

    @Test
    @DisplayName("сортировка ходит в тот же '<=>', что и оператор")
    void sortUsesOrder() {
        assertEquals("1 2 3", run(LENGTH + """
                items = [new Len(3), new Len(1), new Len(2)]
                items.sort()
                print(items[0].size + " " + items[1].size + " " + items[2].size)
                """));
        assertEquals("1 2 3", run(LENGTH + """
                items = [new Len(3), new Len(1), new Len(2)].sorted
                print(items[0].size + " " + items[1].size + " " + items[2].size)
                """));
    }

    @Test
    @DisplayName("'contains' и 'indexOf' у массива сравнивают тем же членом '=='")
    void arrayUsesEquality() {
        assertEquals("true 1", run(LENGTH + """
                items = [new Len(1), new Len(2)]
                print(items.contains(new Len(2)))
                print(" ")
                print(items.indexOf(new Len(2)))
                """));
    }

    @Test
    @DisplayName("ответ '<=>' обязан быть числом, иначе сравнение молча начало бы врать")
    void orderMustAnswerNumber() {
        assertEquals("оператор '<=>' у Bad вернул строка, а сравнение требует число",
                errorOf("""
                        class Bad(x) {
                            def `<=>`(right) => "не число"
                        }
                        print(new Bad(1) < new Bad(2))
                        """).getMessage());
    }

    @Test
    @DisplayName("без '<=>' сравнение экземпляров — ошибка, и сказано, кому чего не хватило")
    void comparisonWithoutOrder() {
        assertTrue(errorOf("""
                class Plain(x) {}
                print(new Plain(1) < new Plain(2))
                """).getMessage().startsWith("у Plain нет оператора '<'"));
    }

    @Test
    @DisplayName("сортировка смешанного массива по-прежнему честно отказывается")
    void sortStillFails() {
        assertTrue(errorOf("print([1, \"два\"].sort())").getMessage()
                .contains("не применима к типам"));
    }

    // --- принадлежность ------------------------------------------------------

    @Test
    @DisplayName("один член 'in' отвечает на все четыре записи принадлежности")
    void membership() {
        assertEquals("true true true true", run(DECK + """
                d = new Deck(["ace", "king"])
                print("ace" in d)
                print(" ")
                print(d has "ace")
                print(" ")
                print("joker" !in d)
                print(" ")
                print(d !has "joker")
                """));
    }

    @Test
    @DisplayName("член 'has' у экземпляра отвечает тем же 'in', а не по своим полям")
    void hasMemberUsesOperator() {
        assertEquals("true false", run(DECK + """
                d = new Deck(["ace"])
                print(d.has("ace"))
                print(" ")
                print(d.has("cards"))
                """));
    }

    @Test
    @DisplayName("перегрузка 'in' не делает класс перебираемым: перебор идёт по полям")
    void membershipIsNotIteration() {
        assertEquals("cards", run(DECK + """
                d = new Deck(["ace"])
                for (key in d) print(key)
                """));
    }

    @Test
    @DisplayName("без члена 'in' экземпляр по-прежнему отвечает про свои поля")
    void membershipWithoutMember() {
        assertEquals("true false", run("""
                class Plain(x) {}
                p = new Plain(1)
                print("x" in p)
                print(" ")
                print("y" in p)
                """));
    }

    @Test
    @DisplayName("искать в том, что контейнером не бывает, — прежняя ошибка")
    void notAContainer() {
        assertTrue(errorOf("print(1 in 2)").getMessage()
                .contains("искать можно в массиве, строке, объекте или диапазоне"));
    }

    // --- унарные -------------------------------------------------------------

    @Test
    @DisplayName("унарный оператор — тот же член, только без параметров")
    void unaryOperators() {
        assertEquals("-6 6 -2", run("""
                class Num(v) {
                    def `-`() => new Num(0 - v)
                    def `+`() => new Num(v)
                    def `~`() => new Num(0 - v - 1)
                }
                print((-new Num(6)).v)
                print(" ")
                print((+new Num(6)).v)
                print(" ")
                print((~new Num(1)).v)
                """));
    }

    @Test
    @DisplayName("унарный и бинарный '-' живут в одном классе и не мешают друг другу")
    void unaryAndBinaryTogether() {
        assertEquals("-5 3", run("""
                class Num(v) {
                    def `-`(right) => new Num(v - right.v)
                    def `-`() => new Num(0 - v)
                }
                print((-new Num(5)).v)
                print(" ")
                print((new Num(5) - new Num(2)).v)
                """));
    }

    @Test
    @DisplayName("'!' по-прежнему идёт по достоверности: члена у него нет вовсе")
    void notStaysTruthiness() {
        assertEquals("false", run("""
                class Plain(x) {}
                print(!new Plain(1))
                """));
    }

    @Test
    @DisplayName("без унарного члена — ошибка с именем класса, а у чисел прежний текст")
    void missingUnary() {
        assertEquals("у Plain нет унарного оператора '-'", errorOf("""
                class Plain(x) {}
                print(-new Plain(1))
                """).getMessage());
        assertTrue(errorOf("print(-\"текст\")").getMessage()
                .contains("унарный '-' применим только к числам"));
    }

    @Test
    @DisplayName("унарный член достаётся и расширением")
    void unaryFromExtend() {
        assertEquals("-6", run("""
                class Num(v) {}
                extend Num {
                    def `-`() => new Num(0 - this.v)
                }
                print((-new Num(6)).v)
                """));
    }

    // --- сравнение в match ---------------------------------------------------

    @Test
    @DisplayName("образец 'case >' в match идёт тем же '<=>', что и оператор")
    void matchUsesOrder() {
        assertEquals("дальше", run(LENGTH + """
                print(match (new Len(5)) {
                    case > new Len(3) => "дальше"
                    else => "ближе"
                })
                """));
    }
}
