package ru.wds.wdl.stdlib.thread;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.Library;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.FatalError;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * Модуль {@code sys.thread}: потоки скрипта.
 *
 * <pre>{@code
 * import sys.thread as th
 *
 * t = th.spawn("worker", def () => heavy(data))
 * println(t.name, " работает: ", t.alive)
 * println("итог: ", t.join())
 *
 * th.sleep(250)
 * println("я — ", th.current().name)
 * }</pre>
 *
 * <h2>Три решения, которые видно снаружи</h2>
 * <b>Ошибка потока не теряется.</b> Она ложится в {@code t.error} строкой и, если поток
 * никто не ждёт, печатается в вывод запуска с именем потока. Раньше колбэки сокета
 * и моста GUI делали {@code printStackTrace} в {@code System.err} — то есть мимо того
 * вывода, который задало приложение, и мимо любого лога.
 * <p>
 * <b>Поток регистрируется в запуске</b> ({@code CallContext.threads()}), а не заводится
 * сырым {@code new Thread}. Иначе он переживает {@code close()} и зовёт функцию скрипта
 * по закрытым модулям.
 * <p>
 * <b>Поток — демон, и признак этот не меняется.</b> Спор «кто главнее, процесс или поток
 * скрипта» для встроенного движка решён в пользу процесса: мод не вправе не дать игре
 * закрыться. Дождаться работы — явное дело автора, и делается оно {@code t.join()}.
 * Отдельного {@code t.daemon = ...} нет намеренно: {@code spawn} отдаёт уже запущенный
 * поток, а после старта Java этот признак менять не даёт — свойство, которое присваивается
 * и молча ничего не делает, хуже отсутствующего.
 *
 * <h2>Прерывание сильнее скрипта</h2>
 * {@code t.interrupt()} и {@code th.sleep} отвечают остановкой выполнения
 * ({@code FatalError}), а не ошибкой языка: {@code while (true) { try { ... } catch (e) {} }}
 * поймал бы ошибку и продолжил работу — ровно то, ради чего прерывание и звали.
 */
public final class Threads implements Library {

    /** Имя класса потока в области модуля. */
    private static final String THREAD = "Thread";

    /**
     * Потолок размера пула.
     * <p>
     * Не «столько хватит всем», а защита от опечатки: {@code th.pool(10000)} — это
     * не пул, а способ положить процесс, и лучше сказать об этом на месте создания.
     */
    private static final int MAX_POOL_SIZE = 512;

    /** Пулы этого запуска — их же закрывать, если скрипт забыл. */
    private final List<ExecutorService> pools = new CopyOnWriteArrayList<>();

    private Threads() {
    }

    /** Фабрика для реестра встроенных модулей. */
    public static Library library() {
        return new Threads();
    }

    @Override
    public String name() {
        return "sys/thread";
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");
        NativeClass threadClass = Types.in(scope, THREAD, NativeClass.class, Threads::threadClass);
        scope.define(THREAD, threadClass);
        installPool(scope);
        installPrimitives(scope);

        // spawn(fn) и spawn(name, fn): имя — часть диагностики, поэтому оно первым
        // аргументом и необязательно, а не полем в объекте настроек.
        scope.define("spawn", BuiltinFunction.of("spawn", Arity.between(1, 2),
                (context, arguments, span) -> {
                    boolean named = arguments.size() > 1;
                    String title = named ? arguments.string(0, "имя потока") : null;
                    Callback body = arguments.callback(named ? 1 : 0, "функция потока");
                    return spawn(threadClass, context, span, title, body);
                }));

        scope.define("current", BuiltinFunction.of("current", Arity.exactly(0),
                (context, arguments, span) -> describe(Thread.currentThread())));

        scope.define("sleep", BuiltinFunction.of("sleep", Arity.exactly(1),
                (context, arguments, span) -> {
                    long millis = arguments.integer(0, "миллисекунды");
                    if (millis < 0) {
                        throw arguments.bad(0, "миллисекунды", "ожидалось неотрицательное число");
                    }
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                    return NullValue.NULL;
                }));

        // yield — подсказка планировщику, а не гарантия; на корректность скрипта
        // влиять не должна и потому ничего не возвращает.
        scope.define("yield", BuiltinFunction.of("yield", Arity.exactly(0),
                (context, arguments, span) -> {
                    Thread.yield();
                    return NullValue.NULL;
                }));

        return scope;
    }

