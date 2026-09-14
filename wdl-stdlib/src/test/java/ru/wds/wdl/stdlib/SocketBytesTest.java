package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.source.Source;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Байтовый режим сокетов: слушатель кусками и остановка потоков, которые
 * прерыванием не будятся.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SocketBytesTest {

    @Test
    @DisplayName("onBytes отдаёт куски, а границы сообщений проводит скрипт")
    void listensToBytes() {
        // Отправлено три записи, а придёт их сколько угодно: у TCP нет границ
        // сообщений. Поэтому проверяется не число кусков, а собранное целое —
        // ровно так, как это делает накопитель из examples/chat_bin/protocol.wdl.
        assertEquals("собрано: 0102030405060708", printed("""
                import sys.net.socket as net
                import sys.bytes as bin
                import sys.thread as th

                server = new net.Server(0, "bytes")
                got = bin.zeros(0)
                enough = th.latch(1)
                lock = th.lock()

                server.onConnection(def (client) {
                    client.onBytes(def (chunk) {
                        lock.run(def () {
                            got = got + chunk
                            if (got.size == 8) enough.countDown()
                        })
                    })
                })

                talker = new net.Socket("127.0.0.1", server.port, "bytes")
                talker.writeBytes(bin.hex("0102"))
                talker.writeBytes(bin.hex("030405"))
                talker.writeBytes(bin.hex("060708"))

                enough.await(5000)
                println("собрано: ", got.hex)

                talker.close()
                server.close()
                """));
    }

    @Test
    @DisplayName("режим сервера достаётся принятому соединению, и чужой режим отказывает")
    void modeIsInherited() {
        assertEquals("режим: bytes отказ: true", printed("""
                import sys.net.socket as net
                import sys.bytes as bin

                server = new net.Server(0, "bytes")
                talker = new net.Socket("127.0.0.1", server.port, "bytes")
                accepted = server.accept()

                print("режим: ", accepted.mode, " ")
                talker.writeBytes(bin.hex("2a"))
                accepted.readExactly(1)

                // Строки на байтовом соединении — ошибка с объяснением, а не тихий ноль.
                println("отказ: ", (try? accepted.readLine()) == null)

                talker.close()
                accepted.close()
                server.close()
                """));
    }

    @Test
    @DisplayName("порт нулевого сервера — тот, который он занял")
    void serverReportsBoundPort() {
        assertEquals("порт занят: true", printed("""
                import sys.net.socket as net

                server = new net.Server(0)
                println("порт занят: ", server.port > 0)
                server.close()
                """));
    }

    @Test
    @DisplayName("слушатели сокета и сервера останавливаются вместе с запуском, а не висят")
    void listenersStopWithRun() {
        // Поток, стоящий в accept() или read(), прерыванием не выходит: его будит
        // только закрытие. Без будильника ({@code ScriptThreads.start} с третьим
        // аргументом) закрытие запуска ждало бы их весь свой срок и называло в логе —
        // и так было с каждым скриптом, который держал сокет открытым.
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString("""
                import sys.net.socket as net
                import sys.thread as th

                server = new net.Server(0, "bytes")
                ready = th.latch(1)

                server.onConnection(def (client) {
                    client.onBytes(def (chunk) { })
                    ready.countDown()
                })

                talker = new net.Socket("127.0.0.1", server.port, "bytes")
                ready.await(5000)
                println("слушают трое")
                """);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> diagnostics.renderAll());

        ExecutionContext context = ExecutionContext.fresh((Output) output::append)
                .withNativeModules(Sys.modules());
        long spent;
        List<String> stubborn;
        try {
            new Interpreter().run(program, context);
            long started = System.nanoTime();
            // Столько же, сколько ждёт CLI, — но уложиться надо мгновенно.
            stubborn = context.stopScriptThreads(5000);
            spent = (System.nanoTime() - started) / 1_000_000L;
        } finally {
            context.shutdownModules();
        }
        assertTrue(stubborn.isEmpty(), () -> "не остановились: " + stubborn);
        assertTrue(spent < 2000, () -> "остановка заняла " + spent + " мс");
    }
}
