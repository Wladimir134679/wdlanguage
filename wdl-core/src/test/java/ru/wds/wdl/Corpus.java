package ru.wds.wdl;

import ru.wds.wdl.source.Source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Файлы, на которых проверяются свойства, обязанные держаться на всём языке сразу:
 * покрытие документа токенами, вложенность интервалов, обход дерева.
 * <p>
 * Два набора. {@link #examples()} — примеры из корня репозитория: живой, растущий
 * корпус корректного кода, который заодно ловит расхождение примеров с языком.
 * {@link #malformed()} — нарочно недописанный текст: то, что редактор видит
 * большую часть времени, и то, на чём инструменты ломаются первым делом.
 */
public final class Corpus {

    /** Каталог примеров подаёт сборка: тест выполняется из каталога модуля, а не репозитория. */
    private static final String EXAMPLES_PROPERTY = "wdl.examples";

    private Corpus() {
    }

    public static List<Path> examples() {
        return wdlFilesIn(examplesDirectory());
    }

    public static List<Path> malformed() {
        Path directory = Path.of("src", "test", "resources", "malformed");
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("корпус недописанного текста не найден: "
                    + directory.toAbsolutePath());
        }
        return wdlFilesIn(directory);
    }

    /** Оба набора вместе — то, на чём гоняются инварианты. */
    public static List<Path> all() {
        return Stream.concat(examples().stream(), malformed().stream()).toList();
    }

    public static Source sourceOf(Path file) {
        try {
            return new Source(file.getFileName().toString(),
                    Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("не прочитать " + file, e);
        }
    }

    private static Path examplesDirectory() {
        String configured = System.getProperty(EXAMPLES_PROPERTY);
        if (configured != null && Files.isDirectory(Path.of(configured))) {
            return Path.of(configured);
        }
        // Запуск из IDE идёт мимо задачи Gradle, а значит и мимо системного свойства:
        // ищем корень репозитория вверх по дереву, чтобы тест не требовал настройки.
        for (Path directory = Path.of("").toAbsolutePath(); directory != null;
                directory = directory.getParent()) {
            Path examples = directory.resolve("examples");
            if (Files.isDirectory(examples)) {
                return examples;
            }
        }
        throw new IllegalStateException("каталог examples не найден: ни в свойстве "
                + EXAMPLES_PROPERTY + ", ни выше по дереву от " + Path.of("").toAbsolutePath());
    }

    private static List<Path> wdlFilesIn(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            List<Path> found = files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".wdl"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            if (found.isEmpty()) {
                throw new IllegalStateException("в " + directory.toAbsolutePath() + " нет ни одного .wdl");
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("не обойти " + directory, e);
        }
    }
}
