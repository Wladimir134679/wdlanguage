package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.PositionEncodingKind;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import ru.wds.wdl.tools.service.LanguageService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Языковой сервер wdl: жизненный цикл и объявление того, что он умеет.
 * <p>
 * <b>Возможность объявляется только после того, как она сделана.</b> Клиент верит
 * объявлению буквально: обещанное форматирование, которого нет, он покажет пунктом
 * меню, а в ответ получит тишину — и виноват будет сервер, а не клиент.
 * <p>
 * Смысл языка живёт в {@link LanguageService}; здесь — перевод и порядок вызовов.
 */
public final class WdlLanguageServer implements LanguageServer, LanguageClientAware {

    /** Что показывает клиент в списке своих серверов. */
    public static final String NAME = "wdl-lsp";

    private final LanguageService service;
    private final WdlTextDocumentService documents;
    private final WdlWorkspaceService workspace;
    private final CompletableFuture<Integer> stopped = new CompletableFuture<>();

    private volatile boolean shutdownRequested;

    public WdlLanguageServer(LanguageService service) {
        this.service = Objects.requireNonNull(service, "service");
        this.documents = new WdlTextDocumentService(service);
        this.workspace = new WdlWorkspaceService(service);
    }

    @Override
    public void connect(LanguageClient client) {
        documents.connect(client);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        workspace.initialize(params);
        ServerCapabilities capabilities = new ServerCapabilities();
        // Единицы позиций совпадают с нашими без пересчёта: Span — индексы UTF-16.
        capabilities.setPositionEncoding(PositionEncodingKind.UTF16);
        // Правка целым текстом: разбор файла в тысячу строк дешевле, чем аккуратное
        // применение кусков, — а ошибиться в них можно так, что дерево разойдётся
        // с текстом и этого никто не заметит.
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setCompletionProvider(new CompletionOptions(false, List.of(".")));
        capabilities.setHoverProvider(true);
        capabilities.setSignatureHelpProvider(new SignatureHelpOptions(List.of("("), List.of(",")));
        capabilities.setDefinitionProvider(true);
        capabilities.setReferencesProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setWorkspaceSymbolProvider(true);
        capabilities.setSemanticTokensProvider(
                new SemanticTokensWithRegistrationOptions(Protocol.LEGEND, true));
        return CompletableFuture.completedFuture(
                new InitializeResult(capabilities, new ServerInfo(NAME, version())));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        service.closeAll();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // По спецификации: выход без shutdown — это ошибка, и код возврата другой.
        stopped.complete(shutdownRequested ? 0 : 1);
    }

    /** Код возврата процесса; завершается, когда клиент прислал {@code exit}. */
    public CompletableFuture<Integer> stopped() {
        return stopped;
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return documents;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspace;
    }

    public LanguageService service() {
        return service;
    }

    private static String version() {
        String version = WdlLanguageServer.class.getPackage().getImplementationVersion();
        return version == null ? "разработка" : version;
    }
}
