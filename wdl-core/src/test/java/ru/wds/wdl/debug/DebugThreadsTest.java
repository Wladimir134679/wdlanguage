package ru.wds.wdl.debug;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.types.NullValue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Остановка нескольких потоков сразу — то, ради чего отладчик вообще устроен как
 * safepoint, а не как {@code Thread.suspend}.
 * <p>
 * Проверяется главное обещание: <b>по просьбе встать останавливаются все потоки
 * скрипта</b>, каждый — на своей ближайшей безопасной точке, и после этого скрипт
 * действительно перестаёт считать. «Перестаёт считать» здесь измеряется не временем,
 * а счётчиком: встроенная функция {@code tick} считает проходы, и после остановки
 * её показания обязаны замереть.
 * <p>
 * Потоки скрипта заводятся через {@code CallContext.threads()} — тот же реестр,
 * которым пользуется {@code sys.thread}: ядро о модуле не знает, а обещание держит
 * оно, а не библиотека.
 */
class DebugThreadsTest {

    private static final long PATIENCE_MS = 5000;

    @Test
    @DisplayName("Пауза останавливает все потоки скрипта, и счёт прекращается")
    void pauseStopsEveryThread() {
        assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS * 3), () -> {
            DebugSession session = new DebugSession();
            Set<Long> stopped = ConcurrentHashMap.newKeySet();
            CountDownLatch bothStopped = new CountDownLatch(2);
            session.listener(new DebugListener() {
                @Override
                public void suspended(SuspendedEvent event) {
                    if (stopped.add(event.thread().id())) {
                        bothStopped.countDown();
                    }
                }

                @Override
                public void resumed(long threadId) {
                }
            });

            AtomicLong ticks = new AtomicLong();
            Set<Long> working = ConcurrentHashMap.newKeySet();
            CountDownLatch bothWorking = new CountDownLatch(2);
            ExecutionContext context = ExecutionContext.fresh();
            context.scope().define("tick", BuiltinFunction.of("tick", Arity.exactly(0),
                    (call, arguments, span) -> {
                        ticks.incrementAndGet();
                        if (working.add(Thread.currentThread().threadId())) {
                            bothWorking.countDown();
                        }
                        return NullValue.NULL;
                    }));
            // Поток скрипта — через реестр запуска, как это делает sys.thread.
            context.scope().define("spawn", BuiltinFunction.of("spawn", Arity.exactly(1),
                    (call, arguments, span) -> {
                        Callback body = Callback.of((FunctionValue) arguments.get(0), call, span);
                        call.threads().start("worker", body::call);
                        return NullValue.NULL;
                    }));
            session.attach(context);

            String code = """
                    def worker() {
                    while (true) {
                    tick()
                    }
                    }
                    spawn(worker)
                    while (true) {
                    tick()
                    }
                    """;
            Source source = Source.ofString(code);
            Diagnostics diagnostics = new Diagnostics(source);
            Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
            assertFalse(diagnostics.hasErrors(), () -> diagnostics.renderAll());

            Thread main = new Thread(() -> {
                try {
                    new Interpreter().run(Unit.of(source, program), context);
                } catch (RuntimeException expected) {
                    // Вечный цикл кончится остановкой выполнения при закрытии — так и надо.
                }
            }, "script");
            main.setDaemon(true);
            main.start();

            try {
                assertTrue(bothWorking.await(PATIENCE_MS, TimeUnit.MILLISECONDS),
                        "оба потока не успели заработать");
                session.pause();
                assertTrue(bothStopped.await(PATIENCE_MS, TimeUnit.MILLISECONDS),
                        "встали не оба потока: " + session.threads());

                long afterStop = ticks.get();
                Thread.sleep(200);
                assertEquals(afterStop, ticks.get(), "скрипт продолжает считать на паузе");

                List<ThreadInfo> threads = session.threads();
                assertEquals(2, threads.size(), "потоков в сессии не два: " + threads);
                assertTrue(threads.stream().allMatch(ThreadInfo::suspended),
                        "не все потоки помечены остановленными: " + threads);

                // Возобновление отпускает обоих, и счёт возобновляется.
                session.resumeAll();
                long resumed = ticks.get();
                assertTimeoutPreemptively(Duration.ofMillis(PATIENCE_MS), () -> {
                    while (ticks.get() == resumed) {
                        Thread.sleep(5);
                    }
                });
            } finally {
                session.detach();
                context.closeRun();
                main.interrupt();
                context.stopScriptThreads(PATIENCE_MS);
                main.join(PATIENCE_MS);
                context.shutdownModules();
            }
        });
    }
}
