package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Рабочая папка: пока ничего.
 * <p>
 * Клиент шлёт эти уведомления сам, без спроса, и молчать на них он не даст —
 * метод обязан быть. Смысла в них появится ровно столько, сколько появится
 * межфайловых связей: пока сервер отвечает только про открытый файл, ни настройка,
 * ни правка файла на диске ни на что не влияют.
 */
final class WdlWorkspaceService implements WorkspaceService {

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // Настроек у сервера нет.
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // Индекса рабочей папки нет: отвечаем только про открытые документы.
    }
}
