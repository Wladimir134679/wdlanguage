package ru.wds.wdl.idea.profile;

import com.intellij.execution.Executor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindowId;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * Кнопка «Профилировать» рядом с «Запустить».
 * <p>
 * Профилирование — действие, а не настройка конфигурации: одна и та же конфигурация
 * запускается обычным способом и профилируется, без дублирования настроек и без риска
 * забыть снятый флажок и получить медленный запуск там, где ждали быстрый.
 * Флаг {@code --profile-out} дописывает сама конфигурация, увидев наш идентификатор.
 */
public final class WdlProfileExecutor extends Executor {

    /** Идентификатор действия: по нему конфигурация узнаёт, что её просят профилировать. */
    public static final String ID = "wdl.profile";

    @Override public @NotNull String getId() {
        return ID;
    }

    // Вывод скрипта идёт в обычное окно запуска: профиль отличается тем, что показывает
    // потом, а не тем, куда печатает println.
    @Override public @NotNull String getToolWindowId() {
        return ToolWindowId.RUN;
    }

    @Override public @NotNull Icon getToolWindowIcon() {
        return AllIcons.Actions.Profile;
    }

    @Override public @NotNull Icon getIcon() {
        return AllIcons.Actions.Profile;
    }

    @Override public Icon getDisabledIcon() {
        return IconLoader.getDisabledIcon(getIcon());
    }

    @Override public String getDescription() {
        return "Выполнить скрипт wdl и показать, где он проводит время";
    }

    @Override public @NotNull String getActionName() {
        return "Профилировать";
    }

    @Override public @NotNull String getStartActionText() {
        return "Профилировать";
    }

    @Override public @NotNull String getStartActionText(@NotNull String configuration) {
        return "Профилировать " + shortenNameIfNeeded(configuration);
    }

    @Override public @NotNull String getContextActionId() {
        return "WdlProfileContext";
    }

    @Override public String getHelpId() {
        return null;
    }
}
