package ru.wds.wdl.idea;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class WdlRunConfigurationProducer extends LazyRunConfigurationProducer<WdlRunConfiguration> implements DumbAware {
    @Override public @NotNull ConfigurationFactory getConfigurationFactory() {
        return ConfigurationTypeUtil.findConfigurationType(WdlRunConfigurationType.class).getConfigurationFactories()[0];
    }
    private static VirtualFile file(ConfigurationContext context) {
        PsiElement location = context.getPsiLocation();
        if (location == null || location.getContainingFile() == null) return null;
        VirtualFile file = location.getContainingFile().getVirtualFile();
        return file != null && "wdl".equalsIgnoreCase(file.getExtension()) ? file : null;
    }
    @Override protected boolean setupConfigurationFromContext(@NotNull WdlRunConfiguration c,
            @NotNull ConfigurationContext context, @NotNull Ref<PsiElement> source) {
        VirtualFile file = file(context);
        if (file == null) return false;
        c.target = file.getPath();
        c.workingDirectory = "";
        c.setName(file.getName());
        return true;
    }
    @Override public boolean isConfigurationFromContext(@NotNull WdlRunConfiguration c, @NotNull ConfigurationContext context) {
        VirtualFile file = file(context);
        return file != null && c.target.equals(file.getPath());
    }
}
