package ru.wds.wdl.idea;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Один сервер на проект.
 * <p>
 * Именно на проект, а не на файл: сервер помнит открытые документы и однажды
 * станет помнить рабочую папку целиком, а поднимать его на каждый файл значило бы
 * платить снятием каталога за каждую вкладку.
 */
final class WdlLspServerDescriptor extends ProjectWideLspServerDescriptor {

    private final Path server;

    WdlLspServerDescriptor(@NotNull Project project, @NotNull Path server) {
        super(project, "wdl");
        this.server = server;
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return WdlLspServerSupportProvider.isWdl(file);
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() {
        GeneralCommandLine command = new GeneralCommandLine(server.toString(), "--stdio");
        // Сообщения протокола — UTF-8 по спецификации, и русский текст диагностики
        // приходит в них же.
        command.setCharset(StandardCharsets.UTF_8);
        command.withWorkDirectory(getProject().getBasePath());
        return command;
    }
}
