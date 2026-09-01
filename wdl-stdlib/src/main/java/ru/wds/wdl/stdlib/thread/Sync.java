package ru.wds.wdl.stdlib.thread;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.FatalError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Примитивы синхронизации: замок, счётчик, канал, защёлка.
 *
 * <pre>{@code
 * lock = th.lock()
 * lock.run(def () { shared = shared + [item] })
 *
 * hits = th.counter(0)
 * hits.inc()
 *
 * ch = th.channel(100)
 * ch.send("работа"); ch.take()
 *
 * ready = th.latch(3)
 * ready.countDown(); ready.await(5000)
 * }</pre>
 *
 * <h2>Зачем они, если есть {@code synchronized}</h2>
 * {@code synchronized} защищает <b>вызов функции целиком</b>, и это правильный
 * инструмент, когда защищаемое — это и есть тело функции. Когда защитить надо три
 * строки посреди длинной работы, а остальное обязано идти параллельно, замок
 * значением подходит лучше: {@code lock.run(fn)} видно там, где он стоит, и держится
 * он ровно столько, сколько занимает {@code fn}.
 * <p>
 * Счётчик — отдельно от замка не из экономии: {@code c.inc()} атомарен без единого
 * захвата, а {@code lock.run(def () { n = n + 1 })} — это захват, вызов и выход
 * из области. Разница на горячем счётчике заметная.
 *
 * <h2>Ожидание всегда прерываемо</h2>
 * {@code lock.run}, {@code ch.take}, {@code latch.await} отвечают на прерывание
 * остановкой выполнения ({@code FatalError}), а не ошибкой языка: обработчик её
 * не поймает, и остановка зациклившегося скрипта продолжает работать.
 */
final class Sync {

    private Sync() {
    }

    // --- замок ----------------------------------------------------------------

