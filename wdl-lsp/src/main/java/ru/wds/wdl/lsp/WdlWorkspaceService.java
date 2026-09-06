package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;
import ru.wds.wdl.tools.service.LanguageService;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Рабочая папка: пока ничего.
 * <p>
 * Клиент шлёт эти уведомления сам, без спроса, и молчать на них он не даст —
 * метод обязан быть. Смысла в них появится ровно столько, сколько появится
 * межфайловых связей: пока сервер отвечает только про открытый файл, ни настройка,
 * ни правка файла на диске ни на что не влияют.
 */
final class WdlWorkspaceService implements WorkspaceService {

    private final LanguageService service;
    private List<Path> workspaceRoots = List.of();

    WdlWorkspaceService(LanguageService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    void initialize(InitializeParams params) {
        List<Path> roots = pathsOf(params.getWorkspaceFolders());
        if (roots.isEmpty()) {
            Path root = pathOf(params.getRootUri());
            roots = root == null ? List.of() : List.of(root);
        }
        workspaceRoots = List.copyOf(roots);
        service.configureWorkspace(configuredRoots(params.getInitializationOptions()));
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        service.configureWorkspace(configuredRoots(params == null ? null : params.getSettings()));
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        if (params == null || params.getChanges() == null) {
            return;
        }
        for (FileEvent event : params.getChanges()) {
            Path changed = pathOf(event.getUri());
            if (changed != null) {
                service.workspace().changedOnDisk(changed);
            }
        }
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>,
            List<? extends org.eclipse.lsp4j.WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        List<SymbolInformation> found = new ArrayList<>();
        for (ru.wds.wdl.tools.service.WorkspaceSymbol symbol : service.workspaceSymbols(
                params == null ? "" : params.getQuery())) {
            ru.wds.wdl.source.Source source = service.source(symbol.document());
            if (source != null) {
                found.add(Protocol.workspaceSymbol(source, symbol));
            }
        }
        return CompletableFuture.completedFuture(Either.forLeft(found));
    }

    /** Настройка wdl.sourceRoots — URI/пути; без неё source roots равны workspace folders. */
    private Collection<Path> configuredRoots(Object settings) {
        List<Path> configured = new ArrayList<>();
        if (settings instanceof Map<?, ?> map) {
            Object wdl = map.get("wdl");
            Map<?, ?> values = wdl instanceof Map<?, ?> nested ? nested : map;
            Object sourceRoots = values.get("sourceRoots");
            if (sourceRoots instanceof Collection<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof String text) {
                        Path path = configuredPath(text);
                        if (path != null) {
                            configured.add(path);
                        }
                    }
                }
            }
        }
        return configured.isEmpty() ? workspaceRoots : List.copyOf(configured);
    }

    private Path configuredPath(String text) {
        Path uriPath = pathOf(text);
        if (uriPath != null) {
            return uriPath;
        }
        try {
            Path path = Path.of(text);
            return path.isAbsolute() || workspaceRoots.isEmpty() ? path
                    : workspaceRoots.getFirst().resolve(path);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static List<Path> pathsOf(List<WorkspaceFolder> folders) {
        if (folders == null) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (WorkspaceFolder folder : folders) {
            Path path = pathOf(folder.getUri());
            if (path != null) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    private static Path pathOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            return "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
