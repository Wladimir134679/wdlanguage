package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.NullValue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пределы выполнения: то, чем запуск отвечает на «а если скрипт чужой».
 * <p>
 * Проверяется здесь не «скрипт остановился» — этого мало, — а три вещи, из которых
 * состоит обещание:
 * <ul>
 *   <li><b>останавливается всё, что зацикливается</b>: цикл, перебор, рекурсия;</li>
 *   <li><b>остановку нельзя съесть обработчиком</b>, а {@code defer} при этом
 *       отрабатывает: закрыть своё на пути наружу можно, подавить причину — нет;</li>
 *   <li><b>выключённые пределы ничего не меняют</b>: без них поведение ровно то,
 *       что было до их появления.</li>
 * </ul>
 * Ожидания по времени здесь — защёлки и {@code assertTimeoutPreemptively}, а не
 * секундомер: «уложились в две секунды» проверяет то, что нужно, — сторож сработал, —
 * и не краснеет от того, что сборочная машина занята.
 */
class LimitsTest {

    /** Заведомо больше любой честной задержки и заведомо меньше вечности. */
    private static final long PATIENCE_MS = 5000;

    /** Выполненный скрипт плюс то, что он напечатал. */
    private record Script(ExecutionContext context, Execution done, StringBuilder printed) {

        /**
         * Имя, объявленное скриптом.
         * <p>
         * Ищется в области <b>файла</b>, а не в корневой: файл выполняется в своей,
         * и объявленное им наружу не попадает.
         */
        Value name(String name) {
            Value value = done.scope().scope().lookup(name);
            assertFalse(value == null, () -> "имя '" + name + "' не объявлено скриптом");
            return value;
        }
    }

