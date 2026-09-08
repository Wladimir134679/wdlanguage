package ru.wds.wdl.idea;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WdlCommandLine {
    private WdlCommandLine() { }

    static Path findCli(Project project, String explicit) {
        String name = SystemInfo.isWindows ? "wdl.bat" : "wdl";
        if (!explicit.isBlank()) return existing(Path.of(explicit));
        String home = System.getenv("WDL_HOME");
        if (home != null) {
            Path path = existing(Path.of(home, "bin", name));
            if (path != null) return path;
        }
        if (project.getBasePath() != null) {
            Path path = existing(Path.of(project.getBasePath(), "wdl-cli", "build", "install", "wdl", "bin", name));
            if (path != null) return path;
        }
        File path = PathEnvironmentVariableUtil.findInPath(name);
        return path == null ? null : path.toPath();
    }

    private static Path existing(Path path) { return Files.isRegularFile(path) ? path : null; }

    // Launch Java directly: no cmd.exe quoting/expansion of script arguments on Windows.
    // The Gradle application distribution keeps all runtime jars in lib next to bin.
    // Public because the debug adapter (ru.wds.wdl.idea.debug) is started the same way:
    // another distribution, the same trick.
    public static GeneralCommandLine create(Path launcher, String mainClass) {
        String home = System.getenv("JAVA_HOME");
        String java = home == null || home.isBlank() ? "java"
                : Path.of(home, "bin", SystemInfo.isWindows ? "java.exe" : "java").toString();
        return new GeneralCommandLine(java, "-Dfile.encoding=UTF-8", "-cp",
                launcher.toAbsolutePath().getParent().getParent().resolve("lib") + File.separator + "*", mainClass)
                .withCharset(StandardCharsets.UTF_8);
    }
}
