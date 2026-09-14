// Клиент двоичного чата: окно на Swing, обмен — кадрами из protocol.wdl.
// Запуск: сперва wdl examples/chat_bin/server.wdl, потом этот файл — можно дважды,
// два окна разговаривают друг с другом.
//
// Весь разговор с сетью здесь — четыре строки: открыть соединение в режиме "bytes",
// подписаться на куски, скормить их накопителю, отправить собранный кадр. Всё
// остальное в файле — окно.

import sys.gui as gui
import sys.net.socket as net
import protocol as proto

const HOST = "127.0.0.1"
const PORT = 9099

// --- окно --------------------------------------------------------------------

win = new gui.Window(title: "WDL Chat (двоичный, порт " + PORT + ")", width: 520, height: 440)
win.setLayout(layout: gui.border(hgap: 8, vgap: 8))

topPanel = gui.hbox()
nickField = new gui.TextField(text: "user")
connectButton = new gui.Button(text: "Подключиться")
topPanel.add(component: new gui.Label(text: "Имя: "))
topPanel.add(component: nickField)
topPanel.add(component: connectButton)

chatArea = new gui.TextArea(rows: 18, cols: 44, enabled: false)

bottomPanel = gui.hbox()
messageField = new gui.TextField(text: "", enabled: false)
sendButton = new gui.Button(text: "Отправить", enabled: false)
bottomPanel.add(component: messageField)
bottomPanel.add(component: sendButton)

win.add(component: topPanel, constraint: "North")
win.add(component: chatArea, constraint: "Center")
win.add(component: bottomPanel, constraint: "South")

// Окно живёт в потоке интерфейса, а сообщения приходят в потоке сокета —
// gui.later переносит запись туда, где ей можно (docs/gui.md).
def show(text) {
    gui.later(handler: def () => chatArea.append(text + "\n"))
}

// --- соединение --------------------------------------------------------------

socket = null
frames = null

def connect() {
    nick = nickField.text
    if (nick == "") {
        gui.alert("Сначала имя", title: "Чат")
        return;
    }
    try {
        // Режим задаётся при открытии и потом не меняется: буфер строк читает
        // вперёд и съел бы байты, которых потом не хватит (docs/bytes.md).
        socket = new net.Socket(host: HOST, port: PORT, mode: "bytes")
        frames = new proto.Framer()

        // onBytes отдаёт то, что пришло, — кусок, а не сообщение: у TCP нет границ,
        // и кадр может приехать двумя кусками, а два кадра одним. Границы проводит
        // накопитель, и он же здесь единственный, кого кормит этот поток.
        socket.onBytes(handler: def (chunk) {
            try {
                for (frame in frames.feed(chunk)) show(proto.line(proto.decode(frame)))
            } catch (e) {
                show("* поток испорчен: " + e.message)
                disconnect()
            }
        })

        socket.writeBytes(proto.join(nick))
        show("* подключились к " + HOST + ":" + PORT)
        connectButton.enabled = false
        nickField.enabled = false
        messageField.enabled = true
        sendButton.enabled = true
    } catch (e) {
        socket = null
        gui.alert("Не подключиться к " + HOST + ":" + PORT + " — " + e.message, title: "Чат")
    }
}

def disconnect() {
    if (socket == null) return;
    // Прощание уходит первым: сервер узнает о разрыве и без него, но только
    // на следующей отправке — то есть тогда, когда кто-нибудь заговорит.
    try {
        socket.writeBytes(proto.leave(nickField.text))
    } catch (e) {
        // Соединения уже нет — прощаться не с кем, и это не повод для ошибки.
    }
    socket.close()
    socket = null
}

def send() {
    text = messageField.text
    if (text == "" || socket == null) return;
    try {
        socket.writeBytes(proto.say(nickField.text, text))
        messageField.text = ""
    } catch (e) {
        show("* не отправить: " + e.message)
        disconnect()
    }
}

connectButton.onClick(handler: def () => connect())
sendButton.onClick(handler: def () => send())
messageField.onEnter(handler: def () => send())

// Закрытие окна — это выход из чата, а не обрыв связи: сервер получит LEAVE
// и скажет о нём остальным.
win.onClose(handler: def () => disconnect())

win.show()
