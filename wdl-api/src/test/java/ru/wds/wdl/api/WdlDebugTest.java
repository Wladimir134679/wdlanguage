package ru.wds.wdl.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.debug.DebugFrame;
import ru.wds.wdl.debug.DebugListener;
import ru.wds.wdl.debug.DebugSession;
import ru.wds.wdl.debug.StopReason;
import ru.wds.wdl.debug.SuspendedEvent;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.types.BoolValue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Отладка со стороны встраивания: сессия принадлежит запуску, а не движку,
 * и заводится либо при сборке (launch), либо по первому спросу у живого
 * запуска (attach).
 * <p>
 * Само поведение отладчика — точки, шаги, кадры — проверяется в ядре
 * ({@code DebuggerTest}); здесь проверяется граница: что приложение получает
 * сессию, что она подключена к нужному запуску и что закрытие запуска не ждёт
 * человека, отошедшего от отладчика.
 */
class WdlDebugTest {

    private static final String COUNTING = """
            def sum(limit) {
                total = 0
                i = 1
                while (i <= limit) {
                    total = total + i
                    i = i + 1
                }
                return total;
            }

            print(sum(10))
            """;

    /** Смещение инструкции {@code total = total + i} — она же точка останова. */
    private static final int INSIDE_LOOP = COUNTING.indexOf("total = total + i");

    @Test
    @DisplayName("Режим launch: сессия есть до первой инструкции, точка останова срабатывает")
    void launch() throws Exception {
        Stops stops = new Stops();
        StringBuilder printed = new StringBuilder();
        WdlEngine engine = WdlEngine.builder()
                .output(printed::append)
                .debug(stops)
                .build();
        try (WdlInstance instance = engine.compile(COUNTING, "counting.wdl").instance()) {
            assertTrue(instance.debugging(), "движок собран с debug(...): сессия уже заведена");
            DebugSession session = instance.debugger();
            session.breakpoints().set("counting.wdl", List.of(INSIDE_LOOP));

            Thread script = run(instance);
            SuspendedEvent stop = stops.await();
            assertEquals(StopReason.BREAKPOINT, stop.reason());

            DebugFrame top = stop.top();
            assertNotNull(top, "остановка без кадра");
            assertEquals("sum", top.function(), "встали не в той функции");
            assertEquals(5, top.line());
            assertEquals(1L, Values.toJava(top.value("i")), "первый круг цикла");
            assertEquals(0L, Values.toJava(top.value("total")));
            assertEquals(10L, Values.toJava(session.evaluate(stop.thread().id(), top.depth(), "limit")),
                    "вычисление в кадре видит параметр функции");

            session.breakpoints().clear();
            session.resumeAll();
            script.join(5000);
            assertFalse(script.isAlive(), "скрипт не доиграл после возобновления");
            assertEquals("55", printed.toString());
        }
    }

    @Test
    @DisplayName("Режим attach: отладчик подключается к уже работающему запуску")
    void attach() throws Exception {
        AtomicBoolean alive = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        StringBuilder printed = new StringBuilder();
        WdlEngine engine = WdlEngine.builder()
                .output(printed::append)
                .defineValue("alive", BuiltinFunction.of("alive", Arity.exactly(0),
                        (context, arguments, span) -> {
                            started.countDown();
                            return BoolValue.of(alive.get());
                        }))
                .build();
        String code = """
                rounds = 0
                while (alive()) {
                    rounds = rounds + 1
                }
                print("готово")
                """;
        try (WdlInstance instance = engine.compile(code, "spin.wdl").instance()) {
            assertFalse(instance.debugging(), "без debug(...) сессии быть не должно");

            Thread script = run(instance);
            assertTrue(started.await(5, TimeUnit.SECONDS), "скрипт не начал считать");

            // Вот оно, подключение к живому запуску: скрипт уже крутится.
            DebugSession session = instance.debugger();
            Stops stops = new Stops();
            session.listener(stops);
            session.pause();

            SuspendedEvent stop = stops.await();
            assertEquals(StopReason.PAUSE, stop.reason());
            assertNotNull(stop.top(), "остановка без кадра");
            assertTrue(session.isSuspended(stop.thread().id()));

            alive.set(false);
            session.resumeAll();
            script.join(5000);
            assertFalse(script.isAlive(), "скрипт не доиграл после возобновления");
            assertEquals("готово", printed.toString());
            assertTrue((Long) instance.get("rounds") > 0, "скрипт не сделал ни круга");
        }
    }

    @Test
    @DisplayName("Закрытие запуска отпускает поток, стоящий на точке останова")
    void closeReleasesSuspended() throws Exception {
        Stops stops = new Stops();
        WdlEngine engine = WdlEngine.builder().debug(stops).build();
        WdlInstance instance = engine.compile(COUNTING, "counting.wdl").instance();
        instance.debugger().breakpoints().set("counting.wdl", List.of(INSIDE_LOOP));

        Thread script = run(instance);
        stops.await();
        // Пять секунд — это то, сколько close() ждёт потоки скрипта; отпустить
        // стоящего он обязан раньше, чем начнёт ждать.
        assertTimeoutPreemptively(Duration.ofSeconds(5), instance::close,
                "закрытие запуска ждало отладчика");
        script.join(5000);
        assertFalse(script.isAlive(), "поток остался стоять после закрытия запуска");
    }

    /** Запускает скрипт отдельным потоком: тот, кто отлаживает, стоять не должен. */
    private static Thread run(WdlInstance instance) {
        Thread script = new Thread(() -> {
            try {
                instance.execute();
            } catch (RuntimeException stopped) {
                // Закрытие запуска посреди работы — законный конец этого потока.
            }
        }, "script");
        script.start();
        return script;
    }

    /** Слушатель, который копит остановки и умеет подождать первую. */
    private static final class Stops implements DebugListener {

        private final List<SuspendedEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch first = new CountDownLatch(1);

        @Override
        public void suspended(SuspendedEvent event) {
            events.add(event);
            first.countDown();
        }

        @Override
        public void resumed(long threadId) {
        }

        SuspendedEvent await() throws InterruptedException {
            assertTrue(first.await(5, TimeUnit.SECONDS), "скрипт не встал");
            return events.get(0);
        }
    }
}
