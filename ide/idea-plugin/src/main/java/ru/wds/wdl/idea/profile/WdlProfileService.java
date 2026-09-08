package ru.wds.wdl.idea.profile;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Профили этого проекта: последний снятый и окно, в котором их показывают.
 * <p>
 * Служба принадлежит проекту, а не приложению: профили разных проектов между собой
 * не путаются, и подсказки в редакторе одного проекта не берут числа из другого.
 */
@Service(Service.Level.PROJECT)
public final class WdlProfileService {

    /** Идентификатор окна; он же в {@code plugin.xml}. */
    public static final String TOOL_WINDOW = "wdl Profile";

    /**
     * Один снятый профиль.
     *
     * @param title            подпись вкладки: имя скрипта и время прогона
     * @param report           сам отчёт
     * @param workingDirectory рабочий каталог запуска — от него разрешаются
     *                         относительные пути записей
     */
    public record Run(String title, ProfileReport report, Path workingDirectory) {
    }

    private final Project project;
    private volatile Run last;
    private volatile boolean hintsEnabled;

    public WdlProfileService(Project project) {
        this.project = project;
    }

    public static WdlProfileService getInstance(@NotNull Project project) {
        return project.getService(WdlProfileService.class);
    }

    /** Последний снятый профиль или {@code null}, если в этом сеансе ещё не снимали. */
    public @Nullable Run last() {
        return last;
    }

    /**
     * Показывать ли числа профиля прямо в редакторе.
     * <p>
     * Выключено по умолчанию: подсказки, оставшиеся от вчерашнего прогона, врут молча.
     */
    public boolean hintsEnabled() {
        return hintsEnabled;
    }

    public void setHintsEnabled(boolean enabled) {
        hintsEnabled = enabled;
    }

    /** Кладёт отчёт вкладкой в окно профиля и показывает его. */
    public void show(@NotNull Run run) {
        last = run;
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW);
        if (window == null) {
            return;
        }
        ContentManager contents = window.getContentManager();
        removePlaceholder(contents);
        Content content = ContentFactory.getInstance()
                .createContent(new ProfilePanel(project, run), run.title(), false);
        content.setCloseable(true);
        contents.addContent(content);
        contents.setSelectedContent(content);
        window.activate(null, true);
        // Подсказки в редакторе показывают последний профиль — а он только что сменился.
        ProfileInlays.refresh(project);
    }

    /**
     * Наполняет окно при первом открытии: последним профилем, если он есть, иначе
     * строкой о том, как его снять.
     */
    void attach(@NotNull ToolWindow window) {
        ContentManager contents = window.getContentManager();
        if (contents.getContentCount() > 0) {
            return;
        }
        Run current = last;
        Content content = current != null
                ? ContentFactory.getInstance().createContent(new ProfilePanel(project, current), current.title(), false)
                : placeholder();
        contents.addContent(content);
    }

    private Content placeholder() {
        Content content = ContentFactory.getInstance().createContent(
                ProfilePanel.hint("Профиль ещё не снят: запустите скрипт кнопкой «Профилировать»."),
                "Профиль", false);
        content.setCloseable(false);
        content.putUserData(PLACEHOLDER, Boolean.TRUE);
        return content;
    }

    private static final com.intellij.openapi.util.Key<Boolean> PLACEHOLDER =
            com.intellij.openapi.util.Key.create("wdl.profile.placeholder");

    private static void removePlaceholder(ContentManager contents) {
        for (Content content : contents.getContents()) {
            if (Boolean.TRUE.equals(content.getUserData(PLACEHOLDER))) {
                contents.removeContent(content, true);
            }
        }
    }
}
