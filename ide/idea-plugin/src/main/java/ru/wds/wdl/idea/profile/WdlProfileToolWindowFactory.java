package ru.wds.wdl.idea.profile;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Окно «wdl Profile» внизу: вкладка на прогон.
 * <p>
 * Наполняет его служба проекта — она же кладёт туда новые прогоны, — а фабрика лишь
 * показывает то, что уже снято, или строку о том, как это сделать.
 */
public final class WdlProfileToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        WdlProfileService.getInstance(project).attach(window);
    }
}
