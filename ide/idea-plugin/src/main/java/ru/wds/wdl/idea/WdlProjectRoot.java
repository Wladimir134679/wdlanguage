package ru.wds.wdl.idea;

import com.intellij.openapi.project.Project;
import java.nio.file.Path;

/** Общий корень импортов для анализа и выполнения в открытом проекте IDEA. */
final class WdlProjectRoot {
    private WdlProjectRoot() { }

    static Path of(Project project) {
        String base = project.getBasePath();
        if (base == null) throw new IllegalArgumentException("У проекта IDEA не задан корневой каталог");
        return Path.of(base).toAbsolutePath().normalize();
    }
}
