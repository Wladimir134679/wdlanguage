package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.catalog.Suggestion;
import ru.wds.wdl.tools.service.Document;
import ru.wds.wdl.tools.service.DocumentId;
import ru.wds.wdl.tools.service.CallSignature;
import ru.wds.wdl.tools.service.Hover;
import ru.wds.wdl.tools.service.LanguageService;
import ru.wds.wdl.tools.service.Outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Открытые документы и вопросы про них — на языке протокола.
 * <p>
 * Здесь нет ни одного правила языка: каждый метод переводит запрос в смещение,
 * задаёт вопрос {@link LanguageService} и переводит ответ обратно. Если в этом файле
 * однажды появится {@code if} про устройство wdl — значит, чего-то не хватает
 * в сервисе, и дописывать надо там: тем же ответом пользуются плагин IDEA
 * и встроенный редактор, а этого файла у них нет.
 */
final class WdlTextDocumentService implements TextDocumentService {

    private final LanguageService service;
    private volatile LanguageClient client;

    WdlTextDocumentService(LanguageService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    void connect(LanguageClient client) {
        this.client = client;
    }

    // --- жизненный цикл документа -------------------------------------------

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem item = params.getTextDocument();
        publish(service.open(DocumentId.of(item.getUri()), item.getVersion(), item.getText()));
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (changes.isEmpty()) {
            return;
        }
        // Синхронизация объявлена полной: правка приходит целым текстом.
        String text = changes.get(changes.size() - 1).getText();
        publish(service.change(DocumentId.of(params.getTextDocument().getUri()),
                params.getTextDocument().getVersion(), text));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        service.close(DocumentId.of(uri));
        // Подчёркивания закрытого файла надо снять явно: сам клиент их не забудет.
        LanguageClient target = client;
        if (target != null) {
            target.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Открытый текст всё ещё сильнее, но watcher может не прийти для закрытого файла.
        java.nio.file.Path path = pathOf(params.getTextDocument().getUri());
        if (path != null) {
            service.workspace().changedOnDisk(path);
        }
    }

    // --- вопросы ------------------------------------------------------------

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            CompletionParams params) {
        Document document = document(params.getTextDocument().getUri());
        if (document == null) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        int offset = Protocol.offset(document.source(), params.getPosition());
        List<CompletionItem> items = new ArrayList<>();
        for (Suggestion suggestion : service.complete(document.id(), offset)) {
            items.add(Protocol.completion(suggestion));
        }
        return CompletableFuture.completedFuture(Either.forLeft(items));
    }

    @Override
    public CompletableFuture<org.eclipse.lsp4j.Hover> hover(HoverParams params) {
        Document document = document(params.getTextDocument().getUri());
        if (document == null) {
            return CompletableFuture.completedFuture(null);
        }
        Hover hover = service.hover(document.id(),
                Protocol.offset(document.source(), params.getPosition()));
        return CompletableFuture.completedFuture(
                hover == null ? null : Protocol.hover(document.source(), hover));
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        Document document = document(params.getTextDocument().getUri());
        if (document == null) {
            return CompletableFuture.completedFuture(null);
        }
        CallSignature signature = service.signatureHelp(document.id(),
                Protocol.offset(document.source(), params.getPosition()));
        return CompletableFuture.completedFuture(signature == null ? null : Protocol.signature(signature));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
            definition(DefinitionParams params) {
        Document document = document(params.getTextDocument().getUri());
        if (document == null) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        ru.wds.wdl.tools.service.Location found = service.definition(document.id(),
                Protocol.offset(document.source(), params.getPosition()));
        List<Location> locations = found == null ? List.of()
                : List.of(Protocol.location(found.document().uri(), sourceOf(found.document(), document.source()),
                        found.span()));
        return CompletableFuture.completedFuture(Either.forLeft(locations));
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        Document document = document(params.getTextDocument().getUri());
        if (document == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        boolean withDeclaration = params.getContext() != null
                && params.getContext().isIncludeDeclaration();
        List<Location> locations = new ArrayList<>();
        for (ru.wds.wdl.tools.service.Location found : service.references(document.id(),
                Protocol.offset(document.source(), params.getPosition()), withDeclaration)) {
            locations.add(Protocol.location(found.document().uri(), sourceOf(found.document(), document.source()),
                    found.span()));
        }
        return CompletableFuture.completedFuture(locations);
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        Document document = document(params.getTextDocument().getUri());
        if (document == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<Either<SymbolInformation, DocumentSymbol>> symbols = new ArrayList<>();
        for (Outline outline : service.outline(document.id())) {
            symbols.add(Either.forRight(Protocol.documentSymbol(document.source(), outline)));
        }
        return CompletableFuture.completedFuture(symbols);
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        Document document = document(params.getTextDocument().getUri());
        if (document == null) {
            return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
        }
        return CompletableFuture.completedFuture(Protocol.semanticTokens(document.source(),
                service.highlight(document.id())));
    }

    // --- вспомогательное ----------------------------------------------------

    private Document document(String uri) {
        return uri == null ? null : service.document(DocumentId.of(uri));
    }

    private Source sourceOf(DocumentId id, Source fallback) {
        Source source = service.source(id);
        return source == null ? fallback : source;
    }

    private static java.nio.file.Path pathOf(String uri) {
        try {
            java.net.URI parsed = java.net.URI.create(uri);
            return "file".equalsIgnoreCase(parsed.getScheme()) ? java.nio.file.Path.of(parsed) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Отправляет диагностику вместе с версией документа: клиент сам отбросит ответ,
     * если успел напечатать ещё букву.
     */
    private void publish(Document document) {
        LanguageClient target = client;
        if (target == null || document == null) {
            return;
        }
        Source source = document.source();
        List<org.eclipse.lsp4j.Diagnostic> found = new ArrayList<>();
        for (ru.wds.wdl.diagnostic.Diagnostic diagnostic : service.diagnostics(document.id())) {
            found.add(Protocol.diagnostic(source, diagnostic));
        }
        PublishDiagnosticsParams message =
                new PublishDiagnosticsParams(document.id().uri(), found);
        message.setVersion((int) document.version());
        target.publishDiagnostics(message);
    }
}