    /**
     * Замок значением: {@code lock.run(fn)} и {@code lock.tryRun(fn, ms)}.
     * <p>
     * Только эти две формы, и это решение. Пара {@code lock()}/{@code unlock()}
     * позволяла бы забыть второе — а забытый замок это не «упало», а «встало намертво
     * и непонятно где». Здесь освобождение написано один раз, в {@code finally}
     * реализации, и забыть его нельзя.
     */
    static NativeClass lockClass() {
        return NativeClass.named("Lock")

                .method("run", Arity.exactly(1), (self, context, args, span) -> {
                    ReentrantLock lock = lock(self, span);
                    try {
                        lock.lockInterruptibly();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                    try {
                        return args.callback(0, "действие").call();
                    } finally {
                        lock.unlock();
                    }
                })

                // Отвечает null, если замок занят: «не удалось» — обычный ответ,
                // а не ошибка, и именно ради него tryRun и существует.
                .method("tryRun", Arity.exactly(2), (self, context, args, span) -> {
                    ReentrantLock lock = lock(self, span);
                    long millis = args.integer(1, "миллисекунды");
                    if (millis < 0) {
                        throw args.bad(1, "миллисекунды", "ожидалось неотрицательное число");
                    }
                    boolean taken;
                    try {
                        taken = lock.tryLock(millis, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                    if (!taken) {
                        return NullValue.NULL;
                    }
                    try {
                        return args.callback(0, "действие").call();
                    } finally {
                        lock.unlock();
                    }
                })

                .build();
    }

    static Value newLock(NativeClass lockClass) {
        NativeInstance instance = new NativeInstance(lockClass);
        instance.state(new ReentrantLock());
        return instance;
    }

    private static ReentrantLock lock(NativeInstance self, Span span) {
        ReentrantLock lock = self.state(ReentrantLock.class);
        if (lock == null) {
            throw new WdlRuntimeError(span, "за этим значением нет замка: замок даёт th.lock()");
        }
        return lock;
    }

    // --- счётчик --------------------------------------------------------------

    /** Целочисленный счётчик, у которого атомарна каждая операция, а не только чтение. */
    static NativeClass counterClass() {
        return NativeClass.named("Counter")

                .method("get", Arity.exactly(0), (self, context, args, span) ->
                        IntValue.of(counter(self, span).get()))

                // Возвращает новое значение, а не прежнее: 'n = c.inc()' — самая частая
                // запись, и она должна давать то, что человек имел в виду.
                .method("inc", Arity.exactly(0), (self, context, args, span) ->
                        IntValue.of(counter(self, span).incrementAndGet()))

                .method("dec", Arity.exactly(0), (self, context, args, span) ->
                        IntValue.of(counter(self, span).decrementAndGet()))

                .method("add", Arity.exactly(1), (self, context, args, span) ->
                        IntValue.of(counter(self, span).addAndGet(args.integer(0, "слагаемое"))))

                .method("set", Arity.exactly(1), (self, context, args, span) -> {
                    counter(self, span).set(args.integer(0, "значение"));
                    return self;
                })

                // «Проверил и положил» одним шагом — то самое, что двумя обращениями
                // не выражается: между ними значение успевает измениться.
                .method("compareAndSet", Arity.exactly(2), (self, context, args, span) ->
                        BoolValue.of(counter(self, span).compareAndSet(
                                args.integer(0, "ожидаемое"), args.integer(1, "новое"))))

                .build();
    }

    static Value newCounter(NativeClass counterClass, long start) {
        NativeInstance instance = new NativeInstance(counterClass);
        instance.state(new AtomicLong(start));
        return instance;
    }

    private static AtomicLong counter(NativeInstance self, Span span) {
        AtomicLong counter = self.state(AtomicLong.class);
        if (counter == null) {
            throw new WdlRuntimeError(span,
                    "за этим значением нет счётчика: счётчик даёт th.counter()");
        }
        return counter;
    }

    // --- канал ----------------------------------------------------------------

    /**
     * Очередь между потоками: продюсер кладёт, консюмер забирает.
     * <p>
     * {@code close()} <b>будит ждущих</b> — без этого консюмер, стоящий на пустом
     * канале, не узнал бы, что продюсер закончил, и висел бы вечно. Закрытый и пустой
     * канал отвечает {@code null}: цикл {@code while ((item = ch.take()) != null)}
     * заканчивается сам собой.
     */
    static NativeClass channelClass() {
        return NativeClass.named("Channel")
                .field("capacity", IntValue.of(0))

                .method("send", Arity.exactly(1), (self, context, args, span) -> {
                    try {
                        return BoolValue.of(channel(self, span).send(args.get(0)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                })

                .method("take", Arity.exactly(0), (self, context, args, span) -> {
                    try {
                        return channel(self, span).take(0, false);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                })

                .method("poll", Arity.exactly(1), (self, context, args, span) -> {
                    long millis = args.integer(0, "миллисекунды");
                    if (millis < 0) {
                        throw args.bad(0, "миллисекунды", "ожидалось неотрицательное число");
                    }
                    try {
                        return channel(self, span).take(millis, true);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                })

                .method("size", Arity.exactly(0), (self, context, args, span) ->
                        IntValue.of(channel(self, span).size()))

                .method("close", Arity.exactly(0), (self, context, args, span) -> {
                    channel(self, span).close();
                    return NullValue.NULL;
                })

                .build();
    }

    static Value newChannel(NativeClass channelClass, int capacity) {
        NativeInstance instance = new NativeInstance(channelClass);
        instance.put("capacity", IntValue.of(capacity));
        instance.state(new ChannelState(capacity));
        return instance;
    }

    private static ChannelState channel(NativeInstance self, Span span) {
        ChannelState channel = self.state(ChannelState.class);
        if (channel == null) {
            throw new WdlRuntimeError(span, "за этим значением нет канала: канал даёт th.channel()");
        }
        return channel;
    }

    /**
     * Внутренность канала.
     * <p>
     * Своя, а не {@code ArrayBlockingQueue}, ровно из-за закрытия: очередь из
     * стандартной библиотеки закрывать не умеет, и «разбудить всех ждущих» пришлось бы
     * изображать отравленным элементом — который потом кто-нибудь получил бы как
     * значение.
     */
    static final class ChannelState {

        private final Deque<Value> items = new ArrayDeque<>();
        private final int capacity;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final Condition notFull = lock.newCondition();
        private boolean closed;

        ChannelState(int capacity) {
            this.capacity = capacity;
        }

        /** @return {@code false}, если канал закрыт и класть в него нечего */
        boolean send(Value value) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (!closed && items.size() >= capacity) {
                    notFull.await();
                }
                if (closed) {
                    return false;
                }
                items.addLast(value);
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        /**
         * @param timed есть ли предел ожидания
         * @return значение или {@code null}-значение, если канал закрыт и пуст либо
         *         ждать перестали
         */
        Value take(long millis, boolean timed) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                long left = TimeUnit.MILLISECONDS.toNanos(millis);
                while (items.isEmpty()) {
                    if (closed) {
                        return NullValue.NULL;
                    }
                    if (!timed) {
                        notEmpty.await();
                    } else if (left <= 0) {
                        return NullValue.NULL;
                    } else {
                        left = notEmpty.awaitNanos(left);
                    }
                }
                Value value = items.removeFirst();
                notFull.signal();
                return value;
            } finally {
                lock.unlock();
            }
        }

        int size() {
            lock.lock();
            try {
                return items.size();
            } finally {
                lock.unlock();
            }
        }

        /** Закрывает канал и будит всех, кто ждёт: иначе они не узнают, что работа кончилась. */
        void close() {
            lock.lock();
            try {
                closed = true;
                notEmpty.signalAll();
                notFull.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    // --- защёлка --------------------------------------------------------------

    /** Ожидание N событий: {@code latch.countDown()} и {@code latch.await(ms)}. */
    static NativeClass latchClass() {
        return NativeClass.named("Latch")
                .field("count", IntValue.of(0))

                .method("countDown", Arity.exactly(0), (self, context, args, span) -> {
                    latch(self, span).countDown();
                    return self;
                })

                .method("left", Arity.exactly(0), (self, context, args, span) ->
                        IntValue.of(latch(self, span).getCount()))

                // await() ждёт до конца, await(ms) отвечает, дождался ли: у «не успел»
                // и «дождался» разные последствия, и различить их надо по значению.
                .method("await", Arity.between(0, 1), (self, context, args, span) -> {
                    CountDownLatch latch = latch(self, span);
                    long millis = args.integer(0, "миллисекунды", 0);
                    if (millis < 0) {
                        throw args.bad(0, "миллисекунды", "ожидалось неотрицательное число");
                    }
                    try {
                        if (!args.has(0)) {
                            latch.await();
                            return BoolValue.TRUE;
                        }
                        return BoolValue.of(latch.await(millis, TimeUnit.MILLISECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw FatalError.interrupted(span);
                    }
                })

                .build();
    }

    static Value newLatch(NativeClass latchClass, int count) {
        NativeInstance instance = new NativeInstance(latchClass);
        instance.put("count", IntValue.of(count));
        instance.state(new CountDownLatch(count));
        return instance;
    }

    private static CountDownLatch latch(NativeInstance self, Span span) {
        CountDownLatch latch = self.state(CountDownLatch.class);
        if (latch == null) {
            throw new WdlRuntimeError(span, "за этим значением нет защёлки: её даёт th.latch()");
        }
        return latch;
    }
}
