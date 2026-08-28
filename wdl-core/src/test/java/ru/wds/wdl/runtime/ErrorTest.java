package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Arity;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ошибки во время выполнения: бросок, ловля, {@code finally} и трассировка.
 * <p>
 * Проверяется наблюдаемое поведение — что напечатал скрипт и какой ошибкой кончился, —
 * а не устройство: материализация экземпляра ленивая, и тест не должен её замечать.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ErrorTest {

    /** Запускает скрипт и возвращает напечатанное одной строкой. */
    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        run(code, output::append);
        return oneLine(output);
    }

    /**
     * Напечатанное до того, как скрипт упал. Нужно там, где проверяется именно
     * порядок: что {@code finally} успел выполниться, а обработчик — нет.
     */
    private static String printedBeforeFailure(String code, Class<? extends WdlError> expected) {
        StringBuilder output = new StringBuilder();
        assertThrows(expected, () -> run(code, output::append));
        return oneLine(output);
    }

    private static String oneLine(StringBuilder output) {
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static void run(String code, Output output) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        // Юнитом, а не голой программой: место броска и строки трассировки осмысленны
        // только вместе с исходником, а без него их и не показывают.
        new Interpreter().run(Unit.of(source, program), ExecutionContext.fresh(output));
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    // --- прелюдия ------------------------------------------------------------

    @Test
    @DisplayName("иерархия Exception есть в области видимости до первой строки скрипта")
    void preludeIsThere() {
        assertEquals("class true true", printed("""
                println(typeof(Exception), " ", new IndexError("x") is RuntimeError,
                        " ", new IndexError("x") is Exception)
                """));
    }

    // --- бросок --------------------------------------------------------------

    @Test
    @DisplayName("своя ошибка ловится своим классом")
    void throwAndCatchOwnClass() {
        assertEquals("не число: abc", printed("""
                class ParseError(raw) : Exception("не число: " + raw)
                try {
                    throw new ParseError("abc")
                } catch (e is ParseError) {
                    println(e.message)
                }
                """));
    }

    @Test
    @DisplayName("бросить можно только экземпляр Exception")
    void onlyExceptionsFly() {
        assertTrue(errorOf("throw 5").getMessage().contains("только экземпляр Exception"));
        assertTrue(errorOf("class Point(x)\nthrow new Point(1)")
                .getMessage().contains("только экземпляр Exception"));
    }

    @Test
    @DisplayName("ловля по предку и по трейту — то же самое, что 'is'")
    void catchByParentAndTrait() {
        assertEquals("по предку по трейту", printed("""
                trait Retriable { }
                class HttpError(code) : Exception("HTTP " + code) with Retriable

                try { throw new HttpError(503) } catch (e is Exception) { print("по предку ") }
                try { throw new HttpError(503) } catch (e is Retriable) { print("по трейту") }
                """));
    }

    @Test
    @DisplayName("catch (e is Object) ловит любую ошибку скрипта: она тоже object")
    void catchByTypeDescriptor() {
        // Та же логика, что у 'p is Object' для экземпляра класса: ошибка — это object,
        // и Object.matches отвечает совпадением ValueType, а не цепочкой предков.
        assertEquals("поймано", printed("""
                try { throw new IndexError("вне границ") } catch (e is Object) { print("поймано") }
                """));
    }

    @Test
    @DisplayName("typeof(e) == Exception и e is Exception — разные вопросы: у типов нет иерархии")
    void typeofAndIsDivergeForExceptions() {
        assertEquals("false true", printed("""
                e = new IndexError("x")
                println(typeof(e) == Exception, " ", e is Exception)
                """));
    }

    @Test
    @DisplayName("catch без типа ловит всё, что вообще ловится")
    void catchEverything() {
        assertEquals("ArithmeticError ой", printed("""
                try { println(1 / 0) } catch (e) { print(e.kind, " ") }
                try { throw new Exception("ой") } catch (e) { println(e.message) }
                """));
    }

    @Test
    @DisplayName("берётся первый подходящий обработчик, сверху вниз")
    void firstMatchingHandlerWins() {
        assertEquals("второй", printed("""
                try {
                    println(1 / 0)
                } catch (e is TypeError) {
                    println("первый")
                } catch (e is RuntimeError) {
                    println("второй")
                } catch (e) {
                    println("третий")
                }
                """));
    }

    @Test
    @DisplayName("несколько типов в одном обработчике")
    void severalTypesInOneHandler() {
        assertEquals("index arithmetic", printed("""
                def kindOf(f) {
                    try {
                        f()
                    } catch (e is IndexError, ArithmeticError) {
                        return e is IndexError ? "index" : "arithmetic";
                    }
                }
                println(kindOf(def() => [1][9]), " ", kindOf(def() => 1 / 0))
                """));
    }

    // --- ошибки движка -------------------------------------------------------

    @Test
    @DisplayName("ошибка движка получает класс, сообщение и место")
    void engineErrorIsAValue() {
        assertEquals("IndexError object true true", printed("""
                try {
                    println([1, 2][7])
                } catch (e is IndexError) {
                    println(e.kind, " ", typeof(e), " ",
                            e.message != "", " ", e.at != "")
                }
                """));
    }

    @Test
    @DisplayName("не пойманная своим классом ошибка летит дальше")
    void unmatchedErrorFliesOut() {
        WdlRuntimeError error = errorOf("try { println(1 / 0) } catch (e is IndexError) { println(1) }");
        assertEquals(ErrorKind.ARITHMETIC, error.kind());
        assertEquals("ArithmeticError", error.kindName());
    }

    // --- finally -------------------------------------------------------------

    @Test
    @DisplayName("finally выполняется при нормальном выходе, при return и при break")
    void finallyRunsOnEveryExit() {
        assertEquals("тело конец", printed("try { print(\"тело \") } finally { println(\"конец\") }"));

        assertEquals("конец 1", printed("""
                def f() {
                    try { return 1; } finally { print("конец ") }
                }
                println(f())
                """));

        assertEquals("конец после", printed("""
                for (i in [1, 2, 3]) {
                    try { break } finally { print("конец ") }
                }
                println("после")
                """));
    }

    @Test
    @DisplayName("finally выполняется и на пути ошибки, которую никто не поймал")
    void finallyRunsOnFlyingError() {
        assertEquals("конец", printedBeforeFailure(
                "try { println(1 / 0) } finally { println(\"конец\") }", WdlRuntimeError.class));
    }

    @Test
    @DisplayName("ошибка из обработчика летит наружу, но finally выполняется")
    void errorFromHandlerFlies() {
        assertEquals("конец", printedBeforeFailure("""
                try {
                    throw new Exception("первая")
                } catch (e) {
                    throw new Exception("вторая")
                } finally {
                    println("конец")
                }
                """, WdlRuntimeError.class));
    }

    @Test
    @DisplayName("ошибка в finally не затирает ту, ради которой мы шли наружу")
    void finallyErrorIsSuppressed() {
        assertEquals("первая 1 вторая", printed("""
                try {
                    try {
                        throw new Exception("первая")
                    } finally {
                        throw new Exception("вторая")
                    }
                } catch (e) {
                    println(e.message, " ", len(e.suppressed), " ", e.suppressed[0].message)
                }
                """));
    }

    // --- то, что не ловится --------------------------------------------------

    @Test
    @DisplayName("прерывание потока не ловится, но finally при нём выполняется")
    void fatalIsNotCatchable() {
        Thread.currentThread().interrupt();
        try {
            assertEquals("конец", printedBeforeFailure("""
                    try {
                        for (;;) { x = 1 }
                    } catch (e) {
                        println("поймал")
                    } finally {
                        println("конец")
                    }
                    """, FatalError.class));
        } finally {
            // Флаг снимаем, иначе он утечёт в соседние тесты.
            Thread.interrupted();
        }
    }

    // --- цепочка причин и повторный бросок -----------------------------------

    @Test
    @DisplayName("cause сохраняет исходную ошибку при перезаворачивании")
    void causeKeepsTheOriginal() {
        assertEquals("конфиг не прочитан / ArithmeticError", printed("""
                class ConfigError(cause) : Exception("конфиг не прочитан", cause)
                try {
                    try {
                        println(1 / 0)
                    } catch (e is RuntimeError) {
                        throw new ConfigError(e)
                    }
                } catch (e is ConfigError) {
                    println(e.message, " / ", e.cause.kind)
                }
                """));
    }

    @Test
    @DisplayName("повторный бросок пойманной ошибки места и трейса не затирает")
    void rethrowKeepsPlace() {
        assertEquals("true", printed("""
                def deep() { throw new Exception("ой"); }

                first = ""
                try {
                    try {
                        deep()
                    } catch (e) {
                        first = e.at
                        throw e
                    }
                } catch (e) {
                    println(e.at == first)
                }
                """));
    }

    // --- трассировка ---------------------------------------------------------

    @Test
    @DisplayName("трейс — по строке на вызов, от места броска наружу")
    void traceHasFramePerCall() {
        // Кадр называет функцию и место её вызова — то есть строку вызывающего,
        // а не ту, на которой рвануло: где рвануло, показывает сама ошибка.
        assertEquals("3 в a (<script>:2:12) в b (<script>:3:12)", printed("""
                def a() => 1 / 0
                def b() => a()
                def c() => b()

                try {
                    c()
                } catch (e) {
                    println(len(e.trace), " ", e.trace[0], " ", e.trace[1])
                }
                """));
    }

    @Test
    @DisplayName("трейс есть и у ошибки, пойманной внутри той же функции")
    void traceSurvivesLocalCatch() {
        // Границы вызова такая ошибка не пересекает — а кадры вокруг есть,
        // и обработчик вправе их видеть.
        assertEquals("2 в inner (<script>:4:16)", printed("""
                def inner() {
                    try { println(1 / 0) } catch (e) { println(len(e.trace), " ", e.trace[0]) }
                }
                def outer() => inner()
                outer()
                """));
    }

    @Test
    @DisplayName("у ошибки на верхнем уровне трейса нет: вызовов не было")
    void noTraceWithoutCalls() {
        assertEquals("0", printed("try { println(1 / 0) } catch (e) { println(len(e.trace)) }"));
        assertTrue(errorOf("println(1 / 0)").trace().isEmpty());
    }

    // --- то, что прилетело из Java -------------------------------------------

    /**
     * Запускает скрипт, положив в область видимости функцию, которая ведёт себя как
     * чужая библиотека: бросает обычное исключение Java, а не ошибку скрипта.
     */
    private static String withMisbehavingLibrary(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());

        ExecutionContext context = ExecutionContext.fresh(output::append);
        context.scope().define("query", BuiltinFunction.of("query", Arity.any(),
                (ignoredContext, ignoredArguments, ignoredSpan) -> {
                    throw new IllegalStateException("база недоступна");
                }));
        new Interpreter().run(Unit.of(source, program), context);
        return oneLine(output);
    }

    @Test
    @DisplayName("чужое исключение становится JavaException с местом в скрипте")
    void foreignExceptionIsWrapped() {
        assertEquals("JavaException java.lang.IllegalStateException база недоступна true",
                withMisbehavingLibrary("""
                try {
                    query("select 1")
                } catch (e is JavaException) {
                    println(e.kind, " ", e.javaClass, " ", e.message, " ",
                            len(e.javaTrace()) > 0)
                }
                """));
    }

    @Test
    @DisplayName("JavaException — не ошибка движка: catch по RuntimeError её не видит")
    void javaExceptionIsNotRuntimeError() {
        assertEquals("не движок", withMisbehavingLibrary("""
                try {
                    try {
                        query("select 1")
                    } catch (e is RuntimeError) {
                        println("не сюда")
                    }
                } catch (e is Exception) {
                    println("не движок")
                }
                """));
    }

    // --- короткие формы ------------------------------------------------------

    @Test
    @DisplayName("try? даёт null при ошибке и значение без неё")
    void optionalTry() {
        assertEquals("null 4 30", printed("""
                println(try? [1][9], " ", try? 2 + 2, " ", (try? [1][9]) == null ? 30 : 0)
                """));
    }

    @Test
    @DisplayName("try! пропускает значение, а на ошибке останавливает выполнение")
    void forcedTry() {
        assertEquals("4", printed("println(try! 2 + 2)"));

        FatalError fatal = assertThrows(FatalError.class, () -> printed("x = try! [1][9]"));
        assertTrue(fatal.getMessage().contains("здесь ошибки быть не должно"), fatal.getMessage());
        // Причина сохранена: по ней видно, что именно не сложилось.
        assertEquals("IndexError", fatal.reason().kindName());
    }

    @Test
    @DisplayName("короткие формы не гасят то, что не ловится")
    void shortFormsDoNotSwallowFatal() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(FatalError.class,
                    () -> printed("def runForever() { for (;;) { } }\nx = try? runForever()"));
        } finally {
            Thread.interrupted();
        }
    }

    // --- defer ---------------------------------------------------------------

    @Test
    @DisplayName("отложенное выполняется в обратном порядке на выходе из области")
    void deferRunsInReverse() {
        assertEquals("тело второй первый после", printed("""
                {
                    defer print("первый ")
                    defer print("второй ")
                    print("тело ")
                }
                println("после")
                """));
    }

    @Test
    @DisplayName("область у defer — блок, поэтому в цикле он срабатывает на каждом проходе")
    void deferRunsEveryIteration() {
        assertEquals("открыт a закрыт a открыт b закрыт b", printed("""
                for (name in ["a", "b"]) {
                    defer print("закрыт ", name, " ")
                    print("открыт ", name, " ")
                }
                """));
    }

    @Test
    @DisplayName("отложенное выполняется при return, break и на пути ошибки")
    void deferRunsOnEveryExit() {
        assertEquals("прибрано значение", printed("""
                def f() {
                    defer print("прибрано ")
                    return "значение";
                }
                println(f())
                """));

        assertEquals("прибрано после", printed("""
                for (i in [1, 2]) {
                    defer print("прибрано ")
                    break
                }
                println("после")
                """));

        assertEquals("прибрано", printedBeforeFailure("""
                {
                    defer println("прибрано")
                    println(1 / 0)
                }
                """, WdlRuntimeError.class));
    }

    @Test
    @DisplayName("записывается только то, до чего дошло выполнение")
    void deferIsRecordedAfterTheFact() {
        // Ровно то, чем defer отличается от finally: блока «а был ли ресурс» не нужно.
        assertEquals("до", printedBeforeFailure("""
                {
                    print("до")
                    println(1 / 0)
                    defer println(" не записан")
                }
                """, WdlRuntimeError.class));
    }

    @Test
    @DisplayName("ошибка из defer не затирает ту, ради которой мы шли наружу")
    void deferErrorIsSuppressed() {
        assertEquals("основная 1 из defer", printed("""
                try {
                    defer throw new Exception("из defer");
                    throw new Exception("основная")
                } catch (e) {
                    println(e.message, " ", len(e.suppressed), " ", e.suppressed[0].message)
                }
                """));
    }

    @Test
    @DisplayName("отложенное на верхнем уровне файла выполняется, когда файл дочитан")
    void deferAtFileLevel() {
        assertEquals("тело конец", printed("""
                defer println("конец")
                println("тело")
                """));
    }

    // --- ресурсы -------------------------------------------------------------

    /** Класс-ресурс, который рассказывает о себе печатью. */
    private static final String RESOURCE = """
            class Res(name) with Closeable {
                def close() => print("закрыт ", name, " ")
            }
            """;

    @Test
    @DisplayName("Closeable — обычный трейт: забыли close, и это ошибка на строке class")
    void closeableIsAnOrdinaryTrait() {
        assertTrue(errorOf("class Broken(name) with Closeable")
                .getMessage().contains("нет метода 'close'"));
    }

    @Test
    @DisplayName("use закрывает ресурсы в обратном порядке")
    void useClosesInReverse() {
        assertEquals("тело закрыт второй закрыт первый", printed(RESOURCE + """
                use (a = new Res("первый"), b = new Res("второй")) {
                    print("тело ")
                }
                """));
    }

    @Test
    @DisplayName("use закрывает ресурс и на пути ошибки, и при return")
    void useClosesOnEveryExit() {
        assertEquals("закрыт f", printedBeforeFailure(RESOURCE + """
                use (f = new Res("f")) {
                    println(1 / 0)
                }
                """, WdlRuntimeError.class));

        assertEquals("закрыт f готово", printed(RESOURCE + """
                def read() {
                    use (f = new Res("f")) {
                        return "готово";
                    }
                }
                println(read())
                """));
    }

    @Test
    @DisplayName("значение без Closeable в use — ошибка до выполнения тела")
    void useChecksTheContract() {
        WdlRuntimeError error = errorOf("""
                class Point(x)
                use (p = new Point(1)) {
                    println("не сюда")
                }
                """);
        assertTrue(error.getMessage().contains("не подмешивает трейт 'Closeable'"), error.getMessage());
    }

    @Test
    @DisplayName("если бросил сам захват, закрывается только взятое левее")
    void failedAcquisitionClosesWhatIsHeld() {
        assertEquals("закрыт первый", printedBeforeFailure(RESOURCE + """
                use (a = new Res("первый"), b = new Res("второй")[9]) {
                    println("не сюда")
                }
                """, WdlRuntimeError.class));
    }

    @Test
    @DisplayName("ошибка при закрытии не затирает ту, ради которой мы шли наружу")
    void closeErrorIsSuppressed() {
        assertEquals("из тела 1", printed("""
                class Bad() with Closeable {
                    def close() { throw new Exception("из close"); }
                }
                try {
                    use (f = new Bad()) {
                        throw new Exception("из тела")
                    }
                } catch (e) {
                    println(e.message, " ", len(e.suppressed))
                }
                """));
    }

    @Test
    @DisplayName("непойманная ошибка доносит трейс до хозяина запуска")
    void hostSeesTheTrace() {
        WdlRuntimeError error = errorOf("""
                def inner() => 1 / 0
                def outer() => inner()
                outer()
                """);
        assertEquals(2, error.trace().size(), error.trace().toString());
        assertTrue(error.trace().get(0).startsWith("в inner ("), error.trace().toString());
        assertTrue(error.trace().get(1).startsWith("в outer ("), error.trace().toString());
    }
}
