package ru.wds.wdl.idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Весь плагин целиком.
 * <p>
 * Открылся файл {@code .wdl} — поднять сервер, если он ещё не поднят. Всё остальное
 * платформа делает сама: подчёркивания, дополнение, переход, структура файла
 * и подсветка приходят по протоколу, и повторять эти правила здесь ни к чему —
 * они уже записаны один раз, в {@code wdl-tools}.
 * <p>
 * Клиент LSP есть только в платных IDE JetBrains; в Community этого API нет,
 * и плагин туда не ставится (см. зависимость от {@code com.intellij.modules.ultimate}
 * в {@code plugin.xml}).
 */
final class WdlLspServerSupportProvider implements LspServerSupportProvider {

    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file,
                           @NotNull LspServerStarter starter) {
        if (!isWdl(file)) {
            return;
        }
        Path server = WdlServerPath.find(project);
        if (server == null) {
            // Сервера нет — редактор остаётся обычным текстовым. Это хуже подсветки,
            // но лучше окна с ошибкой на каждый открытый файл.
            return;
        }
        starter.ensureServerStarted(new WdlLspServerDescriptor(project, server));
    }

    static boolean isWdl(@NotNull VirtualFile file) {
        return "wdl".equalsIgnoreCase(file.getExtension());
    }
}
