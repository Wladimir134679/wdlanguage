package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.service.LanguageService;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сервер через настоящий JSON-RPC: клиент, поток сообщений, ответы.
 * <p>
 * Прямой вызов {@code WdlLanguageServer} проверил бы половину работы: вторая
 * половина — что ответ сериализуется, что уведомление доходит и что процесс
 * заканчивается по {@code exit}, а не висит.
 */
class ServerTest {

    private static final String URI = "file:///tmp/order.wdl";

    private static final String TEXT = """
            // сумма заказа
            def total(price, count) {
                return price * count;
            }

            class Rect(width, height) {
                def area() => width * height
            }

            println(total(120, 3))
            """;

    /** Снятие каталога стоит установки библиотек — одного на весь класс достаточно. */
    private static Catalog catalog;

    private WdlLanguageServer server;
    private LanguageServer client;
    private RecordingClient events;
    private PipedInputStream toServer;
    private PipedInputStream toClient;

    @TempDir
    Path workspace;

    @BeforeAll
    static void snapshotCatalog() {
        catalog = StandardCatalog.create();
    }

    @BeforeEach
    void connect() throws Exception {
        toServer = new PipedInputStream();
        toClient = new PipedInputStream();
        PipedOutputStream fromClient = new PipedOutputStream(toServer);
        PipedOutputStream fromServer = new PipedOutputStream(toClient);

        server = new WdlLanguageServer(LanguageService.of(catalog));
        var serverSide = LSPLauncher.createServerLauncher(server, toServer, fromServer);
        server.connect(serverSide.getRemoteProxy());
        serverSide.startListening();

        events = new RecordingClient();
        var clientSide = LSPLauncher.createClientLauncher(events, toClient, fromClient);
        clientSide.startListening();
        client = clientSide.getRemoteProxy();
    }

    @AfterEach
    void disconnect() throws Exception {
        toServer.close();
        toClient.close();
    }

    @Test
    @DisplayName("Сервер объявляет только то, что умеет, и заканчивается по exit")
    void lifecycle() throws Exception {
        InitializeResult result = client.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS);

        ServerCapabilities capabilities = result.getCapabilities();
        assertEquals(WdlLanguageServer.NAME, result.getServerInfo().getName());
        assertEquals(PositionEncodingKind.UTF16, capabilities.getPositionEncoding(),
                "единицы совпадают с нашими — пересчёта нет");
        assertEquals(TextDocumentSyncKind.Full,
                capabilities.getTextDocumentSync().getLeft());
        assertEquals(List.of("."), capabilities.getCompletionProvider().getTriggerCharacters());
        assertTrue(capabilities.getHoverProvider().getLeft());
        assertTrue(capabilities.getDefinitionProvider().getLeft());
        assertTrue(capabilities.getReferencesProvider().getLeft());
        assertTrue(capabilities.getDocumentSymbolProvider().getLeft());
        assertTrue(capabilities.getWorkspaceSymbolProvider().getLeft());
        assertNotNull(capabilities.getSignatureHelpProvider());
        assertEquals(Protocol.TOKEN_TYPES,
                capabilities.getSemanticTokensProvider().getLegend().getTokenTypes());
        // Того, чего ещё нет, сервер не обещает: клиент верит объявлению буквально.
        assertEquals(null, capabilities.getDocumentFormattingProvider());
        assertEquals(null, capabilities.getRenameProvider());

