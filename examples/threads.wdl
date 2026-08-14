// Потоки: запустить, дождаться, остановить — и защитить общее состояние.
// Запуск: wdl examples/threads.wdl
//
// Правила игры описаны в docs/threads.md. Коротко: одно обращение атомарно,
// выражение целиком — нет.

import sys.thread as th

// --- запустить и дождаться ---------------------------------------------------

worker = th.spawn("worker-1", def () {
    th.sleep(50)
    return 120 * 3;
})

println("поток ", worker.name, " запущен, работает: ", worker.alive)
println("результат: ", worker.join())        // join ждёт и отдаёт то, что вернула функция
println("после join работает: ", worker.alive)

// --- несколько потоков сразу -------------------------------------------------

workers = []
for (i = 1; i <= 4; i += 1) {
    workers = workers + [th.spawn("sum-" + i, def () {
        total = 0
        for (n = 0; n < 100000; n += 1) total += n
        return total;
    })]
}

// Все четыре считали одновременно, а не по очереди — на это и заведены потоки.
for (w in workers) println(w.name, " → ", w.join())

// --- почему нужен synchronized ----------------------------------------------

counter = 0

// 'counter = counter + 1' — это два обращения: прочитать и записать. Два потока,
// прочитавшие одно и то же, запишут одно и то же, и один инкремент пропадёт.
// Модификатор склеивает их в одно неделимое целое.
synchronized def bump() {
    counter = counter + 1
}

bumpers = []
for (i = 0; i < 8; i += 1) {
    bumpers = bumpers + [th.spawn(def () {
        for (n = 0; n < 1000; n += 1) bump()
    })]
}
for (b in bumpers) b.join()

println("счётчик: ", counter, " (ожидалось 8000)")

// То же самое без единого замка — счётчиком:
hits = th.counter(0)
hits.add(5)
hits.inc()
println("th.counter: ", hits.get())

// --- остановка ---------------------------------------------------------------

// Фоновая работа с выходом по прерыванию. Ловить прерывание обработчиком нельзя
// и не нужно: это остановка выполнения, а не ошибка, — иначе кнопка «остановить»
// перестала бы работать.
ticker = th.spawn("ticker", def () {
    while (true) {
        println("  тик")
        th.sleep(100)
    }
})

th.sleep(250)
ticker.interrupt()
ticker.join(1000)
println("тикер остановлен: ", !ticker.alive)

// --- ошибка потока не теряется ------------------------------------------------

broken = th.spawn("broken", def () => [1][5])
broken.join()
println("ошибка потока: ", broken.error)

println("готово")
