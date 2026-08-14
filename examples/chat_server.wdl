// Локальный TCP Чат-Сервер на порту 9088 (WDL)
import sys.net.socket as net

server = new net.Server(9088)
println("=== WDL Chat Server started on port 9088 ===")

clients = []

def broadcast(message) {
    println("[SERVER LOG] Broadcasting message to ", len(clients), " clients: ", message)
    for (client in clients) {
        try {
            client.send(message)
            println("[SERVER LOG] Sent to client ", client.host)
        } catch (e) {
            println("[SERVER LOG] Error sending to client: ", e)
        }
    }
}

while (true) {
    client = server.accept()
    println("[SERVER LOG] New client connected from ", client.host)
    clients += [client]

    client.onLine(def (line) {
        println("[SERVER LOG] Received line from client: ", line)
        if (line != "") {
            broadcast(line)
        }
    })
}
