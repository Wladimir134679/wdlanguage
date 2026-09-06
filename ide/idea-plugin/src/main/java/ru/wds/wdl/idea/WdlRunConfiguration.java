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

    private Path directory() {
        String base = getProject().getBasePath();
        Path root = Path.of(base == null ? "." : base);
        return workingDirectory.isBlank() ? root : root.resolve(workingDirectory).normalize();
    }

    @Override public void checkConfiguration() throws RuntimeConfigurationException {
        try {
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

    @Override public @NotNull SettingsEditor<WdlRunConfiguration> getConfigurationEditor() {
        return new WdlRunSettingsEditor();
    }

    @Override public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        return new CommandLineState(environment) {
            @Override protected @NotNull ProcessHandler startProcess() throws ExecutionException {
                Path launcher = WdlCommandLine.findCli(getProject(), interpreter);
                if (launcher == null) throw new ExecutionException("wdl не найден; выполните installWdl");
                GeneralCommandLine command = WdlCommandLine.create(launcher, "ru.wds.wdl.cli.Main");
                command.withWorkDirectory(directory().toFile());
                command.addParameters(ParametersListUtil.parse(interpreterOptions));
                command.addParameter("--");
                command.addParameter(directory().resolve(target).normalize().toString());
                command.addParameters(ParametersListUtil.parse(scriptArguments));
                OSProcessHandler handler = new KillableColoredProcessHandler(command);
                ProcessTerminatedListener.attach(handler);
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
