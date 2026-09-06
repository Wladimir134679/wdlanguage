package ru.wds.wdl.idea;

import com.intellij.execution.configurations.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class WdlRunConfigurationType extends ConfigurationTypeBase {
    public WdlRunConfigurationType() {
        super("WdlRunConfiguration", "wdl", "Запуск файла или проекта wdl", AllIcons.Actions.Execute);
        addFactory(new ConfigurationFactory(this) {
            @Override public @NotNull String getId() { return "wdl"; }
            @Override public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
                return new WdlRunConfiguration(project, this, "wdl");
            }
        });
    }
}
