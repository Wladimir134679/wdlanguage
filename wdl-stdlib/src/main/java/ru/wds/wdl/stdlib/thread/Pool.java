package ru.wds.wdl.stdlib.thread;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.FatalError;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ScriptThreads;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Пул потоков и его обещания: {@code th.pool(4)}, {@code pool.submit}, {@code pool.map}.
 *
 * <pre>{@code
 * use (pool = th.pool(4)) {
 *     results = pool.map(urls, def (url) => fetch(url))
 * }
 * }</pre>
 *
 * <h2>Пул — ресурс, и язык об этом знает</h2>
 * Класс подмешивает {@code Closeable}, поэтому работает с {@code use} и {@code defer}:
 * пул держит живые потоки, и забыть его закрыть — обычная ошибка, которую конструкция
 * языка делает невозможной. Сверх того пул записан в библиотеку модуля: даже забытый
 * скриптом, он закроется вместе с запуском.
 *
 * <h2>Ошибка задачи не теряется</h2>
 * Правило то же, что у {@code th.spawn}: ошибку забирает {@code f.get()}, и там она
 * остаётся <b>ошибкой языка</b> — с классом, местом и возможностью поймать её
 * обработчиком. Если {@code get} никто не позвал, ошибка печатается в вывод запуска
 * с именем задачи — молча терять работу нельзя.
 */
final class Pool {

    /** Имя трейта, который прелюдия кладёт в корневую область. */
    private static final String CLOSEABLE = "Closeable";

    private Pool() {
    }

    /**
     * Класс пула для этого запуска.
     *
     * @param futureClass класс {@code Future}: пул его создаёт, поэтому получает готовым
     */
    static NativeClass poolClass(Environment scope, NativeClass futureClass) {
        NativeClass.Builder builder = NativeClass.named("Pool")
                .field("size", IntValue.of(0))

                .method("submit", Arity.exactly(1), (self, context, args, span) ->
                        submit(futureClass, executor(self, span), context,
                                args.callback(0, "задача")))

                .method("map", Arity.exactly(2), (self, context, args, span) ->
                        map(executor(self, span), args.array(0, "значения"),
                                args.callback(1, "функция"), span))

                .method("close", Arity.exactly(0), (self, context, args, span) -> {
                    Running running = self.state(Running.class);
                    if (running != null) {
                        shutdown(running);
                        self.state((Object) null);
                    }
                    return NullValue.NULL;
                });

        // Трейт берётся из области, а не из статики: Closeable объявлен прелюдией
        // и принадлежит запуску — тот же приём, что у потоков sys.io.
        if (scope.lookup(CLOSEABLE) instanceof TraitValue trait) {
            builder.with(trait);
        }
        return builder.build();
    }

    /**
     * Класс обещания результата: {@code get}, {@code cancel} и вычисляемые поля.
     * <p>
     * Создаётся только пулом — своего конструктора у него нет по смыслу: обещание
     * без задачи, которая его выполнит, ничего не значит.
     */
    static NativeClass futureClass() {
        return NativeClass.named("Future")

                .method("get", Arity.between(0, 1), (self, context, args, span) -> {
                    FutureHandle handle = future(self, span);
                    handle.shared().markAwaited();
                    long millis = args.integer(0, "миллисекунды", 0);
                    if (millis < 0) {
                        throw args.bad(0, "миллисекунды", "ожидалось неотрицательное число");
                    }
                    return await(handle.task(), millis, args.has(0), span);
                })

                // Отвечает, удалось ли: задача, которая уже кончилась, не отменяется,
                // и знать об этом вызывающему полезнее, чем не знать.
                .method("cancel", Arity.exactly(0), (self, context, args, span) ->
                        ru.wds.wdl.value.types.BoolValue.of(future(self, span).task().cancel(true)))

                .build();
    }

    private static FutureHandle future(NativeInstance self, Span span) {
        if (self instanceof FutureHandle handle) {
            return handle;
        }
        throw new WdlRuntimeError(span,
                "за этим значением нет задачи: обещание отдаёт pool.submit");
    }

    /**
     * Открытый пул: его исполнитель и занятое им место в квоте потоков запуска.
     * <p>
     * Двух вещей, а не одной, потому что закрытие пула — это оба действия сразу:
     * остановить потоки и вернуть их место. Пул, потоки которого остановлены,
     * но место осталось занятым, тихо съедал бы квоту до конца запуска.
     */
    record Running(ExecutorService service, ScriptThreads.Quota quota) {
    }

    /**
     * Создаёт пул на заданное число потоков.
     * <p>
     * Потоки берутся у запуска ({@code CallContext.threads()}), а не у своей
     * {@code ThreadFactory}, и это то же правило, что у {@code th.spawn}: поток,
     * о котором запуск не знает, переживает закрытие и не считается в квоте.
     * Пул занимает место целиком в момент создания — по числу потоков, которое он
     * вправе держать, а не по числу заведённых.
     *
     * @throws ScriptThreads.LimitExceeded если квота потоков запуска исчерпана;
     *                                     переводит её в ошибку скрипта вызывающий,
     *                                     которому известно место в исходнике
     */
    static Value create(NativeClass poolClass, int size, CallContext context,
                        List<Running> opened) {
        ScriptThreads.Quota quota = context.threads().reserve("wdl-pool", size);
        ExecutorService service;
        try {
            service = Executors.newFixedThreadPool(size, quota.factory());
        } catch (RuntimeException | Error failed) {
            // Место занято, а пула нет — вернуть его надо здесь: другого владельца
            // у этой квоты уже не появится.
            quota.close();
            throw failed;
        }
        Running running = new Running(service, quota);
        opened.add(running);
        NativeInstance instance = new NativeInstance(poolClass);
        instance.put("size", IntValue.of(size));
        instance.state(running);
        return instance;
    }

