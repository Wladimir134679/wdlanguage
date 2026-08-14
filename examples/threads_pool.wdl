// Пул, обещания, канал и защёлка: когда задач больше, чем потоков.
// Запуск: wdl examples/threads_pool.wdl
//
// Потоки поштучно — examples/threads.wdl. Правила — docs/threads.md.

import sys.thread as th

// --- пул: N задач на фиксированном числе потоков -----------------------------

def slowSquare(n) {
    th.sleep(50)
    return n * n;
}

// use закрывает пул, чем бы дело ни кончилось: пул держит живые потоки,
// и забыть его — значит утечь. Класс подмешивает Closeable, поэтому 'use' работает.
use (pool = th.pool(4)) {

    // map ждёт всех и сохраняет порядок входа: результат соответствует элементу,
    // а не тому, кто раньше закончил.
    println("квадраты: ", pool.map([1, 2, 3, 4, 5, 6, 7, 8], slowSquare))

    // submit отдаёт обещание: работа уже идёт, результат спрашивается позже.
    slow = pool.submit(def () {
        th.sleep(300)
        return "долгий ответ";
    })
    fast = pool.submit(def () => "быстрый ответ")

    println("быстрый готов: ", fast.get())
    println("долгий за 50 мс: ", slow.get(50))     // null — не успел, и это не ошибка
    println("долгий целиком: ", slow.get())

    // Ошибка задачи остаётся ошибкой языка: тот же класс, то же место, тот же catch.
    broken = pool.submit(def () => [1][5])
    try {
        broken.get()
    } catch (e is IndexError) {
        println("поймали ", e.kind, ": ", e.message)
    }
}

// --- канал: продюсер и консюмер ----------------------------------------------

queue = th.channel(4)
processed = th.counter(0)

consumer = th.spawn("consumer", def () {
    while (true) {
        item = queue.take()
        if (item == null) break        // канал закрыт и пуст — работа кончилась
        processed.add(item)
    }
    return processed.get();
})

for (i = 1; i <= 10; i += 1) queue.send(i)
queue.close()                          // close будит того, кто ждёт на пустом канале

println("сумма из канала: ", consumer.join())

// --- защёлка: дождаться, пока все будут готовы -------------------------------

ready = th.latch(3)
lock = th.lock()
started = []

for (i = 1; i <= 3; i += 1) {
    th.spawn("stage-" + i, def () {
        // Замок значением — там, где защитить надо не всю функцию, а три строки.
        lock.run(def () {
            started = started + [th.current().name]
        })
        ready.countDown()
    })
}

println("все стартовали: ", ready.await(5000), ", их ", len(started))
println("готово")
