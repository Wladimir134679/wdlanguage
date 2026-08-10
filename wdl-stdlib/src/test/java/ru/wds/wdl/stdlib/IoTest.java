package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.stdlib.Scripts.errorOf;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Модуль {@code sys.io}: функции над путём и тот же класс {@code File}, что в {@code std}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class IoTest {

    /** Путь в скрипт — строкой с прямыми слэшами: обратные съел бы разбор строки. */
    private static String script(Path path) {
        return path.toString().replace('\\', '/');
    }

    @Test
    @DisplayName("запись и чтение одной строкой")
    void writeAndRead(@TempDir Path dir) {
        String file = script(dir.resolve("report.txt"));
        assertEquals("привет true", printed("""
                import sys.io as io
                io.write("%s", "привет")
                println(io.read("%s"), " ", io.exists("%s"))
                """.formatted(file, file, file)));
    }

    @Test
    @DisplayName("дописывание и построчное чтение")
    void appendAndLines(@TempDir Path dir) {
        String file = script(dir.resolve("log.txt"));
        assertEquals("2 первая", printed("""
                import sys.io as io
                io.write("%s", "первая\\n")
                io.append("%s", "вторая")
                lines = io.lines("%s")
                println(len(lines), " ", lines[0])
                """.formatted(file, file, file)));
    }

    @Test
    @DisplayName("каталоги: mkdirs, isDir и список имён")
    void directories(@TempDir Path dir) {
        String root = script(dir);
        assertEquals("true 2 a.txt", printed("""
                import sys.io as io
                io.mkdirs("%s/inner")
                io.write("%s/a.txt", "a")
                println(io.isDir("%s/inner"), " ", len(io.list("%s")), " ", io.list("%s")[0])
                """.formatted(root, root, root, root, root)));
    }

    @Test
    @DisplayName("удаление отвечает, было ли что удалять")
    void remove(@TempDir Path dir) {
        String file = script(dir.resolve("tmp.txt"));
        assertEquals("true false", printed("""
                import sys.io as io
                io.write("%s", "x")
                println(io.remove("%s"), " ", io.remove("%s"))
                """.formatted(file, file, file)));
    }

    @Test
    @DisplayName("класс File из модуля — тот же самый, что кладёт std")
    void fileClassIsTheSame(@TempDir Path dir) {
        String file = script(dir.resolve("data.txt"));
        assertEquals("привет true", printed("""
                import sys.io as io
                f = new io.File("%s")
                println(f.write("привет").read(), " ", f is io.File)
                """.formatted(file)));
    }

    @Test
    @DisplayName("развёрнутый импорт приносит и File, и функции")
    void plainImport(@TempDir Path dir) {
        String file = script(dir.resolve("plain.txt"));
        assertEquals("да", printed("""
                import sys.io
                write("%s", "да")
                println(new File("%s").read())
                """.formatted(file, file)));
    }

    @Test
    @DisplayName("ошибка файловой системы — ошибка скрипта, а не IOException")
    void ioErrorsBecomeScriptErrors(@TempDir Path dir) {
        assertTrue(errorOf("""
                import sys.io as io
                io.read("%s")
                """.formatted(script(dir.resolve("нет.txt"))))
                .getMessage().contains("не удалось обратиться к файлу"));
    }

    @Test
    @DisplayName("путь должен быть строкой — проверка на входе")
    void pathMustBeString() {
        assertTrue(errorOf("""
                import sys.io as io
                io.read(7)
                """).getMessage().contains("путь к файлу должен быть строкой"));
    }
}