    /** Останавливает потоки пула и возвращает занятое им место. Идемпотентно. */
    static void shutdown(Running running) {
        running.service().shutdownNow();
        running.quota().close();
    }

    private static ExecutorService executor(NativeInstance self, Span span) {
        Running running = self.state(Running.class);
        if (running == null) {
            throw new WdlRuntimeError(span, "пул закрыт: задачи в него больше не принимаются");
        }
        return running.service();
    }

    private static Value submit(NativeClass futureClass, ExecutorService service,
                                CallContext context, Callback body) {
        ThreadHandle.State state = new ThreadHandle.State();
        Future<Value> task = service.submit(() -> run(state, context, body));
        return new FutureHandle(futureClass, task, state);
    }

    /**
     * Тело задачи: как у потока — не потерять то, чем она кончилась.
     * <p>
     * Ошибка летит наружу (её заберёт {@code get}) и <b>заодно</b> записывается
     * в состояние: по нему решается, печатать ли её, если {@code get} так и не позвали.
     */
    private static Value run(ThreadHandle.State state, CallContext context, Callback body) {
        try {
            return body.call();
        } catch (WdlError error) {
            report(state, context, error);
            throw error;
        } catch (RuntimeException | LinkageError failure) {
            state.failed(failure.getClass().getSimpleName());
            if (!state.isAwaited()) {
                context.write("задача пула: " + failure + System.lineSeparator());
            }
            throw failure;
        }
    }

    private static void report(ThreadHandle.State state, CallContext context, WdlError error) {
        String kind = error.kindName();
        state.failed(kind == null ? error.getMessage() : kind + ": " + error.getMessage());
        boolean requested = error instanceof FatalError && Thread.currentThread().isInterrupted();
        if (!requested && !state.isAwaited()) {
            // Никто не спросил результат — значит, это последний момент сказать об ошибке.
            context.write("задача пула: " + error.getMessage() + System.lineSeparator());
        }
    }

    /**
     * Обрабатывает все значения и ждёт всех.
     * <p>
     * Порядок результатов — порядок входа, а не порядок завершения: {@code map}
     * обещает соответствие элементу, и «кто раньше кончил» к делу не относится.
     * Первая же ошибка отменяет остальные задачи и летит наружу: продолжать работу
     * ради результата, который всё равно не сложится, незачем.
     */
    private static Value map(ExecutorService service, ArrayValue items, Callback body, Span span) {
        List<Value> source = items.items();
        List<Future<Value>> tasks = new ArrayList<>(source.size());
        for (Value item : source) {
            tasks.add(service.submit(() -> body.call(item)));
        }
        List<Value> results = new ArrayList<>(source.size());
        try {
            for (Future<Value> task : tasks) {
                results.add(await(task, 0, false, span));
            }
        } catch (RuntimeException failed) {
            tasks.forEach(task -> task.cancel(true));
            throw failed;
        }
        return ArrayValue.of(results);
    }

    /**
     * Ждёт задачу и переводит её конец в термины языка.
     *
     * @param timed есть ли предел ожидания; без него ждём до конца
     * @return результат или {@code null}-значение, если ждать перестали
     */
    static Value await(Future<Value> task, long millis, boolean timed, Span span) {
        try {
            Value value = timed ? task.get(millis, TimeUnit.MILLISECONDS) : task.get();
            return value == null ? NullValue.NULL : value;
        } catch (TimeoutException notYet) {
            // Не ошибка: спрашивали «успеет ли», ответ — «нет». Задача продолжает работу.
            return NullValue.NULL;
        } catch (CancellationException cancelled) {
            return NullValue.NULL;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw FatalError.interrupted(span);
        } catch (ExecutionException failed) {
            throw unwrap(failed, span);
        }
    }

    /**
     * Ошибка задачи — ошибка языка, а не обёртка вокруг неё.
     * <p>
     * {@code ExecutionException} автору скрипта не говорит ничего: ему нужен тот же
     * {@code IndexError} с тем же местом, что он получил бы, позвав функцию напрямую.
     */
    private static RuntimeException unwrap(ExecutionException failed, Span span) {
        Throwable cause = failed.getCause();
        if (cause instanceof WdlError error) {
            return error;
        }
        if (cause instanceof RuntimeException foreign) {
            // Чужое исключение отдаётся как есть: границу «Java → скрипт» держит
            // интерпретатор, и он же завернёт его в JavaException с местом вызова.
            return foreign;
        }
        return new WdlRuntimeError(ErrorKind.RUNTIME, span,
                "задача пула не выполнилась: " + cause);
    }
}
