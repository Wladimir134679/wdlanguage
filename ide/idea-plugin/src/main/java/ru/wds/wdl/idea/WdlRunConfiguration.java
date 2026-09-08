package ru.wds.wdl.idea;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import com.intellij.util.execution.ParametersListUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import ru.wds.wdl.idea.profile.WdlProfileExecutor;
import ru.wds.wdl.idea.profile.WdlProfileSession;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WdlRunConfiguration extends RunConfigurationBase<RunConfigurationOptions> {
    String target = "";
    String workingDirectory = "";
    String interpreterOptions = "";
    String scriptArguments = "";
    String interpreter = "";

    WdlRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    /**
     * Рабочий каталог запуска.
     * <p>
     * Он же — то, от чего разрешаются относительные пути в отчёте профилировщика:
     * процесс запускался отсюда, и {@code wdl} назвал главный файл так, как его дали
     * в командной строке.
     */
    public Path directory() {
        Path root = WdlProjectRoot.of(getProject());
        return workingDirectory.isBlank() ? root : root.resolve(workingDirectory).normalize();
    }

    /**
     * Корень импортов: он же корень проекта IDEA.
     * <p>
     * Один и тот же для запуска, анализа и отладки — иначе {@code import lib.math}
     * находил бы в редакторе один файл, а в запуске другой.
     */
    public Path projectRoot() {
        return WdlProjectRoot.of(getProject());
    }

    /**
     * Файл, который будет выполнен: цель, разрешённая от рабочего каталога, а каталог
     * — как {@code main.wdl} внутри него. То же правило, что у консольного {@code wdl}.
     */
    public Path scriptFile() {
        Path file = directory().resolve(target).normalize();
        return Files.isDirectory(file) ? file.resolve("main.wdl") : file;
    }

    /** Аргументы скрипта: то, что он увидит как {@code args}. */
    public java.util.List<String> arguments() {
        return ParametersListUtil.parse(scriptArguments);
    }

    /** Параметры интерпретатора из настроек конфигурации. */
    public java.util.List<String> interpreterFlags() {
        return options();
    }

    @Override public void checkConfiguration() throws RuntimeConfigurationException {
        try {
            options();
            if (target.isBlank()) throw new IllegalArgumentException("Укажите файл или каталог проекта");
            if (!Files.isDirectory(directory())) throw new IllegalArgumentException("Рабочий каталог не существует");
            Path file = directory().resolve(target);
            if (Files.isDirectory(file)) file = file.resolve("main.wdl");
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Файл не найден: " + file);
            if (WdlCommandLine.findCli(getProject(), interpreter) == null)
                throw new IllegalArgumentException("wdl не найден: выполните installWdl или укажите путь к bin/wdl");
        } catch (IllegalArgumentException e) {
            throw new RuntimeConfigurationError(e.getMessage());
        }
    }

    private java.util.List<String> options() {
        var options = ParametersListUtil.parse(interpreterOptions);
        for (String option : options) {
            if (option.equals("--project-root") || option.startsWith("--project-root="))
                throw new IllegalArgumentException("Корень импортов задаёт проект IDEA; удалите --project-root из параметров wdl");
            if (option.equals("--"))
                throw new IllegalArgumentException("Параметр -- добавляется автоматически; используйте поле аргументов скрипта");
        }
        return options;
    }

    public GeneralCommandLine createCommandLine(Path launcher) {
        return createCommandLine(launcher, null);
    }

    /**
     * Командная строка запуска; {@code profileOut} — файл, куда просят положить профиль.
     * <p>
     * Флаг дописывается здесь, а не хранится в настройках: профилирование — действие,
     * и одна и та же конфигурация обязана запускаться обычным способом без риска
     * забыть снятый флажок.
     */
    public GeneralCommandLine createCommandLine(Path launcher, Path profileOut) {
        GeneralCommandLine command = WdlCommandLine.create(launcher, "ru.wds.wdl.cli.Main");
        command.withWorkDirectory(directory().toFile());
        command.addParameters(options());
        if (profileOut != null) {
            command.addParameters("--profile-out", profileOut.toString());
        }
        command.addParameters("--project-root", WdlProjectRoot.of(getProject()).toString());
        command.addParameter("--");
        command.addParameter(directory().resolve(target).normalize().toString());
        command.addParameters(ParametersListUtil.parse(scriptArguments));
        return command;
    }

    @Override public @NotNull SettingsEditor<WdlRunConfiguration> getConfigurationEditor() {
        return new WdlRunSettingsEditor();
    }

    @Override public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        boolean profiling = WdlProfileExecutor.ID.equals(executor.getId());
        return new CommandLineState(environment) {
            @Override protected @NotNull ProcessHandler startProcess() throws ExecutionException {
                Path launcher = WdlCommandLine.findCli(getProject(), interpreter);
                if (launcher == null) throw new ExecutionException("wdl не найден; выполните installWdl");
                Path profileOut = profiling ? WdlProfileSession.createReportFile() : null;
                GeneralCommandLine command = createCommandLine(launcher, profileOut);
                OSProcessHandler handler = new KillableColoredProcessHandler(command);
                ProcessTerminatedListener.attach(handler);
                if (profileOut != null) {
                    WdlProfileSession.collectOn(handler, getProject(), profileOut, directory(),
                            getName() + " · " + java.time.LocalTime.now().withNano(0));
                }
                return handler;
            }
        };
    }

    @Override public void readExternal(@NotNull Element element) {
        super.readExternal(element);
        target = read(element, "target");
        workingDirectory = read(element, "workingDirectory");
        interpreterOptions = read(element, "interpreterOptions");
        scriptArguments = read(element, "scriptArguments");
        interpreter = read(element, "interpreter");
    }

    @Override public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        JDOMExternalizerUtil.writeField(element, "target", target);
        JDOMExternalizerUtil.writeField(element, "workingDirectory", workingDirectory);
        JDOMExternalizerUtil.writeField(element, "interpreterOptions", interpreterOptions);
        JDOMExternalizerUtil.writeField(element, "scriptArguments", scriptArguments);
        JDOMExternalizerUtil.writeField(element, "interpreter", interpreter);
    }

    private static String read(Element element, String key) {
        String value = JDOMExternalizerUtil.readField(element, key);
        return value == null ? "" : value;
    }
}
