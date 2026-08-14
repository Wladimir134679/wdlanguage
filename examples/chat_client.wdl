// Локальный TCP Чат-Клиент с Swing GUI на порту 9088 (WDL)
import sys.gui as gui
import sys.net.socket as net

println("[CLIENT LOG] Starting WDL Chat Client GUI...")

win = new gui.Window("WDL Chat (Port 9088)", 480, 420)
win.setLayout(gui.border(10, 10))

topPanel = gui.hbox()
lblNick = new gui.Label("Nickname: ")
inputNick = new gui.TextField("User")
btnConnect = new gui.Button("Connect")

topPanel.add(lblNick)
topPanel.add(inputNick)
topPanel.add(btnConnect)

chatArea = new gui.TextArea("", 15, 35)

bottomPanel = gui.hbox()
inputMsg = new gui.TextField("")
inputMsg.setEnabled(false)
btnSend = new gui.Button("Send")
btnSend.setEnabled(false)

bottomPanel.add(inputMsg)
bottomPanel.add(btnSend)

socket = null

def connect() {
    nick = inputNick.getText()
    println("[CLIENT LOG] Connect clicked with nickname: ", nick)
    if (nick == "") {
        gui.alert("Please enter a nickname!", "Error")
        return;
    }

    try {
        println("[CLIENT LOG] Connecting TCP socket to 127.0.0.1:9088...")
        socket = new net.Socket("127.0.0.1", 9088)
        println("[CLIENT LOG] Connected! Socket: ", socket)
        chatArea.append("System: Connected to server on port 9088\n")
        btnConnect.setEnabled(false)
        inputNick.setEnabled(false)
        inputMsg.setEnabled(true)
        btnSend.setEnabled(true)

        socket.onLine(def (line) {
            println("[CLIENT LOG] Received message from server: ", line)
            gui.later(def () {
                chatArea.append(line + "\n")
            })
        })

        socket.send("System: " + nick + " joined the room.")
        println("[CLIENT LOG] Sent join message.")
    } catch (e) {
        println("[CLIENT ERROR] Connection error: ", e)
        gui.alert("Could not connect to server on port 9088: " + e, "Connection Error")
    }
}

def sendMessage() {
    text = inputMsg.getText()
    nick = inputNick.getText()
    println("[CLIENT LOG] sendMessage called. text=", text, " nick=", nick, " socket=", socket)
    if (text != "" && socket != null) {
        socket.send("[" + nick + "]: " + text)
        println("[CLIENT LOG] Message sent over socket: [", nick, "]: ", text)
        inputMsg.setText("")
    } else {
        println("[CLIENT WARNING] sendMessage skipped. text empty or socket null.")
    }
}

btnConnect.onClick(def () => connect())
btnSend.onClick(def () => sendMessage())
inputMsg.onEnter(def () => sendMessage())

win.add(topPanel, "North")
win.add(chatArea, "Center")
win.add(bottomPanel, "South")

win.show()
