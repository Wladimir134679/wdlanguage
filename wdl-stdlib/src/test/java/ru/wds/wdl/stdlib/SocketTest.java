package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Тесты для модуля {@code sys.net.socket}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SocketTest {

    @Test
    @DisplayName("Импорт модуля sys.net.socket")
    void socketModuleImport() {
        assertEquals("module sys/net/socket", printed("""
                import sys.net.socket as net
                println(net)
                """));
    }

    @Test
    @DisplayName("Передача данных по TCP сокету")
    void socketCommunication() {
        assertEquals("Received: Ping from client!", printed("""
                import sys.net.socket as net

                server = new net.Server(9089)
                client = new net.Socket("127.0.0.1", 9089)
                accepted = server.accept()

                client.send("Ping from client!")
                received = accepted.readLine()

                println("Received: ", received)

                client.close()
                accepted.close()
                server.close()
                """));
    }
}
