package ru.wds.wdl.idea.profile;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Один прогон под кнопкой «Профилировать»: куда {@code wdl} положит отчёт и кто его
 * оттуда возьмёт.
 * <p>
 * Обмен — файлом, а не протоколом: {@code --profile-out} уже умеет всё, что нужно,
 * и пишет отчёт даже у скрипта, который упал. Сокет добавил бы третью движущуюся
 * часть там, где хватает файла во временном каталоге.
 */
public final class WdlProfileSession {

    private static final Logger LOG = Logger.getInstance(WdlProfileSession.class);
    private static final String NOTIFICATIONS = "wdl";

    private WdlProfileSession() {
    }

    /** Временный файл под отчёт этого прогона. */
    public static Path createReportFile() throws ExecutionException {
        try {
            return FileUtil.createTempFile("wdl-profile", ".json", true).toPath();
        } catch (IOException e) {
            throw new ExecutionException("Не удалось создать файл под профиль: " + e.getMessage(), e);
        }
    }

    /**
     * Забирает отчёт, когда процесс закончился.
     *
     * @param title подпись прогона — по ней вкладка отличается от соседней
     */
    public static void collectOn(@NotNull ProcessHandler handler, @NotNull Project project,
                                 @NotNull Path report, @NotNull Path workingDirectory,
                                 @NotNull String title) {
        handler.addProcessListener(new ProcessListener() {
            @Override public void processTerminated(@NotNull ProcessEvent event) {
                collect(project, report, workingDirectory, title);
            }
        });
    }

    private static void collect(Project project, Path report, Path workingDirectory, String title) {
        ProfileReport parsed;
        try {
            parsed = ProfileFormat.read(report);
        } catch (IOException e) {
            // Не поломка запуска: скрипт мог быть убит до записи отчёта, и говорить
            // об этом надо словами, а не исключением в логе.
            notify(project, "Профиль не получен", e.getMessage(), NotificationType.WARNING);
            return;
        } finally {
            delete(report);
        }
        if (parsed.isEmpty()) {
            notify(project, "Профиль пуст",
                    "Скрипт не дошёл до выполнения — вызовов не было. Посмотрите ошибки разбора в окне запуска.",
                    NotificationType.INFORMATION);
            return;
        }
        ApplicationManager.getApplication().invokeLater(
                () -> WdlProfileService.getInstance(project).show(
                        new WdlProfileService.Run(title, parsed, workingDirectory)),
                project.getDisposed());
    }

    private static void delete(Path report) {
        try {
            Files.deleteIfExists(report);
        } catch (IOException e) {
            LOG.warn("Не удалось удалить временный профиль " + report, e);
        }
    }

    static void notify(Project project, String title, String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
                .createNotification(title, text, type)
                .notify(project);
    }
}
