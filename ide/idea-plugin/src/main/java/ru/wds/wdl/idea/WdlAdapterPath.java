package ru.wds.wdl.idea;

import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Где взять адаптер отладки.
 * <p>
 * Порядок поиска и причина — те же, что у языкового сервера ({@link WdlServerPath}):
 * плагин не носит внутри себя ни строчки языка, а отладчик обязан быть той же версии,
 * что и движок, которым скрипт будет выполнен. Отладчик, приехавший с плагином,
 * однажды остановился бы там, где в запуске уже нет инструкции.
 */
public final class WdlAdapterPath {

    private static final Logger LOG = Logger.getInstance(WdlAdapterPath.class);

    private static final String PROPERTY = "wdl.dap.home";
    private static final String ENVIRONMENT = "WDL_DAP_HOME";
    private static final String EXECUTABLE = SystemInfo.isWindows ? "wdl-dap.bat" : "wdl-dap";

    private WdlAdapterPath() {
    }

    /** Путь к запускаемому файлу адаптера или {@code null}, если его негде взять. */
    public static Path find(Project project) {
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
            // Репозиторий wdl, открытый как проект: `./gradlew :wdl-dap:installDist`.
            Path built = Path.of(base, "wdl-dap", "build", "install", "wdl-dap", "bin", EXECUTABLE);
            if (Files.isRegularFile(built)) {
                return built;
            }
        }
        File onPath = PathEnvironmentVariableUtil.findInPath(EXECUTABLE);
        if (onPath != null) {
            return onPath.toPath();
        }
        LOG.warn("wdl-dap не найден: задайте " + ENVIRONMENT
                + " или соберите адаптер задачей :wdl-dap:installDist");
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