        client.shutdown().get(10, TimeUnit.SECONDS);
        client.exit();
        assertEquals(0, server.stopped().get(10, TimeUnit.SECONDS),
                "прощание по правилам — код возврата 0");
    }

    @Test
    @DisplayName("Диагностика приходит сама, с версией правки")
    void diagnosticsArePublished() throws Exception {
        client.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS);

        open(TEXT);
        PublishDiagnosticsParams clean = events.next();
        assertEquals(URI, clean.getUri());
        assertEquals(1, clean.getVersion());
        assertTrue(clean.getDiagnostics().isEmpty(), "пример разбирается без ошибок");

        change(2, "def total(count) {\n    return price *\n}\n");
        PublishDiagnosticsParams broken = events.next();
        assertEquals(2, broken.getVersion());
        assertFalse(broken.getDiagnostics().isEmpty());
        assertEquals("wdl", broken.getDiagnostics().get(0).getSource());

        client.getTextDocumentService().didClose(
                new DidCloseTextDocumentParams(new TextDocumentIdentifier(URI)));
        assertTrue(events.next().getDiagnostics().isEmpty(),
                "подчёркивания закрытого файла надо снять явно");
    }

    @Test
    @DisplayName("Дополнение, наведение, переход, употребления и структура файла")
    void answersQuestionsAboutTheDocument() throws Exception {
        client.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS);
        open(TEXT);
        events.next();

        List<CompletionItem> completion = client.getTextDocumentService()
                .completion(new CompletionParams(document(), at("println(total")))
                .get(10, TimeUnit.SECONDS)
                .getLeft();
        List<String> names = completion.stream().map(CompletionItem::getLabel).toList();
        assertTrue(names.contains("total"), names.toString());
        assertTrue(names.contains("println"), "встроенное имя приходит из каталога");

        Hover hover = client.getTextDocumentService()
                .hover(new HoverParams(document(), at("total(120, 3)")))
                .get(10, TimeUnit.SECONDS);
        assertNotNull(hover);
        String text = hover.getContents().getRight().getValue();
        assertTrue(text.startsWith("total(price, count)"), text);
        assertTrue(text.contains("сумма заказа"), "комментарий перед объявлением — это и есть справка");

        List<? extends Location> definition = client.getTextDocumentService()
                .definition(new DefinitionParams(document(), at("total(120, 3)")))
                .get(10, TimeUnit.SECONDS)
                .getLeft();
        assertEquals(1, definition.size());
        assertEquals(new Position(1, "def ".length()), definition.get(0).getRange().getStart());

        List<? extends Location> references = client.getTextDocumentService()
                .references(new ReferenceParams(document(), at("price * count"),
                        new ReferenceContext(true)))
                .get(10, TimeUnit.SECONDS);
        assertEquals(2, references.size(), "параметр объявлен в заголовке и прочитан в теле");

        List<DocumentSymbol> symbols = client.getTextDocumentService()
                .documentSymbol(new DocumentSymbolParams(document()))
                .get(10, TimeUnit.SECONDS)
                .stream()
                .map(either -> either.getRight())
                .toList();
        assertEquals(List.of("total", "Rect"), symbols.stream()
                .map(DocumentSymbol::getName).toList());
        assertEquals(List.of("area"), symbols.get(1).getChildren().stream()
                .map(DocumentSymbol::getName).toList());

        SemanticTokens tokens = client.getTextDocumentService()
                .semanticTokensFull(new SemanticTokensParams(document()))
                .get(10, TimeUnit.SECONDS);
        assertFalse(tokens.getData().isEmpty());
        assertEquals(0, tokens.getData().size() % 5, "поток идёт пятёрками");
    }

    @Test
    @DisplayName("Вопрос про неоткрытый документ — пустой ответ, а не отказ")
    void unknownDocumentIsNotAnError() throws Exception {
        client.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS);

        assertTrue(client.getTextDocumentService()
                .completion(new CompletionParams(document(), new Position(0, 0)))
                .get(10, TimeUnit.SECONDS).getLeft().isEmpty());
        assertEquals(null, client.getTextDocumentService()
                .hover(new HoverParams(document(), new Position(0, 0)))
                .get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Workspace folders дают completion и definition в соседнем модуле")
    void workspaceModulesAreAvailableOverProtocol() throws Exception {
        Path shapes = workspace.resolve("app/shapes.wdl");
        Files.createDirectories(shapes.getParent());
        Files.writeString(shapes, "class Circle() { def Circle.unit() => new Circle() }\n");
        InitializeParams initialize = new InitializeParams();
        initialize.setWorkspaceFolders(List.of(new org.eclipse.lsp4j.WorkspaceFolder(
                workspace.toUri().toString(), "test")));
        client.initialize(initialize).get(10, TimeUnit.SECONDS);

        String imported = "import app.shapes as shapes\nshapes.Circle.\n";
        open(imported);
        events.next();
        Position circleDot = position(imported, "Circle.", "Circle.".length());
        List<String> names = client.getTextDocumentService().completion(
                new CompletionParams(document(), circleDot)).get(10, TimeUnit.SECONDS).getLeft()
                .stream().map(CompletionItem::getLabel).toList();
        assertTrue(names.contains("unit"), names.toString());

        List<? extends Location> definition = client.getTextDocumentService().definition(
                new DefinitionParams(document(), position(imported, "Circle.", 1)))
                .get(10, TimeUnit.SECONDS).getLeft();
        assertEquals(shapes.toUri().toString(), definition.getFirst().getUri());
    }

    // --- вспомогательное ----------------------------------------------------

    private void open(String text) {
        client.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(URI, "wdl", 1, text)));
    }

    private void change(int version, String text) {
        client.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(URI, version),
                List.of(new TextDocumentContentChangeEvent(text))));
    }

    private static TextDocumentIdentifier document() {
        return new TextDocumentIdentifier(URI);
    }

    /** Позиция начала фрагмента в тексте примера. */
    private static Position at(String fragment) {
        int offset = TEXT.indexOf(fragment);
        if (offset < 0) {
            throw new AssertionError("нет фрагмента '" + fragment + "'");
        }
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (TEXT.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart);
    }

    private static Position position(String text, String fragment, int shift) {
        int offset = text.indexOf(fragment) + shift;
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < offset; index++) {
            if (text.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return new Position(line, offset - lineStart);
    }

    /** Клиент, который только запоминает пришедшее. */
    private static final class RecordingClient implements LanguageClient {

        private final BlockingQueue<PublishDiagnosticsParams> published =
                new LinkedBlockingQueue<>();

        PublishDiagnosticsParams next() throws InterruptedException {
            PublishDiagnosticsParams params = published.poll(10, TimeUnit.SECONDS);
            assertNotNull(params, "сервер обязан прислать диагностику сам");
            return params;
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            published.add(diagnostics);
        }

        @Override
        public void telemetryEvent(Object object) {
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
