package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.tools.service.LanguageService;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Запуск сеанса: чем он заканчивается.
 */
class MainTest {

    @Test
    @DisplayName("Сеанс кончается по exit, а не по закрытию потока ввода")
    void exitEndsTheSession() throws Exception {
        PipedInputStream toServer = new PipedInputStream();
        PipedInputStream toClient = new PipedInputStream();
        PipedOutputStream fromClient = new PipedOutputStream(toServer);
        PipedOutputStream fromServer = new PipedOutputStream(toClient);

        ExecutorService session = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> code = session.submit(
                    () -> Main.serve(toServer, fromServer, LanguageService.withoutCatalog()));

            var clientSide = LSPLauncher.createClientLauncher(new SilentClient(), toClient,
                    fromClient);
            clientSide.startListening();
            LanguageServer server = clientSide.getRemoteProxy();

            server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS);
            server.shutdown().get(10, TimeUnit.SECONDS);
            server.exit();

            // Поток ввода при этом остаётся открытым — так делает и IntelliJ IDEA.
            assertEquals(0, code.get(10, TimeUnit.SECONDS));
        } finally {
            session.shutdownNow();
            toServer.close();
            toClient.close();
        }
    }

    /** Клиенту в этом тесте слушать нечего: важен только конец сеанса. */
    private static final class SilentClient implements LanguageClient {

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        }

        @Override
        public void showMessage(MessageParams message) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
        }
    }
}