    /**
     * Пул и его обещания: {@code th.pool(n)}, классы {@code Pool} и {@code Future}.
     * <p>
     * Классы кладутся в область модуля рядом с фабрикой, а не прячутся: {@code f is Future}
     * — законный вопрос, и ответить на него можно только имеющимся именем.
     */
    private void installPool(Environment scope) {
        NativeClass futureClass = Types.in(scope, "Future", NativeClass.class, Pool::futureClass);
        NativeClass poolClass = Types.in(scope, "Pool", NativeClass.class, () -> Pool.poolClass(scope, futureClass));
        scope.define("Future", futureClass);
        scope.define("Pool", poolClass);

        scope.define("pool", BuiltinFunction.of("pool", Arity.between(0, 1),
                (context, arguments, span) -> {
                    // По числу процессоров, если не сказано иное: пул на 4 потока
                    // на 16-ядерной машине — это не «безопасно», а «в четыре раза
                    // медленнее без причины».
                    long size = arguments.integer(0, "число потоков",
                            Runtime.getRuntime().availableProcessors());
                    if (size < 1 || size > MAX_POOL_SIZE) {
                        throw arguments.bad(0, "число потоков",
                                "ожидалось от 1 до " + MAX_POOL_SIZE);
                    }
                    return Pool.create(poolClass, (int) size, pools);
                }));
    }

    /** Замок, счётчик, канал и защёлка — всё, что даёт значение, а не ключевое слово. */
    private static void installPrimitives(Environment scope) {
        NativeClass lockClass = Types.in(scope, "Lock", NativeClass.class, Sync::lockClass);
        NativeClass counterClass = Types.in(scope, "Counter", NativeClass.class, Sync::counterClass);
        NativeClass channelClass = Types.in(scope, "Channel", NativeClass.class, Sync::channelClass);
        NativeClass latchClass = Types.in(scope, "Latch", NativeClass.class, Sync::latchClass);
        scope.define("Lock", lockClass);
        scope.define("Counter", counterClass);
        scope.define("Channel", channelClass);
        scope.define("Latch", latchClass);

        scope.define("lock", BuiltinFunction.of("lock", Arity.exactly(0),
                (context, arguments, span) -> Sync.newLock(lockClass)));

        scope.define("counter", BuiltinFunction.of("counter", Arity.between(0, 1),
                (context, arguments, span) ->
                        Sync.newCounter(counterClass, arguments.integer(0, "начальное значение", 0))));

        scope.define("channel", BuiltinFunction.of("channel", Arity.between(0, 1),
                (context, arguments, span) -> {
                    // Без размера канал не ограничен: продюсер, которому некуда положить,
                    // — отдельное решение автора, а не поведение по умолчанию.
                    long capacity = arguments.integer(0, "вместимость", Integer.MAX_VALUE);
                    if (capacity < 1) {
                        throw arguments.bad(0, "вместимость", "ожидалось положительное число");
                    }
                    return Sync.newChannel(channelClass, (int) Math.min(capacity, Integer.MAX_VALUE));
                }));

        scope.define("latch", BuiltinFunction.of("latch", Arity.exactly(1),
                (context, arguments, span) -> {
                    long count = arguments.integer(0, "число событий");
                    if (count < 0 || count > Integer.MAX_VALUE) {
                        throw arguments.bad(0, "число событий", "ожидалось неотрицательное число");
                    }
                    return Sync.newLatch(latchClass, (int) count);
                }));
    }

    /**
     * Закрывает пулы, которые скрипт не закрыл сам.
     * <p>
     * {@code Pool} подмешивает {@code Closeable} и работает с {@code use}, но
     * рассчитывать на дисциплину автора здесь нельзя: забытый пул — это живые потоки,
     * переживающие запуск. Поэтому пулы записываются при создании и закрываются вместе
     * с модулем — {@code close()} у пула идемпотентен, так что двойное закрытие
     * безобидно.
     */
    @Override
    public void close() {
        pools.forEach(ExecutorService::shutdownNow);
        pools.clear();
    }

    /** Текущий поток описанием: имя и номер. Обычный объект — методов у него нет. */
    private static MapValue describe(Thread thread) {
        MapValue described = new MapValue();
        described.put("name", StringValue.of(thread.getName()));
        described.put("id", IntValue.of(thread.threadId()));
        return described;
    }

