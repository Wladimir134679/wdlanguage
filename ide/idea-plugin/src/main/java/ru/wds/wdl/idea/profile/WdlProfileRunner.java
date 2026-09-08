package ru.wds.wdl.idea.profile;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.RunContentDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.wds.wdl.idea.WdlRunConfiguration;

/**
 * Что делает кнопка «Профилировать»: тот же запуск, только конфигурация знает,
 * зачем её позвали.
 * <p>
 * Без своего раннера платформа кнопку не покажет вовсе: {@link WdlProfileExecutor}
 * сам ничего не запускает — он лишь называет действие.
 */
public final class WdlProfileRunner extends GenericProgramRunner<RunnerSettings> {

    @Override public @NotNull String getRunnerId() {
        return "WdlProfileRunner";
    }

    @Override public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return WdlProfileExecutor.ID.equals(executorId) && profile instanceof WdlRunConfiguration;
    }

    @Override protected @Nullable RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                                                 @NotNull ExecutionEnvironment environment)
            throws ExecutionException {
        ExecutionResult result = state.execute(environment.getExecutor(), this);
        if (result == null) {
            return null;
        }
        return new RunContentBuilder(result, environment).showRunContent(environment.getContentToReuse());
    }
}
