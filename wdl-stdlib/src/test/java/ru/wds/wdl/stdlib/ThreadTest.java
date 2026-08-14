package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модуль {@code sys.thread}: управление потоками из скрипта.
 * <p>
 * У каждой проверки свой таймаут: тест многопоточности, который не падает,
 * а <b>висит</b>, ломает всю сборку и ничего при этом не сообщает.
 */
class ThreadTest {

    /** Общий предел на любой тест здесь: заведомо больше любой честной задержки. */
    private static final Duration LIMIT = Duration.ofSeconds(15);

    @Test
    @DisplayName("join() возвращает результат функции потока")
    void joinReturnsResult() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("360", Scripts.printed("""
                import sys.thread as th

                t = th.spawn(def () => 120 * 3)
                println(t.join())
                """)));
    }

    @Test
    @DisplayName("потоку можно дать имя, и оно видно из него самого")
    void threadKeepsItsName() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("worker-1 worker-1", Scripts.printed("""
                import sys.thread as th

                t = th.spawn("worker-1", def () => th.current().name)
                inside = t.join()
                println(t.name, " ", inside)
                """)));
    }

    @Test
    @DisplayName("t.alive отвечает по факту, а не по записанному при создании")
    void aliveIsComputed() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("false", Scripts.printed("""
                import sys.thread as th

                t = th.spawn(def () => 1)
                t.join()
                println(t.alive)
                """)));
    }

    @Test
    @DisplayName("восемь потоков работают одновременно, а не по очереди")
    void threadsRunAtTheSameTime() {
        // Барьер, а не секундомер: каждый поток отмечается и ждёт, пока отметятся все
        // восемь. Сойтись им можно только одновременно — при работе по очереди первый
        // же ждал бы вечно, и join(5000) вернул бы null. Секундомер тут был бы хуже:
        // на загруженной машине он краснеет без всякой очереди.
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("сошлись: 8", Scripts.printed("""
                import sys.thread as th

                arrived = {}
                workers = []
                for (i = 0; i < 8; i += 1) {
                    workers = workers + [th.spawn(def () {
                        arrived[th.current().id] = true
                        while (len(arrived) < 8) th.sleep(5)
                        return "ок";
                    })]
                }

                met = 0
                for (w in workers) {
                    if (w.join(5000) == "ок") met += 1
                }
                println("сошлись: ", met)
                """)));
    }

    @Test
    @DisplayName("ошибка потока ложится в t.error, а не теряется")
    void errorGoesToTheHandle() {
        assertTimeoutPreemptively(LIMIT, () -> {
            String printed = Scripts.printed("""
                    import sys.thread as th

                    t = th.spawn(def () => [1][5])
                    t.join()
                    println("error: ", t.error)
                    """);
            assertTrue(printed.contains("вне границ"), printed);
        });
    }

    @Test
    @DisplayName("ошибка несджойненного потока печатается в вывод запуска с именем потока")
    void unwatchedErrorIsPrinted() {
        assertTimeoutPreemptively(LIMIT, () -> {
            String printed = Scripts.printed("""
                    import sys.thread as th

                    th.spawn("loner", def () => [1][5])
                    th.sleep(300)
                    println("конец")
                    """);
            // Раньше такая ошибка уходила в System.err мимо вывода приложения —
            // то есть в никуда для того, кто движок встроил.
            assertTrue(printed.contains("loner") && printed.contains("вне границ"), printed);
            assertTrue(printed.contains("конец"), printed);
        });
    }

    @Test
    @DisplayName("interrupt() снимает вечный цикл, и catch внутри скрипта его не глотает")
    void interruptStopsEndlessLoop() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("остановлен", Scripts.printed("""
                import sys.thread as th

                t = th.spawn(def () {
                    while (true) {
                        try {
                            th.sleep(10)
                        } catch (e) {
                            // Прерывание — не ошибка скрипта: поймать его здесь нельзя,
                            // иначе кнопка «остановить» перестала бы работать.
                        }
                    }
                })

                th.sleep(100)
                t.interrupt()
                t.join(3000)
                println(t.alive ? "жив" : "остановлен")
                """)));
    }

    @Test
    @DisplayName("join(ms) отвечает null, если поток не успел")
    void joinWithTimeoutGivesNull() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("null true", Scripts.printed("""
                import sys.thread as th

                t = th.spawn(def () { th.sleep(2000) })
                println(t.join(100), " ", t.alive)
                t.interrupt()
                """)));
    }

    @Test
    @DisplayName("поток видит и меняет переменные своего файла")
    void threadSharesTheScriptState() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("готово", Scripts.printed("""
                import sys.thread as th

                state = {}

                def fill() {
                    state.status = "готово"
                }

                th.spawn(fill).join()
                println(state.status)
                """)));
    }

    // --- пул и обещания -------------------------------------------------------

    @Test
    @DisplayName("pool.map обрабатывает список параллельно и сохраняет порядок")
    void poolMapKeepsOrderAndRunsInParallel() {
        // Защёлка вместо секундомера: каждая задача отмечается и ждёт остальных.
        // Дождаться можно только если все четыре идут одновременно — при работе
        // по очереди первая же упёрлась бы в таймаут и вернула "поздно".
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("[1, 2, 3, 4]", Scripts.printed("""
                import sys.thread as th

                ready = th.latch(4)

                use (pool = th.pool(4)) {
                    println(pool.map([1, 2, 3, 4], def (n) {
                        ready.countDown()
                        return ready.await(5000) ? n : "поздно";
                    }))
                }
                """)));
    }

    @Test
    @DisplayName("pool.submit отдаёт обещание, а get() — его результат")
    void submitReturnsAFuture() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("42 true", Scripts.printed("""
                import sys.thread as th

                use (pool = th.pool(2)) {
                    f = pool.submit(def () => 6 * 7)
                    value = f.get()
                    println(value, " ", f.done)
                }
                """)));
    }

    @Test
    @DisplayName("get(ms) отвечает null, если задача не успела, а cancel() её снимает")
    void futureTimeoutAndCancel() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("null true", Scripts.printed("""
                import sys.thread as th

                use (pool = th.pool(2)) {
                    f = pool.submit(def () { th.sleep(5000) })
                    early = f.get(100)
                    println(early, " ", f.cancel())
                }
                """)));
    }

    @Test
    @DisplayName("ошибка задачи остаётся ошибкой языка и ловится обработчиком")
    void futureErrorStaysAScriptError() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("поймана IndexError", Scripts.printed("""
                import sys.thread as th

                use (pool = th.pool(2)) {
                    f = pool.submit(def () => [1][5])
                    try {
                        f.get()
                        println("ошибки не было")
                    } catch (e is IndexError) {
                        println("поймана ", e.kind)
                    }
                }
                """)));
    }

    @Test
    @DisplayName("закрытый пул задач больше не принимает")
    void closedPoolRefusesWork() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("пул закрыт", Scripts.printed("""
                import sys.thread as th

                pool = th.pool(2)
                pool.close()
                try {
                    pool.submit(def () => 1)
                    println("приняли")
                } catch (e) {
                    println("пул закрыт")
                }
                """)));
    }

    // --- замок, счётчик, канал, защёлка ---------------------------------------

    @Test
    @DisplayName("th.lock() склеивает несколько обращений в одно: массив не теряет элементов")
    void lockProtectsSharedArray() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("2000", Scripts.printed("""
                import sys.thread as th

                guard = th.lock()
                shared = []

                def worker() {
                    for (i = 0; i < 250; i += 1) {
                        guard.run(def () {
                            shared = shared + [i]
                        })
                    }
                }

                workers = []
                for (n = 0; n < 8; n += 1) workers = workers + [th.spawn(worker)]
                for (w in workers) w.join()

                println(len(shared))
                """)));
    }

    @Test
    @DisplayName("th.counter() атомарен без единого захвата: 8 × 1000 = 8000")
    void counterIsAtomic() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("8000", Scripts.printed("""
                import sys.thread as th

                hits = th.counter(0)

                def worker() {
                    for (i = 0; i < 1000; i += 1) hits.inc()
                }

                workers = []
                for (n = 0; n < 8; n += 1) workers = workers + [th.spawn(worker)]
                for (w in workers) w.join()

                println(hits.get())
                """)));
    }

    @Test
    @DisplayName("compareAndSet — то, что двумя обращениями не выражается")
    void counterCompareAndSet() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("true false 6", Scripts.printed("""
                import sys.thread as th

                c = th.counter(5)
                first = c.compareAndSet(5, 6)
                second = c.compareAndSet(5, 7)
                println(first, " ", second, " ", c.get())
                """)));
    }

    @Test
    @DisplayName("канал переносит работу от продюсера к консюмеру, а close() будит ждущих")
    void channelCarriesWork() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("сумма 15", Scripts.printed("""
                import sys.thread as th

                ch = th.channel(2)
                total = th.counter(0)

                consumer = th.spawn("consumer", def () {
                    while (true) {
                        item = ch.take()
                        if (item == null) break
                        total.add(item)
                    }
                })

                for (i = 1; i <= 5; i += 1) ch.send(i)
                ch.close()
                consumer.join()

                println("сумма ", total.get())
                """)));
    }

    @Test
    @DisplayName("poll(ms) отвечает null, если в канале пусто")
    void channelPollTimesOut() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("null", Scripts.printed("""
                import sys.thread as th

                ch = th.channel(1)
                println(ch.poll(100))
                """)));
    }

    @Test
    @DisplayName("защёлка отпускает ровно тогда, когда счёт дошёл до нуля")
    void latchWaitsForEveryone() {
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("false true", Scripts.printed("""
                import sys.thread as th

                ready = th.latch(2)
                ready.countDown()
                early = ready.await(100)
                ready.countDown()
                println(early, " ", ready.await(1000))
                """)));
    }

    @Test
    @DisplayName("прерывание снимает поток, ждущий входа в synchronized-функцию")
    void interruptWakesUpAWaiterOnTheLock() {
        // Это то, ради чего замок сделан ReentrantLock, а не блоком synchronized:
        // вход в него берётся lockInterruptibly. Иначе поток, застрявший на замке,
        // не снялся бы ничем, и остановка зациклившегося скрипта перестала бы работать.
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("снят", Scripts.printed("""
                import sys.thread as th

                state = {}

                synchronized def hold() {
                    state.inside = true
                    th.sleep(5000)
                }

                holder = th.spawn("holder", hold)
                while (!state.inside) th.sleep(5)

                waiter = th.spawn("waiter", hold)
                th.sleep(200)
                waiter.interrupt()
                waiter.join(3000)
                println(waiter.alive ? "висит на замке" : "снят")

                holder.interrupt()
                holder.join(3000)
                """)));
    }

    @Test
    @DisplayName("th.sleep прерываемо и отвечает остановкой выполнения")
    void sleepIsInterruptible() {
        // Не будь сон прерываемым, поток проспал бы десять секунд и остался живым
        // после join(3000) — тест это и различает, без секундомера.
        assertTimeoutPreemptively(LIMIT, () -> assertEquals("прервали", Scripts.printed("""
                import sys.thread as th

                t = th.spawn(def () { th.sleep(10000) })
                th.sleep(100)
                t.interrupt()
                t.join(3000)
                println(t.alive ? "спит дальше" : "прервали")
                """)));
    }
}
