// Сервер двоичного чата: окно на Swing, обмен — кадрами, а не строками.
// Запуск: wdl examples/chat_bin/server.wdl   (клиент — examples/chat_bin/client.wdl)
//
// Отличие от текстового чата (examples/concurrency/chat_server.wdl) ровно одно
// и всё определяет: соединение открыто в режиме "bytes", значит границы сообщений
// сервер проводит сам. Их проводит формат из protocol.wdl, а слушатель отдаёт
// то, что пришло, — куском, а не строкой.

import sys.gui as gui
import sys.net.socket as net
import sys.thread as th
import protocol as proto

const PORT = 9099

// --- окно --------------------------------------------------------------------

win = new gui.Window(title: "WDL Chat — сервер, порт " + PORT, width: 560, height: 420)
win.setLayout(layout: gui.border(hgap: 8, vgap: 8))

topPanel = gui.hbox()
statusLabel = new gui.Label(text: "слушаю порт " + PORT + ", клиентов: 0")
stopButton = new gui.Button(text: "Остановить")
topPanel.add(component: statusLabel)
topPanel.add(component: stopButton)

logArea = new gui.TextArea(rows: 18, cols: 60, enabled: false)

win.add(component: topPanel, constraint: "North")
win.add(component: logArea, constraint: "Center")

// Окно трогает только поток интерфейса, а сюда приходят из потоков сокетов:
// каждый onBytes работает в своём. gui.later — единственный правильный мост
// (docs/gui.md), и он же причина, по которой весь вывод идёт через одну функцию.
def log(text) {
    println(text)
    gui.later(handler: def () => logArea.append(text + "\n"))
}

// --- список клиентов ---------------------------------------------------------

clients = []

// Один замок на весь список, а не 'synchronized def' на каждую из трёх функций.
// Причина — в самом правиле: замок принадлежит значению-функции, а разные функции
// это разные замки, и рассылка спокойно шла бы одновременно с добавлением. Список
// тут один, значит и замок нужен один общий (docs/threads.md).
//
// Защищать его надо потому, что 'clients = clients + [entry]' — это два обращения:
// прочитать список и записать новый. Два потока, прочитавшие один и тот же список,
// запишут по одному клиенту поверх друг друга, и один молча пропадёт.
lock = th.lock()

def addClient(entry) => lock.run(def () {
    clients = clients + [entry]
    return len(clients);
})

def removeClient(entry) => lock.run(def () {
    clients = clients.filter(other => other != entry)
    return len(clients);
})

def updateStatus() {
    count = len(clients)
    gui.later(handler: def () {
        statusLabel.text = "слушаю порт " + PORT + ", клиентов: " + count
    })
}

// Тот же замок: пока идёт рассылка, список не должен меняться под ногами.
// Замок повторный, поэтому removeClient внутри — это не тупик, а тот же поток,
// входящий второй раз. Байты при этом неизменяемы, и один и тот же кадр уходит
// всем без единой копии.
def broadcast(raw) => lock.run(def () {
    gone = []
    for (entry in clients) {
        try {
            entry.socket.writeBytes(raw)
        } catch (e) {
            // О разорванном соединении TCP сообщает на первой же отправке, а не
            // в момент разрыва: узнать раньше неоткуда, и это нормальная жизнь.
            log("клиент " + entry.nick + " недоступен: " + e.message)
            gone.push(entry)
        }
    }
    for (entry in gone) {
        entry.socket.close()
        removeClient(entry)
    }
    if (len(gone) > 0) updateStatus()
})

// --- разбор кадров -----------------------------------------------------------

def receive(entry, frame) {
    message = proto.decode(frame)
    log(proto.line(message))

    if (message.kind == proto.JOIN) {
        entry.nick = message.nick
        updateStatus()
        broadcast(proto.info(message.nick + " вошёл в чат"))
        return;
    }
    if (message.kind == proto.LEAVE) {
        removeClient(entry)
        updateStatus()
        entry.socket.close()
        broadcast(proto.info(message.nick + " вышел"))
        return;
    }
    // Кадр уходит дальше байт в байт, как пришёл: сервер его прочитал, но
    // пересобирать незачем — байты уже правильные, а лишняя сборка это лишний
    // способ разойтись с клиентом.
    broadcast(frame.raw)
}

// --- приём клиентов ----------------------------------------------------------

server = new net.Server(port: PORT, mode: "bytes")

// Обработчик подключения работает в потоке приёма и потому короток: принять
// и подписаться. Разговор идёт уже в потоке onBytes этого клиента.
server.onConnection(handler: def (client) {
    // Свой накопитель на соединение — и никакого замка: кормит его ровно один
    // поток, слушатель этого же сокета.
    frames = new proto.Framer()
    entry = {socket: client, nick: client.host}

    log("подключился " + client.host + ", всего " + addClient(entry))
    updateStatus()

    client.onBytes(handler: def (chunk) {
        try {
            for (frame in frames.feed(chunk)) receive(entry, frame)
        } catch (e) {
            // Испорченный поток чинить нечем: длина следующего кадра будет взята
            // из середины чужих данных. Закрываем это соединение, остальные живут.
            log("кадр от " + entry.nick + " разобрать не вышло: " + e.message)
            removeClient(entry)
            updateStatus()
            client.close()
        }
    })
})

stopButton.onClick(handler: def () {
    broadcast(proto.info("сервер остановлен"))
    for (entry in clients) entry.socket.close()
    server.close()
    log("сервер остановлен")
    win.close()
})

log("сервер слушает порт " + PORT + ", жду клиентов")
win.show()
