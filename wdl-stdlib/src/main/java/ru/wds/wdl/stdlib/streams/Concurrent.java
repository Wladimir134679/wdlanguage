package ru.wds.wdl.stdlib.streams;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.FatalError;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ScriptThreads;
import ru.wds.wdl.value.Value;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Конкурентная стадия: {@code urls.stream().mapConcurrent(8, u => http.get(u).text)}.
 *
 * <h2>Почему явная операция, а не {@code parallel()}</h2>
 * {@code parallel()} в модели Java требует делимого источника, ассоциативного слияния
 * и отдельного разговора про порядок — то есть половины машинерии {@code Spliterator}, —
 * а выигрыш даёт на больших вычислительных объёмах, которых у скрипта обычно нет.
 * И он молча ломается о побочные эффекты, что для скриптового языка хуже, чем для Java.
 * <p>
 * Здесь названо то, ради чего конкурентность в скрипте и заводят: <b>восемь запросов
 * сразу вместо восьми подряд</b>. Число задач пишет автор, а не движок, — и пишет его
 * в той же строке, где видно, что именно распараллеливается.
 *
 * <h2>Порядок результатов — порядок входа</h2>
 * Стадия обещает соответствие элементу, и «кто раньше кончил» к делу не относится:
 * то же правило, что у {@code pool.map}. Окно в {@code width} задач держится
 * заполненным, а отдаётся всегда самая старая — поэтому одна медленная задача
 * задерживает выдачу, но не мешает работать остальным.
 *
 * <h2>Потоки берутся у запуска</h2>
 * {@code ScriptThreads.reserve} — тот же реестр, что у {@code th.pool}: квота считается,
 * закрытие запуска эти потоки останавливает, {@code new Thread} здесь нет. Место в квоте
 * занимается на всё время жизни стадии и освобождается её {@code close()} — то есть
 * терминальной операцией, которая закрывает конвейер из {@code finally}.
 */
final class Concurrent {

    private Concurrent() {
    }

    /**
     * Стадия, считающая до {@code width} элементов одновременно.
     *
     * @param width сколько задач держать в работе; квота потоков запуска занимается
     *              целиком и сразу — по числу, которое стадия вправе держать
     */
    static Source mapConcurrent(Source up, int width, Callback transform,
                                CallContext context, Span span) {
        ScriptThreads.Quota quota;
        try {
            quota = context.threads().reserve("wdl-stream", width);
        } catch (ScriptThreads.LimitExceeded exceeded) {
            // Отказ в потоке — обычная ошибка скрипта, а не остановка выполнения:
            // «потоков больше не дам» это про неудавшуюся операцию. Место в исходнике
            // знает библиотека, поэтому переводит его она — то же, что делает sys.thread.
            up.close();
            throw new WdlRuntimeError(ErrorKind.RUNTIME, span, exceeded.getMessage());
        }
        ExecutorService service;
        try {
            service = Executors.newFixedThreadPool(width, quota.factory());
        } catch (RuntimeException | Error failed) {
            // Место занято, а пула нет — вернуть его надо здесь: другого владельца
            // у этой квоты уже не появится.
            quota.close();
            up.close();
            throw failed;
        }
        return new Source() {

            private final Deque<Future<Value>> window = new ArrayDeque<>(width);
            private boolean drained;
            private boolean closed;

            @Override
            public Value next() {
                fill();
                Future<Value> ready = window.pollFirst();
                if (ready == null) {
                    // Задач больше нет и вход исчерпан: держать потоки до закрытия
                    // конвейера незачем — квота нужна следующему.
                    release();
                    return null;
                }
                Value value = await(ready);
                fill();
                return value;
            }

            @Override
            public void close() {
                release();
                up.close();
            }

            /** Доводит окно до {@code width} задач, пока вход не кончился. */
            private void fill() {
                while (!drained && !closed && window.size() < width) {
                    Value item = up.next();
                    if (item == null) {
                        drained = true;
                        return;
                    }
                    window.addLast(service.submit(() -> transform.call(item)));
                }
            }

            /**
             * Ждёт задачу и переводит её конец в термины языка.
             * <p>
             * Первая же ошибка отменяет остальные задачи и летит наружу: продолжать
             * считать ради результата, который всё равно не сложится, незачем.
             */
            private Value await(Future<Value> task) {
                try {
                    return task.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cancelAll();
                    throw FatalError.interrupted(span);
                } catch (CancellationException cancelled) {
                    cancelAll();
                    throw FatalError.interrupted(span);
                } catch (ExecutionException failed) {
                    cancelAll();
                    throw unwrap(failed, span);
                }
            }

            private void cancelAll() {
                window.forEach(task -> task.cancel(true));
                window.clear();
                release();
            }

            /** Останавливает потоки стадии и возвращает занятое место. Идемпотентно. */
            private void release() {
                if (closed) {
                    return;
                }
                closed = true;
                service.shutdownNow();
                quota.close();
            }
        };
    }

    /**
     * Ошибка задачи — ошибка языка, а не обёртка вокруг неё.
     * <p>
     * {@code ExecutionException} автору скрипта не говорит ничего: ему нужен тот же
     * {@code IndexError} с тем же местом, что он получил бы, позвав функцию напрямую.
     * Правило и текст те же, что у {@code th.pool}.
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
                "задача конвейера не выполнилась: " + cause);
    }
}
