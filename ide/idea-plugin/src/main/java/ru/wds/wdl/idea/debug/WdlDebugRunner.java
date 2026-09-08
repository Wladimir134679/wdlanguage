package ru.wds.wdl.idea.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.wds.wdl.idea.WdlAdapterPath;
import ru.wds.wdl.idea.WdlCommandLine;
import ru.wds.wdl.idea.WdlRunConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Что делает кнопка «Отладить»: поднимает адаптер и открывает сеанс.
 * <p>
 * Скрипт запускает <b>адаптер</b>, а не эта кнопка: движок, отладчик и запуск обязаны
 * быть одним процессом — иначе точку останова некуда ставить, а кадры не у кого
 * спрашивать. Поэтому обычная командная строка {@code wdl} здесь не строится вовсе,
 * и {@link RunProfileState} конфигурации не используется: что запускать, адаптер
 * узнаёт из запроса {@code launch}.
 */
public final class WdlDebugRunner extends GenericProgramRunner<RunnerSettings> {

    @Override
    public @NotNull String getRunnerId() {
        return "WdlDebugRunner";
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId)
                && profile instanceof WdlRunConfiguration;
    }

    @Override
    protected @Nullable RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                                       @NotNull ExecutionEnvironment environment)
            throws ExecutionException {
        WdlRunConfiguration configuration = (WdlRunConfiguration) environment.getRunProfile();
        Project project = environment.getProject();
        AdapterProcess process = new AdapterProcess(command(project, configuration));
        List<String> ignored = new ArrayList<>();
        Map<String, Object> launch = launchOptions(configuration, ignored);

        XDebugSession session = XDebuggerManager.getInstance(project).startSession(environment,
                new XDebugProcessStarter() {
                    @Override
                    public @NotNull XDebugProcess start(@NotNull XDebugSession started) {
                        return new WdlDebugProcess(started, process, launch);
                    }
                });
        if (!ignored.isEmpty()) {
            // Молча выбросить чужие ключи нельзя: человек задал их в конфигурации
            // и вправе знать, что при отладке они не действуют.
            session.reportMessage("При отладке не передаются параметры интерпретатора: "
                    + String.join(" ", ignored), MessageType.WARNING);
        }
        return session.getRunContentDescriptor();
    }

    /** Командная строка адаптера: та же уловка, что и у сервера, — java по lib. */
    private static GeneralCommandLine command(Project project, WdlRunConfiguration configuration)
            throws ExecutionException {
        Path adapter = WdlAdapterPath.find(project);
        if (adapter == null) {
            throw new ExecutionException("wdl-dap не найден: соберите его задачей "
                    + ":wdl-dap:installDist или укажите путь переменной WDL_DAP_HOME");
        }
        GeneralCommandLine command = WdlCommandLine.create(adapter, "ru.wds.wdl.dap.Main");
        // Рабочий каталог задаёт конфигурация: от него скрипт считает относительные пути,
        // а сменить его у живой JVM нельзя — значит, задать надо при запуске процесса.
        command.withWorkDirectory(configuration.directory().toFile());
        return command;
    }

    /**
     * Что просить у адаптера: файл, корень импортов, аргументы — плюс пределы, если
     * они заданы параметрами интерпретатора.
     * <p>
     * Пределы переносятся, потому что они меняют поведение скрипта: запуск с
     * {@code --timeout=5} и отладка без него — это разные запуски. Остальные ключи
     * ({@code --metrics}, {@code --profile}, {@code --ast}) к сеансу отладки отношения
     * не имеют и называются человеку списком, а не выбрасываются молча.
     */
    private static Map<String, Object> launchOptions(WdlRunConfiguration configuration,
                                                     List<String> ignored) {
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("program", configuration.scriptFile().toString());
        launch.put("projectRoot", configuration.projectRoot().toString());
        launch.put("args", configuration.arguments());
        List<String> flags = configuration.interpreterFlags();
        for (int index = 0; index < flags.size(); index++) {
            String flag = flags.get(index);
            String name = flag.contains("=") ? flag.substring(0, flag.indexOf('=')) : flag;
            String key = switch (name) {
                case "--max-steps" -> "maxSteps";
                case "--timeout" -> "timeout";
                case "--max-threads" -> "maxThreads";
                default -> null;
            };
            if (key == null) {
                ignored.add(flag);
                continue;
            }
            String value = flag.contains("=") ? flag.substring(flag.indexOf('=') + 1)
                    : (index + 1 < flags.size() ? flags.get(++index) : "");
            try {
                launch.put(key, Double.parseDouble(value));
            } catch (NumberFormatException notANumber) {
                ignored.add(flag + " " + value);
            }
        }
        return launch;
    }
}
