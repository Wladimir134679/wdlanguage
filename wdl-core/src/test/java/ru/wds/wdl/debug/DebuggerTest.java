package ru.wds.wdl.debug;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.Execution;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Limits;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Отладчик: остановка на инструкции, кадры, шаги и вычисление в кадре.
 * <p>
 * Проверяется здесь не «сессия что-то сообщила», а четыре обещания:
 * <ul>
 *   <li><b>без отладчика ничего не меняется</b>: запуск не считает себя отлаживаемым
 *       и работает ровно как раньше;</li>
 *   <li><b>останов случается перед инструкцией</b>, и в кадре видно то, что к этому
 *       моменту действительно посчитано, — не больше и не меньше;</li>
 *   <li><b>шаг ведёт себя как шаг</b>: {@code over} проходит вызов целиком,
 *       {@code into} входит в него, {@code out} возвращается к вызывающему;</li>
 *   <li><b>пауза не расходует таймаут</b>: скрипт, простоявший дольше отведённого ему
 *       времени, не падает по сторожу.</li>
 * </ul>
 * Скрипт всюду выполняется в отдельном потоке, а команды идут из потока теста:
 * иначе отладчик и отлаживаемый оказались бы одним потоком, и первая же остановка
 * заблокировала бы сам тест.
 */
class DebuggerTest {

    /** Заведомо больше любой честной задержки и заведомо меньше вечности. */
    private static final long PATIENCE_MS = 5000;

    /** Запущенный скрипт: сессия, поток выполнения и очередь остановок. */
    private static final class Fixture implements AutoCloseable {

        private final DebugSession session = new DebugSession();
        private final BlockingQueue<SuspendedEvent> stops = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final StringBuilder printed = new StringBuilder();
        private final Source source;
        private final ExecutionContext context;
        private final Thread runner;
        private volatile Execution done;

        Fixture(String code, Limits limits) {
            this.source = Source.ofString(code);
            this.context = ExecutionContext.fresh(printed::append).withLimits(limits);
            session.listener(new DebugListener() {
                @Override
                public void suspended(SuspendedEvent event) {
                    stops.add(event);
                }

                @Override
                public void resumed(long threadId) {
                }
            });
            session.attach(context);
            Diagnostics diagnostics = new Diagnostics(source);
            Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
            assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
            this.runner = new Thread(() -> {
                try {
                    done = new Interpreter().run(Unit.of(source, program), context);
                } catch (Throwable failed) {
                    failure.set(failed);
                }
            }, "script");
            runner.setDaemon(true);
        }

        void start() {
            runner.start();
        }

        /** Смещение начала строки — тем же способом, каким его посчитает редактор. */
        int offsetOfLine(int line) {
            return source.offsetOf(line, 1);
        }

        void breakAtLine(int line) {
            session.breakpoints().set(source.name(), List.of(offsetOfLine(line)));
        }

        SuspendedEvent awaitStop() throws InterruptedException {
            SuspendedEvent event = stops.poll(PATIENCE_MS, TimeUnit.MILLISECONDS);
            assertNotNull(event, "скрипт не остановился");
            return event;
        }

        void awaitFinish() throws InterruptedException {
            runner.join(PATIENCE_MS);
            assertFalse(runner.isAlive(), "скрипт не закончился");
            Throwable failed = failure.get();
            if (failed != null) {
                throw new AssertionError("скрипт упал: " + failed, failed);
            }
        }

        Value name(String name) {
            return done.scope().scope().lookup(name);
        }

        @Override
        public void close() {
            session.detach();
            context.closeRun();
            context.shutdownModules();
        }
    }

    private static Fixture script(String code) {
        return new Fixture(code, Limits.none());
    }

    private static long number(Value value) {
        assertNotNull(value, "имени нет в кадре");
        return ((IntValue) value).value();
    }

    // --- выключённая отладка -------------------------------------------------

    @Test
    @DisplayName("Без отладчика запуск не считает себя отлаживаемым")
    void offByDefault() {
        ExecutionContext context = ExecutionContext.fresh();
        assertFalse(context.run().debugging(), "отладка включена, хотя её не просили");
        assertEquals(Debugger.off(), context.debugger(), "приёмник не выключённый");
    }

