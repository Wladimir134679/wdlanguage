package ru.wds.wdl.runtime;

import ru.wds.wdl.value.ScriptThreads;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Потоки одного запуска: кто их завёл, кто их остановит.
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
 */
final class RunThreads implements ScriptThreads {

    /** Живые потоки. Множество, а не список: снимаются они в произвольном порядке. */
    private final Set<Thread> live = ConcurrentHashMap.newKeySet();
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
        String title = name != null && !name.isBlank()
                ? name
                : "wdl-" + counter.incrementAndGet();
        Thread thread = new Thread(null, () -> {
            try {
                body.run();
            } finally {
                live.remove(Thread.currentThread());
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
            throw failed;
        }
        return thread;
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
        for (Thread thread : live) {
            thread.interrupt();
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        List<String> stubborn = new ArrayList<>();
        for (Thread thread : live) {
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
        return live.size();
    }
}
