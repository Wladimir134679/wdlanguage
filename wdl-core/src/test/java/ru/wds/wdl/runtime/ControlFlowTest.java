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

/** Выполнение ветвлений и циклов: выбор ветки, проходы, break/continue, области видимости. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ControlFlowTest {

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        run(code, ExecutionContext.fresh(printed::append));
        return printed.toString();
    }

    private static void run(String code, ExecutionContext context) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, context);
    }

    /** Вывод одной строкой: переводы строк заменены пробелами — так ожидания читаются целиком. */
    private static String printed(String code) {
        return run(code).replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    // --- ветвление -----------------------------------------------------------

    @Test
    @DisplayName("выполняется ровно одна ветка")
    void ifPicksOneBranch() {
        assertEquals("да", printed("if (true) println(\"да\") else println(\"нет\")"));
        assertEquals("нет", printed("if (false) println(\"да\") else println(\"нет\")"));
        assertEquals("", printed("if (false) println(\"да\")"));
    }

    @Test
    @DisplayName("цепочка else if проверяет условия по порядку")
    void elseIfChain() {
        String chain = """
                if (x > 10) println("много")
                else if (x > 5) println("средне")
                else println("мало")
                """;
        assertEquals("много", printed("x = 20\n" + chain));
        assertEquals("средне", printed("x = 7\n" + chain));
        assertEquals("мало", printed("x = 1\n" + chain));
    }

    @Test
    @DisplayName("условие смотрит на истинность: ложны только null и false")
    void conditionUsesTruthiness() {
        assertEquals("да", printed("if (0) println(\"да\")"));
        assertEquals("да", printed("if (\"\") println(\"да\")"));
        assertEquals("да", printed("if ([]) println(\"да\")"));
        assertEquals("", printed("if (null) println(\"да\")"));
        assertEquals("", printed("if (false) println(\"да\")"));
    }

    // --- циклы ---------------------------------------------------------------

    @Test
    @DisplayName("while повторяет тело, пока условие истинно")
    void whileRepeats() {
        assertEquals("0 1 2", printed("""
                i = 0
                while (i < 3) {
                    println(i)
                    i += 1
                }
                """));
        assertEquals("", printed("while (false) println(\"никогда\")"));
    }

    @Test
    @DisplayName("for со счётчиком: инициализатор, условие, шаг")
    void forCounts() {
        assertEquals("15", printed("""
                сумма = 0
                for (i = 1; i <= 5; i += 1) сумма += i
                println(сумма)
                """));
    }

    @Test
    @DisplayName("вечный for выходит по break")
    void endlessForNeedsBreak() {
        assertEquals("0 1 2", printed("""
                i = 0
                for (;;) {
                    if (i == 3) break
                    println(i)
                    i += 1
                }
                """));
    }

    @Test
    @DisplayName("перебор идёт по элементам массива, символам строки и ключам объекта")
    void forEachOverEachType() {
        assertEquals("гайка болт", printed("for (товар in [\"гайка\", \"болт\"]) println(товар)"));
        assertEquals("а б в", printed("for (буква in \"абв\") println(буква)"));
        assertEquals("x y", printed("for (ключ in {x: 1, y: 2}) println(ключ)"));
        assertEquals("", printed("for (x in []) println(x)"));
    }

    @Test
    @DisplayName("перебрать можно только массив, строку или объект")
    void forEachRejectsOtherTypes() {
        assertTrue(errorOf("for (x in 5) println(x)").getMessage().contains("перебрать можно"));
        assertTrue(errorOf("for (x in null) println(x)").getMessage().contains("перебрать можно"));
    }

    @Test
    @DisplayName("массив и объект можно менять прямо в переборе — выполнение это переживает")
    void forEachSurvivesModification() {
        assertEquals("1 2 [9, 2]", printed("""
                список = [1, 2]
                for (x in список) {
                    println(x)
                    список[0] = 9
                }
                println(список)
                """));
        assertEquals("x {\"x\": 1, \"y\": 2}", printed("""
                точка = {x: 1}
                for (ключ in точка) {
                    println(ключ)
                    точка.y = 2
                }
                println(точка)
                """));
    }

    // --- break и continue ----------------------------------------------------

    @Test
    @DisplayName("break прерывает цикл, continue — только текущий проход")
    void breakAndContinue() {
        assertEquals("0 1", printed("""
                for (i = 0; i < 10; i += 1) {
                    if (i == 2) break
                    println(i)
                }
                """));
        assertEquals("0 2", printed("""
                for (i = 0; i < 3; i += 1) {
                    if (i == 1) continue
                    println(i)
                }
                """));
    }

    @Test
    @DisplayName("continue в for не пропускает шаг, поэтому цикл остаётся конечным")
    void continueDoesNotSkipStep() {
        assertEquals("готово", printed("""
                for (i = 0; i < 100; i += 1) continue
                println("готово")
                """));
    }

    @Test
    @DisplayName("break прерывает только свой цикл")
    void breakLeavesInnerLoopOnly() {
        assertEquals("0 1 2", printed("""
                for (i = 0; i < 3; i += 1) {
                    for (j = 0; j < 10; j += 1) {
                        if (j == 1) break
                        println(i)
                    }
                }
                """));
    }

    @Test
    @DisplayName("break работает и в переборе, из любой вложенности блоков")
    void breakInsideForEach() {
        assertEquals("а", printed("""
                for (буква in "абв") {
                    if (true) {
                        println(буква)
                        break
                    }
                }
                """));
    }

    // --- области видимости ---------------------------------------------------

    @Test
    @DisplayName("имя, заведённое в блоке, снаружи не существует")
    void blockScopesNewNames() {
        assertTrue(errorOf("""
                if (true) { внутренняя = 1 }
                println(внутренняя)
                """).getMessage().contains("не определена"));
    }

    @Test
    @DisplayName("присваивание известному имени изнутри блока меняет внешнее")
    void blockAssignsOuterName() {
        assertEquals("3", printed("""
                счёт = 0
                while (счёт < 3) { счёт += 1 }
                println(счёт)
                """));
    }

    @Test
    @DisplayName("тело без скобок области не создаёт — блок создаёт")
    void bodyWithoutBracesSharesScope() {
        assertEquals("1", printed("""
                if (true) новая = 1
                println(новая)
                """));
    }

    @Test
    @DisplayName("счётчик for живёт в цикле и наружу не выходит")
    void forCounterIsLocal() {
        assertTrue(errorOf("""
                for (i = 0; i < 3; i += 1) println(i)
                println(i)
                """).getMessage().contains("не определена"));
    }

    @Test
    @DisplayName("переменная перебора своя на каждый проход")
    void forEachVariableIsPerIteration() {
        assertEquals("1 2 3", printed("for (x in [1, 2, 3]) println(x)"));
        assertTrue(errorOf("for (x in [1]) println(x)\nprintln(x)").getMessage().contains("не определена"));
    }

    // --- остановка снаружи ---------------------------------------------------

    @Test
    @DisplayName("зациклившийся скрипт останавливается по прерыванию потока")
    void interruptStopsEndlessLoop() {
        Thread.currentThread().interrupt();
        try {
            // Прерывание — FatalError: скрипт не должен уметь его поймать и продолжить,
            // иначе while (true) с try съел бы то, ради чего звали interrupt().
            FatalError fatal = assertThrows(FatalError.class, () -> run("for (;;) { x = 1 }"));
            assertTrue(fatal.getMessage().contains("прервано"), fatal.getMessage());
        } finally {
            // Флаг снимаем, иначе он утечёт в соседние тесты.
            Thread.interrupted();
        }
    }
}
