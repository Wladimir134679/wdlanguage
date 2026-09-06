package ru.wds.wdl.idea;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Где взять сам сервер.
 * <p>
 * Плагин не содержит ни строчки языка и не носит сервер внутри себя: сервер — часть
 * дистрибутива wdl, обновляется вместе с ним и обязан быть той же версии, что
 * и движок, которым скрипт будет выполнен. Плагин, носящий свою копию, однажды
 * подскажет то, чего в запуске уже нет.
 * <p>
 * Порядок поиска идёт от явного к угаданному: настройка запуска, переменная
 * окружения, сборка в самом проекте, {@code PATH}.
 */
final class WdlServerPath {

    private static final Logger LOG = Logger.getInstance(WdlServerPath.class);

    private static final String PROPERTY = "wdl.lsp.home";
    private static final String ENVIRONMENT = "WDL_LSP_HOME";
    private static final String EXECUTABLE = SystemInfo.isWindows ? "wdl-lsp.bat" : "wdl-lsp";

    private WdlServerPath() {
    }

    /** Путь к запускаемому файлу сервера или {@code null}, если его негде взять. */
    static Path find(Project project) {
        Path fromProperty = inHome(System.getProperty(PROPERTY));
        if (fromProperty != null) {
            return fromProperty;
        }
        Path fromEnvironment = inHome(System.getenv(ENVIRONMENT));
        if (fromEnvironment != null) {
            return fromEnvironment;
        }
        String base = project.getBasePath();
        if (base != null) {
            // Репозиторий wdl, открытый как проект: `./gradlew :wdl-lsp:installDist`.
            Path built = Path.of(base, "wdl-lsp", "build", "install", "wdl-lsp", "bin", EXECUTABLE);
            if (Files.isRegularFile(built)) {
                return built;
            }
        }
        File onPath = PathEnvironmentVariableUtil.findInPath(EXECUTABLE);
        if (onPath != null) {
            return onPath.toPath();
        }
        LOG.warn("wdl-lsp не найден: задайте " + ENVIRONMENT
                + " или соберите сервер задачей :wdl-lsp:installDist");
        return null;
    }

    private static Path inHome(String home) {
        if (home == null || home.isBlank()) {
            return null;
        }
        Path executable = Path.of(home, "bin", EXECUTABLE);
        if (Files.isRegularFile(executable)) {
            return executable;
        }
        // Указать могли и на сам запускаемый файл — это то же самое намерение.
        Path direct = Path.of(home);
        return Files.isRegularFile(direct) ? direct : null;
    }
}
