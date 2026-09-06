package ru.wds.wdl.idea;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport;
import com.intellij.psi.PsiFile;
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

    private final LspCustomization customization = new LspCustomization() {
        private final LspSemanticTokensSupport semanticTokens = new LspSemanticTokensSupport() {
            @Override
            public boolean shouldAskServerForSemanticTokens(@NotNull PsiFile file) {
                // IDEA's default only enables TEXT/textmate. Our Run PSI uses language wdl,
                // but still relies entirely on the server for syntax and semantic colors.
                return file.getLanguage().is(WdlLanguage.INSTANCE);
            }
        };

        @Override
        public @NotNull LspSemanticTokensSupport getSemanticTokensCustomizer() {
            return semanticTokens;
        }
    };

    @Override
    public @NotNull LspCustomization getLspCustomization() {
        return customization;
    }

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
        GeneralCommandLine command = WdlCommandLine.create(server, "ru.wds.wdl.lsp.Main");
        command.addParameter("--stdio");
        // Сообщения протокола — UTF-8 по спецификации, и русский текст диагностики
        // приходит в них же.
        command.setCharset(StandardCharsets.UTF_8);
        command.withWorkDirectory(getProject().getBasePath());
        return command;
    }
}
