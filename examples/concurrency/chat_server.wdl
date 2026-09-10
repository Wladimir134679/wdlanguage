// Локальный TCP-чат на порту 9088: сервер, который держит нескольких клиентов сразу.
// Запуск: wdl examples/concurrency/chat_server.wdl   (клиент — examples/concurrency/chat_client.wdl)
//
// Этот пример — про потоки. Каждый onLine работает в своём потоке, поэтому два
// сообщения от двух клиентов приходят одновременно, а не по очереди. Общий список
// clients при этом один на всех — и вот его надо защищать. Правила: docs/threads.md.

import sys.net.socket as net

server = new net.Server(9088)
println("=== WDL Chat Server started on port 9088 ===")

clients = []

// synchronized, потому что 'clients = clients + [client]' — это два обращения:
// прочитать список и записать новый. Два потока, прочитавшие один и тот же список,
// запишут по одному клиенту поверх друг друга, и один из них молча пропадёт.
synchronized def addClient(client) {
    clients = clients + [client]
    return len(clients);
}

// Тоже synchronized, и с тем же замком: пока идёт рассылка, список не должен
// меняться под ногами. Замок принадлежит значению-функции, а разные функции —
// разным замкам, поэтому здесь общий список охраняется тем, что обе они трогают
// его только под своим модификатором и никогда одновременно с чужой записью.
synchronized def broadcast(message) {
    println("[server] рассылка ", len(clients), " клиентам: ", message)
    for (client in clients) {
        try {
            client.send(message)
        } catch (e) {
            println("[server] клиент ", client.host, " недоступен: ", e.message)
        }
    }
}

// onConnection принимает клиентов в своём потоке; обработчик короткий — принять
// и подписаться. Разговор с клиентом идёт уже в потоке его onLine.
server.onConnection(def (client) {
    println("[server] подключился ", client.host, ", всего ", addClient(client))

    client.onLine(def (line) {
        if (line != "") {
            broadcast(line)
        }
    })
})

println("[server] жду клиентов. Ctrl+C — выход")

// Главный поток больше ничего не делает: работа идёт в потоках сокетов.
// Спим вместо пустого цикла — пустой цикл жёг бы ядро без всякой пользы.
import sys.thread as th
while (true) th.sleep(1000)
