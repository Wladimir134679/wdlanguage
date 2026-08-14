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
    @DisplayName("io.create и io.open отдают поток, который use закрывает сам")
    void streamsWorkWithUse(@TempDir Path dir) {
        String file = script(dir.resolve("stream.txt"));
        assertEquals("2 первая целиком 16 true", printed("""
                import sys.io as io

                use (out = io.create("%s")) {
                    out.writeLine("первая").writeLine("вторая")
                }
                use (src = io.open("%s")) {
                    lines = src.lines()
                    println(len(lines), " ", lines[0])
                }
                use (src = io.open("%s")) {
                    println("целиком ", len(src.read()), " ", src is Closeable)
                }
                """.formatted(file, file, file)));
    }

    @Test
    @DisplayName("после close поток говорит об этом прямо, а не падает загадочно")
    void closedStreamSaysSo(@TempDir Path dir) {
        String file = script(dir.resolve("closed.txt"));
        assertTrue(errorOf("""
                import sys.io as io
                io.write("%s", "текст")
                src = io.open("%s")
                src.close()
                println(src.read())
                """.formatted(file, file)).getMessage().contains("поток уже закрыт"));
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
    @DisplayName("Reader и Writer — потоки: общий родитель, общий Closeable")
    void streamsShareAParent(@TempDir Path dir) {
        String file = script(dir.resolve("stream.txt"));
        assertEquals("true true true true false", printed("""
                import sys.io as io
                w = io.create("%s")
                r = io.open("%s")
                println(w is io.Stream, " ", r is io.Stream, " ",
                        r is io.Reader, " ", r is Closeable, " ", r is io.Writer)
                w.close()
                r.close()
                """.formatted(file, file)));
    }

    @Test
    @DisplayName("путь и close достались от родителя, свои методы — свои")
    void inheritedMembers(@TempDir Path dir) {
        String file = script(dir.resolve("stream.txt"));
        // Путь печатается так, как его записала система, поэтому сверяется хвост:
        // разделитель каталогов к наследованию отношения не имеет.
        assertTrue(printed("""
                import sys.io as io
                io.write("%s", "привет")
                r = io.open("%s")
                println(r.path, " ", r.close(), " ", io.read("%s"))
                """.formatted(file, file, file)).endsWith("stream.txt true привет"));
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
                """).getMessage().contains("read(): путь к файлу: ожидалась строка"));
    }
}
