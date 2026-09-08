package ru.wds.wdl.idea.profile;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

/**
 * «Открыть профиль wdl…» — показать в окне отчёт, снятый раньше.
 * <p>
 * Нужно затем, что {@code --profile-out} снимают не только из IDE: файл приходит
 * из сборки или с чужой машины, и читать его глазами как JSON — работа не для человека.
 */
public final class OpenProfileAction extends DumbAwareAction {

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getData(CommonDataKeys.PROJECT) != null);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getData(CommonDataKeys.PROJECT);
        if (project == null) {
            return;
        }
        VirtualFile chosen = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                        .withTitle("Отчёт профилировщика wdl"),
                project, null);
        if (chosen == null) {
            return;
        }
        Path file = chosen.toNioPath();
        ProfileReport report;
        try {
            report = ProfileFormat.read(file);
        } catch (IOException e) {
            WdlProfileSession.notify(project, "Профиль не прочитан", e.getMessage(), NotificationType.WARNING);
            return;
        }
        // Относительные пути в чужом отчёте считаем от каталога самого файла: где
        // лежал профиль, там чаще всего лежит и то, о чём он.
        Path directory = file.getParent();
        WdlProfileService.getInstance(project).show(new WdlProfileService.Run(
                chosen.getName(), report, directory == null ? file : directory));
    }
}
