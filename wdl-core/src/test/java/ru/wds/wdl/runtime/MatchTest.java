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

/** Выполнение {@code match}: выбор ветки, однократность предмета, ленивость образцов. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class MatchTest {

    private static String printed(String code) {
        StringBuilder out = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(out::append));
        return out.toString().replace(System.lineSeparator(), " ").trim();
    }

    @Test
    @DisplayName("выражением match даёт значение первой подошедшей ветки")
    void valuePosition() {
        assertEquals("critical", printed("""
                cpu = 95
                level = match (cpu) {
                    case > 90 => "critical"
                    case > 70 => "high"
                    else => "fine"
                }
                println(level)
                """));
        assertEquals("high", printed("""
                cpu = 80
                println(match (cpu) {
                    case > 90 => "critical"
                    case > 70 => "high"
                    else => "fine"
                })
                """));
        assertEquals("fine", printed("""
                cpu = 10
                println(match (cpu) {
                    case > 90 => "critical"
                    else => "fine"
                })
                """));
    }

    @Test
    @DisplayName("инструкцией match выполняет тело ветки и ничего не даёт")
    void statementPosition() {
        assertEquals("one", printed("""
                code = 1
                match (code) {
                    case 1 { println("one") }
                    case 2 { println("two") }
                }
                """));
        assertEquals("one", printed("""
                code = 1
                match (code) { case 1 => println("one") }
                """));
    }

    @Test
    @DisplayName("match без else инструкцией просто ничего не делает")
    void statementWithoutElseDoesNothing() {
        assertEquals("готово", printed("""
                code = 99
                match (code) {
                    case 1 { println("one") }
                }
                println("готово")
                """));
    }

    @Test
    @DisplayName("предмет вычисляется ровно один раз")
    void subjectEvaluatedOnce() {
        assertEquals("считаю high", printed("""
                def total() {
                    println("считаю")
                    return 80;
                }
                println(match (total()) {
                    case > 90 => "critical"
                    case > 70 => "high"
                    else => "fine"
                })
                """));
    }

    @Test
    @DisplayName("образцы вычисляются лениво, сверху вниз, до первого совпадения")
    void patternsAreLazy() {
        assertEquals("первый one", printed("""
                def mark(name, value) {
                    println(name)
                    return value;
                }
                println(match (1) {
                    case mark("первый", 1) => "one"
                    case mark("второй", 2) => "two"
                    else => "other"
                })
                """));
    }

    @Test
    @DisplayName("перечисление образцов заменяет провал")
    void severalPatterns() {
        assertEquals("few few few many", printed("""
                for (n in 1..4) {
                    println(match (n) {
                        case 1, 2, 3 => "few"
                        else => "many"
                    })
                }
                """));
    }

    @Test
    @DisplayName("в образце работают is, in и диапазон — те же операторы, что в if")
    void patternsReuseOperators() {
        assertEquals("число", printed("""
                println(match (5) {
                    case is Number => "число"
                    else => "другое"
                })
                """));
        assertEquals("admin", printed("""
                println(match ("admin") {
                    case in ["admin", "root"] => "admin"
                    else => "user"
                })
                """));
        assertEquals("working", printed("""
                println(match (42) {
                    case in 18..65 => "working"
                    else => "other"
                })
                """));
        assertEquals("нет", printed("""
                println(match ("hello") {
                    case !in ["admin"] => "нет"
                    else => "да"
                })
                """));
    }

    @Test
    @DisplayName("условие проверяется после образцов и бывает без них")
    void guards() {
        assertEquals("slowed", printed("""
                throttled = true
                println(match (95) {
                    case > 90 if !throttled => "critical"
                    case if throttled => "slowed"
                    else => "fine"
                })
                """));
        assertEquals("critical", printed("""
                throttled = false
                println(match (95) {
                    case > 90 if !throttled => "critical"
                    case if throttled => "slowed"
                    else => "fine"
                })
                """));
    }

    @Test
    @DisplayName("break из ветки относится к объемлющему циклу, а не к match")
    void breakLeavesTheLoop() {
        assertEquals("1 2", printed("""
                for (i in 1..5) {
                    match (i) {
                        case 3 { break }
                        else { println(i) }
                    }
                }
                """));
        assertEquals("1 2 4 5", printed("""
                for (i in 1..5) {
                    match (i) {
                        case 3 { continue }
                        else { println(i) }
                    }
                }
                """));
    }

    @Test
    @DisplayName("тело-блок ветки заводит свою область видимости")
    void blockBranchHasOwnScope() {
        assertEquals("внутри null", printed("""
                match (1) {
                    case 1 {
                        inner = "внутри"
                        println(inner)
                    }
                }
                println(try? inner)
                """));
    }

    @Test
    @DisplayName("match вкладывается в match, и это обычное выражение")
    void nested() {
        assertEquals("x", printed("""
                a = 1
                b = 2
                println(match (a) {
                    case 1 => match (b) { case 2 => "x" else => "y" }
                    else => "z"
                })
                """));
    }

    @Test
    @DisplayName("return из ветки выходит из функции")
    void returnFromBranch() {
        assertEquals("one", printed("""
                def name(code) {
                    match (code) {
                        case 1 { return "one"; }
                        else { return "other"; }
                    }
                }
                println(name(1))
                """));
    }

    // --- yield ---------------------------------------------------------------

    @Test
    @DisplayName("ветка-блок отдаёт значение словом yield")
    void yieldGivesBranchValue() {
        assertEquals("итого 360", printed("""
                price = 120
                count = 3
                text = match (count) {
                    case > 0 {
                        total = price * count
                        yield "итого " + total
                    }
                    else => "пусто"
                }
                println(text)
                """));
    }

    @Test
    @DisplayName("yield заканчивает ветку: то, что записано после него, не выполняется")
    void yieldEndsTheBranch() {
        assertEquals("до one", printed("""
                text = match (1) {
                    case 1 {
                        println("до")
                        yield "one"
                        println("после")
                    }
                    else => "other"
                }
                println(text)
                """));
    }

    @Test
    @DisplayName("yield работает из глубины ветки — из ветвления и из цикла")
    void yieldFromDepth() {
        assertEquals("большой", printed("""
                println(match (150) {
                    case > 100 {
                        if (true) { yield "большой" }
                        yield "не дойдём"
                    }
                    else => "мелкий"
                })
                """));
        assertEquals("3", printed("""
                println(match ([1, 2, 3, 4]) {
                    case is Array {
                        for (item in [1, 2, 3, 4]) {
                            if (item > 2) yield item
                        }
                        yield 0
                    }
                    else => -1
                })
                """));
    }

    @Test
    @DisplayName("ветка, не дошедшая до yield, — ошибка выполнения, а не тихий null")
    void branchWithoutYieldIsAnError() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class, () -> printed("""
                text = match (1) {
                    case 1 {
                        if (false) { yield "one" }
                    }
                    else => "other"
                }
                println(text)
                """));
        assertTrue(error.getMessage().contains("ветка 'case' закончилась, не отдав значение"),
                error.getMessage());
    }

    @Test
    @DisplayName("defer внутри ветки отрабатывает по пути наружу")
    void deferRunsBeforeYieldLeaves() {
        assertEquals("прибрали one", printed("""
                text = match (1) {
                    case 1 {
                        defer println("прибрали")
                        yield "one"
                    }
                    else => "other"
                }
                println(text)
                """));
    }

    @Test
    @DisplayName("вложенный match забирает свой yield первым")
    void nestedMatchTakesItsOwnYield() {
        assertEquals("внутренний-x", printed("""
                println(match (1) {
                    case 1 {
                        inner = match (2) {
                            case 2 { yield "x" }
                            else => "y"
                        }
                        yield "внутренний-" + inner
                    }
                    else => "z"
                })
                """));
    }

    @Test
    @DisplayName("yield отдаёт ветку, return выходит из функции")
    void yieldAndReturnAreDifferent() {
        assertEquals("ветка готово", printed("""
                def describe(code) {
                    text = match (code) {
                        case 1 { yield "ветка" }
                        else => "другое"
                    }
                    println(text)
                    return "готово";
                }
                println(describe(1))
                """));
        // return из ветки выходит из всей функции, минуя присваивание
        assertEquals("рано", printed("""
                def describe(code) {
                    text = match (code) {
                        case 1 { return "рано"; }
                        else => "другое"
                    }
                    println("сюда не дойдём")
                    return text;
                }
                println(describe(1))
                """));
    }

    @Test
    @DisplayName("стрелка и блок с yield смешиваются в одном match")
    void arrowAndBlockMix() {
        assertEquals("мало 6 много", printed("""
                def size(n) => match (n) {
                    case < 3 => "мало"
                    case < 10 {
                        doubled = n * 2
                        yield doubled
                    }
                    else => "много"
                }
                println(size(1), " ", size(3), " ", size(50))
                """));
    }

    @Test
    @DisplayName("ветке, уходящей через return, throw или break, yield не нужен")
    void branchesThatLeaveNeedNoYield() {
        // throw: значения ветка не даёт законно — управление уходит мимо match.
        assertEquals("пусто", printed("""
                def parse(code) {
                    text = match (code) {
                        case 0 { throw new ValueError("пусто") }
                        else => "ок"
                    }
                    return text;
                }
                println(try? parse(0) || "пусто")
                """));
        // break: выходит из объемлющего цикла, присваивание не состоится
        assertEquals("вышли", printed("""
                for (i in 1..3) {
                    text = match (i) {
                        case 2 { break }
                        else => "ок"
                    }
                }
                println("вышли")
                """));
    }
}
