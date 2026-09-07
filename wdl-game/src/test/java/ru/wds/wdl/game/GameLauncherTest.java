package ru.wds.wdl.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Запуск без аргументов показывает игру, а не справку.
 * <p>
 * Проверять тут стоит ровно одно: пример находится при любом рабочем каталоге.
 * Тест выполняется из каталога модуля, а не из корня репозитория, — то есть
 * ровно в том положении, в котором стартует «Run» из IDE.
 */
class GameLauncherTest {

    @Test
    @DisplayName("Пример игры находится вверх по дереву от рабочего каталога")
    void demoGameIsFound() {
        Path demo = GameLauncher.demoGame();

        assertNotNull(demo, "examples/game/pong.wdl должен находиться из "
                + Path.of("").toAbsolutePath());
        assertTrue(Files.isRegularFile(demo));
        assertTrue(read(demo).contains("import game"), "это игра на модуле game");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("не прочитать " + file, e);
        }
    }
}
