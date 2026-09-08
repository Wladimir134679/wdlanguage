package ru.wds.wdl.runtime;

import ru.wds.wdl.value.ScriptThreads;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Потоки одного запуска: кто их завёл, кто их остановит, сколько их можно.
 * <p>
 * Реестр существует ради одного вопроса — <b>что происходит при закрытии запуска</b>.
 * Без него ответ был «ничего»: колбэк сокета заводил сырой {@code new Thread}, никем
 * не учтённый, и после {@code close()} он просыпался и звал функцию скрипта по уже
 * закрытым модулям. Теперь закрытие прерывает потоки, ждёт их и <b>называет по именам</b>
 * тех, кто не успел, — иначе «приложение не завершается» так и осталось бы загадкой.
 *
 * <h2>Почему демоны</h2>
 * Признак демона — это ответ на вопрос «кто главнее: процесс или поток скрипта».
 * Для встроенного движка ответ однозначен: мод не вправе не дать игре закрыться,
 * а скрипт настроек — серверу. Поэтому поток скрипта не держит процесс живым,
 * а дождаться его — явное дело автора: {@code t.join()}.
 *
 * <h2>Квота</h2>
 * Здесь же считается {@link Limits#maxThreads()} — предел, без которого
 * {@code for (;;) th.spawn(...)} кладёт не скрипт, а приложение. Счёт один
 * ({@link #used}) на оба способа завести поток: одиночный {@link #start}
 * и {@linkplain #reserve место под пул}. Пул занимает своё место целиком при создании,
 * а не по факту старта потоков, — иначе {@code th.pool(1000)} проходил бы проверку,
 * пока в него не положили задачу.
 * <p>
 * Разделение множеств {@link #live} и {@link #pooled} нужно ровно за этим: потоки пула
 * прерываются и ждутся наравне с остальными, но в квоте их место уже занято резервом,
 * и считать их второй раз было бы двойным счётом.
 */
public final class RunThreads implements ScriptThreads {

    /** Живые потоки {@code th.spawn}. Множество, а не список: снимаются в произвольном порядке. */
    private final Set<Thread> live = ConcurrentHashMap.newKeySet();

    /** Живые потоки пулов: место под них уже занято резервом, счёт им отдельный. */
    private final Set<Thread> pooled = ConcurrentHashMap.newKeySet();

    /** Занятые места квоты: потоки {@code spawn} плюс размеры открытых пулов. */
    private final AtomicInteger used = new AtomicInteger();

    private final AtomicInteger counter = new AtomicInteger();
    private final Run run;

    RunThreads(Run run) {
        this.run = Objects.requireNonNull(run, "run");
    }

    @Override
    public Thread start(String name, Runnable body) {
        Objects.requireNonNull(body, "body");
        if (run.isClosed()) {
            // Заводить поток в закрытом запуске бессмысленно: первый же его вызов
            // функции скрипта упрётся в ту же проверку, только уже без внятного места.
            throw new IllegalStateException("запуск закрыт: новый поток скрипта не заводится");
        }
        take(1);
        String title = name != null && !name.isBlank()
                ? name
                : "wdl-" + counter.incrementAndGet();
        Thread thread = new Thread(null, () -> {
            try {
                body.run();
            } finally {
                live.remove(Thread.currentThread());
                // Место освобождается вместе с потоком, а не при закрытии запуска:
                // скрипт, честно дождавшийся 'join', вправе завести следующий.
                used.decrementAndGet();
            }
        }, title, STACK_SIZE);
        thread.setDaemon(true);
        // В реестр — до старта: иначе короткий поток успел бы завершиться и снять себя
        // раньше, чем его записали, и остался бы в реестре навсегда.
        live.add(thread);
        try {
            thread.start();
        } catch (RuntimeException | Error failed) {
            live.remove(thread);
            used.decrementAndGet();
            throw failed;
        }
        return thread;
    }

    @Override
    public Quota reserve(String prefix, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("размер пула должен быть положительным: " + size);
        }
        if (run.isClosed()) {
            throw new IllegalStateException("запуск закрыт: новый пул не заводится");
        }
        take(size);
        return new PoolQuota(prefix, size);
    }

    /**
     * Занимает {@code count} мест квоты или отказывает.
     * <p>
     * Циклом с {@code compareAndSet}, а не {@code addAndGet} с откатом: откат
     * на мгновение показывал бы квоту переполненной, и два потока, заводящих поток
     * одновременно, отказывали бы друг другу оба.
     */
    private void take(int count) {
        int max = run.limits().maxThreads();
        if (max <= 0) {
            used.addAndGet(count);
            return;
        }
        while (true) {
            int now = used.get();
            if (now + count > max) {
                throw new LimitExceeded("потоков скрипта разрешено " + max
                        + ", занято " + now + ", запрошено ещё " + count);
            }
            if (used.compareAndSet(now, now + count)) {
                return;
            }
        }
    }

    /**
     * Прерывает потоки запуска, не дожидаясь их.
     * <p>
     * Зовёт сторож времени: скрипт, застрявший в {@code th.sleep} или в ожидании
     * канала, до точки проверки уже не дойдёт, и достать его можно только так.
     */
    void interruptAll() {
        for (Thread thread : live) {
            thread.interrupt();
        }
        for (Thread thread : pooled) {
            thread.interrupt();
        }
    }

    /**
     * Прерывает потоки запуска и ждёт их.
     * <p>
     * Прерывание, а не остановка: {@code Thread.stop} оставляет структуры данных
     * на середине изменения, а прерывание доходит до скрипта обычной остановкой
     * выполнения — её видят циклы, вызовы функций и {@code th.sleep}, а {@code defer}
     * и {@code finally} при этом отрабатывают.
     *
     * @param timeoutMillis сколько всего ждать всех вместе
     * @return имена тех, кто не завершился, — для строки в логе; пусто, если все вышли
     */
    List<String> stopAndJoin(long timeoutMillis) {
        interruptAll();
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        List<String> stubborn = new ArrayList<>();
        List<Thread> waiting = new ArrayList<>(live);
        waiting.addAll(pooled);
        for (Thread thread : waiting) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            try {
                // Ноль для join() означает «ждать вечно», поэтому бюджет, ушедший
                // в минус, превращается в единственную короткую проверку.
                thread.join(Math.max(1, left));
            } catch (InterruptedException interrupted) {
                // Закрывающего прервали самого: дожидаться остальных уже не наше дело,
                // но признак прерывания обязан пережить эту строку.
                Thread.currentThread().interrupt();
                break;
            }
            if (thread.isAlive()) {
                stubborn.add(thread.getName());
            }
        }
        return stubborn;
    }

    /** Сколько потоков скрипта живо прямо сейчас. */
    int liveCount() {
        return live.size() + pooled.size();
    }

    /** Сколько мест квоты занято: потоки {@code spawn} плюс размеры открытых пулов. */
    int usedQuota() {
        return used.get();
    }

    /** Занятое пулом место: его фабрика потоков и освобождение при закрытии. */
    private final class PoolQuota implements Quota {

        private final String prefix;
        private final int size;
        private final AtomicInteger numbers = new AtomicInteger();
        /** Закрыть можно дважды (пул закрывают и скрипт, и библиотека) — освободить один раз. */
        private final AtomicBoolean released = new AtomicBoolean();

        private PoolQuota(String prefix, int size) {
            this.prefix = prefix == null || prefix.isBlank() ? "wdl-pool" : prefix;
            this.size = size;
        }

        @Override
        public ThreadFactory factory() {
            return body -> {
                Thread thread = new Thread(null, () -> {
                    try {
                        body.run();
                    } finally {
                        pooled.remove(Thread.currentThread());
                    }
                }, prefix + "-" + numbers.incrementAndGet(), STACK_SIZE);
                thread.setDaemon(true);
                pooled.add(thread);
                return thread;
            };
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                used.addAndGet(-size);
            }
        }
    }
}