    @Test
    @DisplayName("Отключённая сессия отпускает запуск: точки больше не срабатывают")
    void detachStopsHitting() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            try (Fixture fixture = script("total = 0\nfor (i in 1..5) {\ntotal = total + i\n}\n")) {
                fixture.breakAtLine(3);
                fixture.session.detach();
                fixture.start();
                fixture.awaitFinish();
                assertEquals(15, number(fixture.name("total")));
                assertTrue(fixture.stops.isEmpty(), "остановился, хотя сессия отключена");
            }
        });
    }

    // --- останов на инструкции -----------------------------------------------

    @Test
    @DisplayName("Точка останова срабатывает перед инструкцией, а не после неё")
    void stopsBeforeStatement() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            try (Fixture fixture = script("a = 1\nb = 2\nc = a + b\n")) {
                fixture.breakAtLine(3);
                fixture.start();

                SuspendedEvent stop = fixture.awaitStop();
                assertEquals(StopReason.BREAKPOINT, stop.reason());
                DebugFrame frame = stop.top();
                assertEquals(3, frame.line(), "встали не на той строке");
                assertEquals(1, number(frame.value("a")));
                assertEquals(2, number(frame.value("b")));
                assertNull(frame.value("c"), "инструкция уже выполнена — значит встали после неё");

                fixture.session.resumeAll();
                fixture.awaitFinish();
                assertEquals(3, number(fixture.name("c")));
            }
        });
    }

    @Test
    @DisplayName("Точка в теле цикла срабатывает на каждой итерации")
    void stopsEveryIteration() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            try (Fixture fixture = script("total = 0\nfor (i in 1..3) {\ntotal = total + i\n}\n")) {
                fixture.breakAtLine(3);
                fixture.start();
                for (int i = 0; i < 3; i++) {
                    SuspendedEvent stop = fixture.awaitStop();
                    assertEquals(3, stop.top().line());
                    fixture.session.resumeAll();
                }
                fixture.awaitFinish();
                assertEquals(6, number(fixture.name("total")));
            }
        });
    }

    @Test
    @DisplayName("В кадре функции видны её параметры, а в кадре вызывающего — его переменные")
    void framesCarryTheirOwnScopes() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            String code = """
                    def total(price, count) {
                    sum = price * count
                    return sum;
                    }
                    outer = 7
                    answer = total(120, 3)
                    """;
            try (Fixture fixture = script(code)) {
                fixture.breakAtLine(3);
                fixture.start();

                SuspendedEvent stop = fixture.awaitStop();
                List<DebugFrame> frames = stop.frames();
                assertEquals(2, frames.size(), "ожидались кадр функции и верхний уровень");

                DebugFrame inner = frames.get(0);
                assertEquals("total", inner.function());
                assertEquals(120, number(inner.value("price")));
                assertEquals(360, number(inner.value("sum")));

                DebugFrame outer = frames.get(1);
                assertEquals(0, outer.depth(), "верхний уровень файла — нулевая глубина");
                assertEquals(7, number(outer.value("outer")));
                assertNull(outer.value("sum"), "локальная функции видна снаружи");

                fixture.session.resumeAll();
                fixture.awaitFinish();
                assertEquals(360, number(fixture.name("answer")));
            }
        });
    }

    // --- шаги ----------------------------------------------------------------

    @Test
    @DisplayName("Шаг over проходит вызов целиком и встаёт на следующей строке")
    void stepOverPassesTheCall() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            String code = """
                    def twice(x) {
                    return x * 2;
                    }
                    a = twice(4)
                    b = a + 1
                    """;
            try (Fixture fixture = script(code)) {
                fixture.breakAtLine(4);
                fixture.start();

                SuspendedEvent stop = fixture.awaitStop();
                assertEquals(4, stop.top().line());
                fixture.session.breakpoints().clear();
                fixture.session.step(stop.thread().id(), StepMode.OVER);

                SuspendedEvent next = fixture.awaitStop();
                assertEquals(StopReason.STEP, next.reason());
                assertEquals(5, next.top().line(), "over зашёл внутрь вызова");
                assertEquals(8, number(next.top().value("a")));

                fixture.session.resumeAll();
                fixture.awaitFinish();
            }
        });
    }

    @Test
    @DisplayName("Шаг into входит в тело вызванной функции")
    void stepIntoEntersTheBody() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            String code = """
                    def twice(x) {
                    return x * 2;
                    }
                    a = twice(4)
                    b = a + 1
                    """;
            try (Fixture fixture = script(code)) {
                fixture.breakAtLine(4);
                fixture.start();

                SuspendedEvent stop = fixture.awaitStop();
                fixture.session.breakpoints().clear();
                fixture.session.step(stop.thread().id(), StepMode.INTO);

                SuspendedEvent inside = fixture.awaitStop();
                assertEquals(StopReason.STEP, inside.reason());
                assertEquals("twice", inside.top().function(), "into не вошёл в вызов");
                assertEquals(4, number(inside.top().value("x")));

                fixture.session.resumeAll();
                fixture.awaitFinish();
            }
        });
    }

    @Test
    @DisplayName("Шаг out возвращается к вызывающему")
    void stepOutReturnsToCaller() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            String code = """
                    def twice(x) {
                    y = x * 2
                    return y;
                    }
                    a = twice(4)
                    b = a + 1
                    """;
            try (Fixture fixture = script(code)) {
                fixture.breakAtLine(2);
                fixture.start();

                SuspendedEvent stop = fixture.awaitStop();
                assertEquals("twice", stop.top().function());
                fixture.session.breakpoints().clear();
                fixture.session.step(stop.thread().id(), StepMode.OUT);

                SuspendedEvent back = fixture.awaitStop();
                assertEquals(StopReason.STEP, back.reason());
                assertEquals(0, back.top().depth(), "out не вернулся на верхний уровень");
                assertEquals(6, back.top().line());

                fixture.session.resumeAll();
                fixture.awaitFinish();
            }
        });
    }

    // --- вычисление в кадре --------------------------------------------------

    @Test
    @DisplayName("Выражение считается в кадре остановленного потока")
    void evaluatesInFrame() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            String code = """
                    def total(price, count) {
                    sum = price * count
                    return sum;
                    }
                    answer = total(120, 3)
                    """;
            try (Fixture fixture = script(code)) {
                fixture.breakAtLine(3);
                fixture.start();

                SuspendedEvent stop = fixture.awaitStop();
                long thread = stop.thread().id();
                assertEquals(363, number(fixture.session.evaluate(thread, 1, "sum + count")));
                // Тот же поток, но другой кадр: снаружи 'sum' не существует.
                assertThrows(RuntimeException.class,
                        () -> fixture.session.evaluate(thread, 0, "sum"),
                        "имя из кадра функции видно на верхнем уровне");

                fixture.session.resumeAll();
                fixture.awaitFinish();
            }
        });
    }

    @Test
    @DisplayName("Вычисление не срывается о собственную точку останова")
    void evaluationIgnoresBreakpoints() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 2), () -> {
            String code = """
                    def twice(x) {
                    return x * 2;
                    }
                    a = 1
                    b = 2
                    """;
            try (Fixture fixture = script(code)) {
                // Точка стоит внутри функции, которую сейчас позовёт сам отладчик.
                fixture.breakAtLine(5);
                fixture.start();

                SuspendedEvent stop = fixture.awaitStop();
                fixture.session.breakpoints().set(fixture.source.name(),
                        List.of(fixture.offsetOfLine(2), fixture.offsetOfLine(5)));
                assertEquals(8, number(fixture.session.evaluate(stop.thread().id(), 0, "twice(4)")));

                fixture.session.breakpoints().clear();
                fixture.session.resumeAll();
                fixture.awaitFinish();
            }
        });
    }

    // --- часы ----------------------------------------------------------------

    @Test
    @DisplayName("Пауза не расходует таймаут запуска")
    void pauseDoesNotSpendTheClock() throws Exception {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 3), () -> {
            Fixture fixture = new Fixture("a = 1\nb = 2\nc = 3\n",
                    Limits.builder().timeout(Duration.ofMillis(300)).build());
            try {
                fixture.breakAtLine(3);
                fixture.start();

                SuspendedEvent stop = fixture.awaitStop();
                // Стоим заведомо дольше отведённого скрипту времени.
                Thread.sleep(900);
                fixture.session.resumeAll();

                fixture.awaitFinish();
                assertEquals(3, number(fixture.name("c")), "скрипт не досчитал после паузы");
                assertEquals(StopReason.BREAKPOINT, stop.reason());
            } finally {
                fixture.close();
            }
        });
    }
}
