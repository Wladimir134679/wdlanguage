package ru.wds.wdl.dap;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Адаптер через настоящий JSON-RPC: сообщения, ответы, события.
 * <p>
 * Один сеанс, а не набор случаев: за сам протокол отвечает lsp4j, и проверять здесь
 * надо не его, а что наша сборка сходится — сервер поднялся, клиент назвался,
 * события доехали в том порядке, в котором отладчик их ждёт:
 * {@code initialize → launch → initialized → setBreakpoints → configurationDone}.
 * Всё остальное разобрано в {@link AdapterTest}, где транспорта нет и нечему мешать.
 * <p>
 * Обмен идёт через сокет на локальном адресе, а не через {@code PipedInputStream},
 * и это не вкус: труба из {@code java.io} привязана к <b>потоку</b>-писателю и после
 * его смерти отвечает читателю «write end dead». А писателем здесь бывает поток
 * скрипта — события {@code output} и {@code exited} отправляет он, — и он законно
 * умирает, дойдя до конца файла. Сокету это безразлично, как безразлично и настоящему
 * запуску адаптера.
 */
@DisplayName("Адаптер отладки: обмен по протоколу")
final class TransportTest {

    private static final long WAIT_SECONDS = 20;

    @Test
    @DisplayName("Сеанс целиком: точка сработала, кадры пришли, скрипт дошёл до конца")
    void wholeSession(@TempDir Path directory) throws Exception {
        Path script = directory.resolve("script.wdl");
        Files.writeString(script, """
                def greet(name) => "привет, " + name
                message = greet("мир")
                println(message)
                """, StandardCharsets.UTF_8);

        WdlDebugAdapter adapter = new WdlDebugAdapter();
        CompletableFuture<Integer> served;
        TestClient client = new TestClient();
        IDebugProtocolServer remote;
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket clientSide = new Socket(listener.getInetAddress(), listener.getLocalPort());
             Socket serverSide = listener.accept()) {
            served = CompletableFuture.supplyAsync(() -> {
                try {
                    return WdlDebugServer.serve(serverSide.getInputStream(),
                            serverSide.getOutputStream(), adapter);
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            });
            Launcher<IDebugProtocolServer> connection = DSPLauncher.createClientLauncher(
                    client, clientSide.getInputStream(), clientSide.getOutputStream());
            connection.startListening();
            remote = connection.getRemoteProxy();
            session(script, client, remote, adapter);
        }
        assertEquals(0, served.get(WAIT_SECONDS, TimeUnit.SECONDS),
                "сеанс кончился кодом возврата скрипта");
    }

    /** Сам разговор: от {@code initialize} до {@code disconnect}. */
    private void session(Path script, TestClient client, IDebugProtocolServer remote,
                         WdlDebugAdapter adapter) throws Exception {

        InitializeRequestArguments initialize = new InitializeRequestArguments();
        initialize.setAdapterID("wdl");
        initialize.setLinesStartAt1(true);
        assertTrue(remote.initialize(initialize).get(WAIT_SECONDS, TimeUnit.SECONDS)
                .getSupportsConfigurationDoneRequest());

        Map<String, Object> launch = new HashMap<>();
        launch.put("program", script.toString());
        remote.launch(launch).get(WAIT_SECONDS, TimeUnit.SECONDS);
        // Событие 'initialized' — разрешение расставлять точки, и приходит оно после
        // launch: до этой минуты сессии ещё нет.
        assertEquals("ok", client.take(client.ready, "разрешения ставить точки"));

        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(script.toString());
        SourceBreakpoint requested = new SourceBreakpoint();
        requested.setLine(3);
        SetBreakpointsArguments points = new SetBreakpointsArguments();
        points.setSource(source);
        points.setBreakpoints(new SourceBreakpoint[]{requested});
        SetBreakpointsResponse placed = remote.setBreakpoints(points)
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(placed.getBreakpoints()[0].isVerified());

        remote.configurationDone(new ConfigurationDoneArguments())
                .get(WAIT_SECONDS, TimeUnit.SECONDS);

        StoppedEventArguments stopped = client.take(client.stops, "остановки");
        assertEquals("breakpoint", stopped.getReason());

        StackTraceArguments frames = new StackTraceArguments();
        frames.setThreadId(stopped.getThreadId());
        StackTraceResponse trace = remote.stackTrace(frames).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(3, trace.getStackFrames()[0].getLine());
        assertEquals(script.getFileName().toString(),
                trace.getStackFrames()[0].getSource().getName());

        ContinueArguments go = new ContinueArguments();
        go.setThreadId(stopped.getThreadId());
        remote.continue_(go).get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(0, client.take(client.exits, "конца скрипта").getExitCode());
        assertTrue(client.text().contains("привет, мир"), "вывод: " + client.text());

        DisconnectArguments bye = new DisconnectArguments();
        bye.setTerminateDebuggee(true);
        remote.disconnect(bye).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, adapter.finished().get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    /** Клиент, который только запоминает. */
    private static final class TestClient implements IDebugProtocolClient {

        private final BlockingQueue<StoppedEventArguments> stops = new LinkedBlockingQueue<>();
        private final BlockingQueue<ExitedEventArguments> exits = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> ready = new LinkedBlockingQueue<>();
        private final StringBuilder output = new StringBuilder();

        @Override
        public void initialized() {
            ready.add("ok");
        }

        @Override
        public void stopped(StoppedEventArguments args) {
            stops.add(args);
        }

        @Override
        public void exited(ExitedEventArguments args) {
            exits.add(args);
        }

        @Override
        public void output(OutputEventArguments args) {
            synchronized (output) {
                output.append(args.getOutput());
            }
        }

        String text() {
            synchronized (output) {
                return output.toString();
            }
        }

        <T> T take(BlockingQueue<T> queue, String what) throws Exception {
            T event = queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            if (event == null) {
                throw new TimeoutException("не дождались " + what);
            }
            return event;
        }
    }
}
