package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Интеграционный тест многопользовательской рассылки сокетов (broadcast).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ChatIntegrationTest {

    @Test
    @DisplayName("Сервер принимает сокеты и делает broadcast сообщений всем клиентам")
    void serverBroadcastToClients() {
        assertEquals("ServerStarted Client1Recv: [User1]: Hello Client2Recv: [User1]: Hello", printed("""
                import sys.net.socket as net

                server = new net.Server(9095)
                print("ServerStarted ")

                clients = []

                def broadcast(msg) {
                    for (c in clients) {
                        c.send(msg)
                    }
                }

                c1 = new net.Socket("127.0.0.1", 9095)
                s1 = server.accept()
                clients += [s1]

                c2 = new net.Socket("127.0.0.1", 9095)
                s2 = server.accept()
                clients += [s2]

                s1.onLine(def (line) => broadcast(line))
                s2.onLine(def (line) => broadcast(line))

                c1.send("[User1]: Hello")

                msg1 = c1.readLine()
                msg2 = c2.readLine()

                print("Client1Recv: ", msg1, " ")
                println("Client2Recv: ", msg2)

                c1.close()
                c2.close()
                s1.close()
                s2.close()
                server.close()
                """));
    }
}