    /**
     * Заводит поток скрипта.
     * <p>
     * Порядок здесь важен: состояние создаётся первым, поток вторым, значение третьим.
     * {@code start} отдаёт уже <b>запущенный</b> поток, поэтому его тело обязано уметь
     * работать до того, как значение появится, — общим у них остаётся состояние.
     */
    private static Value spawn(NativeClass threadClass, CallContext context, Span span,
                               String title, Callback body) {
        ThreadHandle.State state = new ThreadHandle.State();
        Thread thread = context.threads().start(title, () -> run(state, context, body));
        ThreadHandle handle = new ThreadHandle(threadClass, thread, state);
        handle.put("name", StringValue.of(thread.getName()));
        handle.put("id", IntValue.of(thread.threadId()));
        return handle;
    }

    /**
     * Тело потока: позвать функцию скрипта и не потерять то, чем она кончилась.
     * <p>
     * Ловится всё, включая ошибки движка: поток, умерший молча, — это исчезнувшая
     * работа без единой строки объяснения, и никакой {@code Thread.UncaughtExceptionHandler}
     * не расскажет о ней в тот вывод, который задало приложение.
     */
    private static void run(ThreadHandle.State state, CallContext context, Callback body) {
        try {
            state.succeeded(body.call());
        } catch (WdlError error) {
            finish(state, context, describeError(error), requested(error));
        } catch (RuntimeException | LinkageError failure) {
            finish(state, context, describeJava(failure), false);
        }
    }

    /**
     * Записывает, чем кончился поток, и решает, говорить ли об этом вслух.
     *
     * @param quiet остановка, о которой просили: печатать её — значит ругаться
     *              на собственное {@code t.interrupt()}
     */
    private static void finish(ThreadHandle.State state, CallContext context, String message,
                               boolean quiet) {
        state.failed(message);
        if (quiet || state.isAwaited()) {
            // Ждущий заберёт ошибку сам — вторая копия в выводе была бы шумом.
            return;
        }
        // Не ждёт никто — значит, это последний момент, когда об ошибке можно сказать.
        context.write("поток '" + Thread.currentThread().getName() + "': " + message
                + System.lineSeparator());
    }

    /**
     * Остановка ли это по просьбе снаружи.
     * <p>
     * Признак прерывания к этому моменту восстановлен теми, кто его снимал
     * ({@code th.sleep}, {@code join}), поэтому вопрос решается одним взглядом
     * на поток. {@code t.error} при этом всё равно заполняется — по нему видно,
     * почему поток кончился, — но в вывод не идёт.
     */
    private static boolean requested(WdlError error) {
        return error instanceof FatalError && Thread.currentThread().isInterrupted();
    }

    private static String describeError(WdlError error) {
        String kind = error.kindName();
        return kind == null ? error.getMessage() : kind + ": " + error.getMessage();
    }

    private static String describeJava(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /**
     * Класс потока: {@code join} и {@code interrupt} методами, остальное — полями
     * (см. {@link ThreadHandle}).
     */
    private static NativeClass threadClass() {
        return NativeClass.named(THREAD)
                .field("name", StringValue.of(""))
                .field("id", IntValue.of(0))

                // join() ждёт до конца, join(ms) — сколько сказано и отвечает null,
                // если не дождался: у «не успел» и «вернул null» разные последствия,
                // и различить их автор скрипта обязан по t.alive.
                .method("join", Arity.between(0, 1), (self, context, args, span) -> {
                    ThreadHandle handle = handle(self, span);
                    handle.shared().markAwaited();
                    long millis = args.integer(0, "миллисекунды", 0);
                    if (millis < 0) {
                        throw args.bad(0, "миллисекунды", "ожидалось неотрицательное число");
                    }
                    try {
                        handle.thread().join(millis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                    return handle.thread().isAlive() ? NullValue.NULL : handle.resultValue();
                })

                .method("interrupt", Arity.exactly(0), (self, context, args, span) -> {
                    handle(self, span).thread().interrupt();
                    return self;
                })

                .build();
    }

    private static ThreadHandle handle(NativeInstance self, Span span) {
        if (self instanceof ThreadHandle handle) {
            return handle;
        }
        // Попасть сюда можно только собрав экземпляр в обход spawn: 'new th.Thread()'
        // даёт обычный экземпляр, за которым потока нет.
        throw new WdlRuntimeError(span,
                "за этим значением нет потока: поток заводится вызовом th.spawn");
    }
}