    private static Script run(String code, Limits limits) {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append).withLimits(limits);
        install(context);
        Execution done = execute(code, context);
        return new Script(context, done, printed);
    }

    /** То же, но ошибка запуска отдаётся вызывающему вместе с напечатанным. */
    private static Script broken(String code, Limits limits, Class<? extends WdlError> expected,
                                 String messagePart) {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append).withLimits(limits);
        install(context);
        WdlError error = assertThrows(WdlError.class, () -> execute(code, context));
        assertInstanceOf(expected, error);
        assertTrue(error.getMessage().contains(messagePart),
                () -> "ожидалось сообщение про «" + messagePart + "», а пришло: " + error.getMessage());
        return new Script(context, null, printed);
    }

    private static void install(ExecutionContext context) {
        // Блокирующая встроенная функция: то, чего мягкая проверка дедлайна не достаёт
        // и ради чего заведён сторож.
        context.scope().define("block", BuiltinFunction.of("block", Arity.exactly(0),
                (call, arguments, span) -> {
                    try {
                        Thread.sleep(PATIENCE_MS * 10);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                    return NullValue.NULL;
                }));
    }

    private static Execution execute(String code, ExecutionContext context) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        return new Interpreter().run(Unit.of(source, program), context);
    }

    private static Limits steps(long count) {
        return Limits.builder().maxSteps(count).build();
    }

    // --- шаги ----------------------------------------------------------------

    @Test
    @DisplayName("Вечный while останавливается на исчерпании шагов")
    void whileStops() {
        broken("i = 0\nwhile (true) { i = i + 1 }", steps(500),
                FatalError.class, "исчерпал отведённые 500 шагов");
    }

    @Test
    @DisplayName("Вечный for останавливается тем же")
    void forStops() {
        broken("for (;;) { }", steps(300), FatalError.class, "исчерпал отведённые");
    }

    @Test
    @DisplayName("Длинный перебор останавливается тем же")
    void foreachStops() {
        broken("total = 0\nfor (x in 1..1000000) { total = total + x }", steps(400),
                FatalError.class, "исчерпал отведённые");
    }

    @Test
    @DisplayName("Рекурсия без цикла — тоже шаги: вызов считается наравне с итерацией")
    void recursionCountsAsSteps() {
        // Предел вложенности поднят выше числа шагов нарочно: иначе остановила бы
        // рекурсия, а проверяется здесь именно счётчик шагов.
        Limits limits = Limits.builder().maxSteps(100).maxCallDepth(1000).build();
        broken("def f(n) => f(n + 1)\nf(0)", limits, FatalError.class, "исчерпал отведённые 100");
    }

    @Test
    @DisplayName("Честный скрипт свои шаги не исчерпывает")
    void honestScriptFinishes() {
        Script script = run("total = 0\nfor (i = 0; i < 100; i = i + 1) { total = total + i }\n"
                + "println(total)", steps(10000));
        assertEquals("4950" + System.lineSeparator(), script.printed().toString());
    }

    @Test
    @DisplayName("Сделанные шаги видны запуску")
    void stepsAreCounted() {
        Script script = run("for (i = 0; i < 1000; i = i + 1) { }", steps(100000));
        assertTrue(script.context().run().steps() >= 1000,
                () -> "шагов насчитано " + script.context().run().steps());
    }

    // --- остановку нельзя съесть ---------------------------------------------

    @Test
    @DisplayName("Остановка не ловится 'catch' — иначе цикл съел бы свой предохранитель")
    void notCatchable() {
        Script script = broken("try { while (true) { } } catch (e) { println(\"поймал\") }",
                steps(300), FatalError.class, "исчерпал отведённые");
        assertEquals("", script.printed().toString());
    }

    @Test
    @DisplayName("'defer' на пути наружу выполняется: закрыть своё можно, подавить причину — нет")
    void deferStillRuns() {
        Script script = broken("""
                def loop() {
                    defer println("закрыто")
                    while (true) { }
                }
                loop()
                """, steps(400), FatalError.class, "исчерпал отведённые");
        assertEquals("закрыто" + System.lineSeparator(), script.printed().toString());
    }

    // --- время ---------------------------------------------------------------

    @Test
    @DisplayName("Мягкий дедлайн останавливает вечный цикл")
    void softDeadlineStopsLoop() {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS), () ->
                broken("while (true) { }",
                        Limits.builder().timeout(Duration.ofMillis(100)).build(),
                        FatalError.class, "вышло отведённое время"));
    }

    @Test
    @DisplayName("Сторож снимает застрявший блокирующий вызов, до которого дедлайн не достаёт")
    void watchdogStopsBlockingCall() {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS), () -> {
            FatalError stop = assertThrows(FatalError.class, () ->
                    execute("block()", ready(Limits.builder()
                            .timeout(Duration.ofMillis(100)).build())));
            // Библиотека знает только про прерывание — причину подставляет граница
            // запуска, иначе хозяин искал бы, кто же нажал стоп.
            assertTrue(stop.getMessage().contains("вышло отведённое время"), stop.getMessage());
        });
    }

    @Test
    @DisplayName("Флаг прерывания от сторожа не уходит наружу вместе с потоком")
    void interruptDoesNotLeak() {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS), () -> {
            assertThrows(FatalError.class, () ->
                    execute("block()", ready(Limits.builder()
                            .timeout(Duration.ofMillis(100)).build())));
            assertFalse(Thread.currentThread().isInterrupted(),
                    "поток вышел из скрипта с чужим флагом прерывания");
        });
    }

    private static ExecutionContext ready(Limits limits) {
        ExecutionContext context = ExecutionContext.fresh(text -> { }).withLimits(limits);
        install(context);
        return context;
    }

    // --- счёт общий на запуск ------------------------------------------------

    @Test
    @DisplayName("Счётчик шагов один на запуск, а не по лимиту каждому потоку")
    void stepsAreSharedBetweenThreads() throws Exception {
        // Каждый поток крутит меньше предела, но вдвоём они его перебирают: при счёте
        // по потоку прошли бы оба.
        Script script = run("def spin() { for (i = 0; i < 4000; i = i + 1) { } }",
                steps(5000));
        var function = assertInstanceOf(FunctionValue.class, script.name("spin"));

        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger stopped = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        for (int i = 0; i < 2; i++) {
            Thread worker = new Thread(() -> {
                try {
                    function.call(script.context(), List.of(), Span.point(0));
                } catch (FatalError expected) {
                    stopped.incrementAndGet();
                } catch (Throwable other) {
                    unexpected.set(other);
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }
        assertTrue(done.await(PATIENCE_MS, TimeUnit.MILLISECONDS), "потоки не закончили работу");
        assertEquals(null, unexpected.get(), () -> "неожиданная ошибка: " + unexpected.get());
        assertTrue(stopped.get() >= 1, "предел шагов не сработал: счёт идёт по потоку");
    }

    // --- выключенные пределы --------------------------------------------------

    @Test
    @DisplayName("Без пределов вечный цикл останавливает только прерывание снаружи")
    void withoutLimitsOnlyInterruptStops() throws Exception {
        assertFalse(Limits.none().counting());
        ExecutionContext context = ExecutionContext.fresh(text -> { });
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                execute("while (true) { }", context);
            } catch (Throwable stop) {
                outcome.set(stop);
            }
        });
        worker.setDaemon(true);
        worker.start();
        assertTrue(started.await(PATIENCE_MS, TimeUnit.MILLISECONDS));
        // Времени на то, чтобы упереться в несуществующий предел, у него было вдоволь.
        Thread.sleep(150);
        assertTrue(worker.isAlive(), "скрипт остановился сам, хотя пределов нет");
        worker.interrupt();
        worker.join(PATIENCE_MS);
        assertInstanceOf(FatalError.class, outcome.get());
        assertTrue(outcome.get().getMessage().contains("прервано"), outcome.get().getMessage());
    }

    // --- вложенность ----------------------------------------------------------

    @Test
    @DisplayName("Предел вложенности вызовов настраивается и называет себя в сообщении")
    void callDepthIsConfigurable() {
        broken("def f(n) => f(n + 1)\nf(0)",
                Limits.builder().maxCallDepth(12).build(),
                FatalError.class, "вложенных вызовов больше 12");
    }

    @Test
    @DisplayName("Пределы проверяются при создании: отрицательных не бывает")
    void limitsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> Limits.builder().maxSteps(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> Limits.builder().timeout(Duration.ofSeconds(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> Limits.builder().maxCallDepth(0).build());
    }
}
