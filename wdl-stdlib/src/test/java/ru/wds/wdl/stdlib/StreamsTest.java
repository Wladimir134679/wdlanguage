package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.wds.wdl.runtime.FatalError;
import ru.wds.wdl.runtime.Limits;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.stdlib.Scripts.errorOf;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Модуль {@code sys.streams} глазами скрипта.
 * <p>
 * Устройство конвейера проверяет {@code streams.PipelineTest} — там видно, сколько раз
 * спросили источник. Здесь проверяется то, что видит автор скрипта: результат,
 * закрытие ресурса, одноразовость и то, что бесконечный источник не вешает запуск.
 */
class StreamsTest {

    /** Путь в виде, пригодном для литерала: обратные слэши Windows экранируются. */
    private static String script(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static String log(Path dir, String... lines) {
        Path file = dir.resolve("data.txt");
        try {
            Files.write(file, java.util.List.of(lines), StandardCharsets.UTF_8);
        } catch (java.io.IOException failed) {
            throw new IllegalStateException(failed);
        }
        return script(file);
    }

    // --- основа ---------------------------------------------------------------

    @Test
    @DisplayName("Член массива появляется с import и отдаёт поток")
    void arrayMember() {
        assertEquals("[2, 4, 6] 12", printed("""
                import sys.streams as streams
                a = [1, 2, 3]
                println(a.stream().map(x => x * 2).list(), " ",
                        a.stream().map(x => x * 2).sum())
                """));
    }

    @Test
    @DisplayName("Без import члена stream у массива нет: цена модуля, которого не позвали")
    void memberNeedsImport() {
        assertTrue(errorOf("println([1, 2].stream())").getMessage().contains("stream"));
    }

    @Test
    @DisplayName("Источники: диапазон, склейка, пустой, повтор и бесконечная последовательность")
    void sources() {
        assertEquals("[1, 2, 3] [1, 2, 9] 0 --- [1, 2, 4, 8]", printed("""
                import sys.streams as streams
                println(streams.range(1, 3).list(), " ",
                        streams.concat(streams.range(1, 2), [9]).list(), " ",
                        streams.empty().count(), " ",
                        streams.repeat("-").limit(3).join(""), " ",
                        streams.iterate(1, x => x * 2).limit(4).list())
                """));
    }

    @Test
    @DisplayName("Бесконечный источник с limit завершается, а не вешает запуск")
    void infiniteEnds() {
        assertEquals("5", printed("""
                import sys.streams as streams
                println(streams.iterate(1, x => x + 1).filter(x => x > 4).first())
                """));
    }

    @Test
    @DisplayName("Составные: zip, chunked, windowed, distinct и sorted по ключу")
    void composites() {
        assertEquals("[[1, \"a\"], [2, \"b\"]] [[1, 2], [3]] [[1, 2], [2, 3]] [1, 2] [\"b\", \"aa\"]", printed("""
                import sys.streams as streams
                println(streams.of([1, 2]).zip(streams.of(["a", "b"])).list(), " ",
                        streams.range(1, 3).chunked(2).list(), " ",
                        streams.range(1, 3).windowed(2).list(), " ",
                        streams.of([1, 2, 1, 2]).distinct().list(), " ",
                        streams.of(["aa", "b"]).sorted(s => len(s)).list())
                """));
    }

    @Test
    @DisplayName("Терминальные операции отвечают тем же, чем ответил бы массив")
    void terminals() {
        assertEquals("3 6 1 3 2 true false {1: 2, 2: 4}", printed("""
                import sys.streams as streams
                a = [1, 2, 3]
                println(a.stream().count(), " ", a.stream().sum(), " ",
                        a.stream().min(), " ", a.stream().max(), " ",
                        a.stream().find(x => x > 1), " ",
                        a.stream().any(x => x == 3), " ",
                        a.stream().none(x => x > 0), " ",
                        streams.of([1, 2]).object(x => x, x => x * 2))
                """));
    }

    // --- одноразовость и закрытие ---------------------------------------------

    @Test
    @DisplayName("Второе обращение к пройденному потоку — ошибка, а не тихая пустота")
    void spentStreamSaysSo() {
        assertTrue(errorOf("""
                import sys.streams as streams
                s = streams.of([1, 2])
                s.list()
                s.list()
                """).getMessage().contains("поток уже пройден"));
    }

    @Test
    @DisplayName("Промежуточная операция расходует поток наравне с терминальной")
    void stageSpendsItToo() {
        assertTrue(errorOf("""
                import sys.streams as streams
                s = streams.of([1, 2])
                s.map(x => x)
                s.count()
                """).getMessage().contains("поток уже пройден"));
    }

    @Test
    @DisplayName("Поток — ресурс: use его закрывает, и повторное закрытие не ошибка")
    void useClosesIt(@TempDir Path dir) {
        String file = log(dir, "первая", "вторая");
        assertEquals("true первая false", printed("""
                import sys.streams as streams
                s = streams.lines("%s")
                println(s is Closeable, " ", s.first(), " ", s.close())
                """.formatted(file)));
    }

    @Test
    @DisplayName("lines читает файл лениво: до первой подходящей строки, а не до конца")
    void linesAreLazy(@TempDir Path dir) {
        String file = log(dir, "a", "b", "цель", "d", "e");
        // peek считает прочитанное: если бы файл читался целиком, было бы пять.
        assertEquals("3 цель", printed("""
                import sys.streams as streams
                import sys.thread as th
                seen = th.counter()
                use (s = streams.lines("%s")) {
                    found = s.peek((_) => seen.inc()).find(l => l == "цель")
                    println(seen.get(), " ", found)
                }
                """.formatted(file)));
    }

    @Test
    @DisplayName("use закрывает нетронутый поток — тот, у которого терминальной операции не было")
    void useClosesUntouched(@TempDir Path dir) {
        String file = log(dir, "строка");
        assertEquals("готово", printed("""
                import sys.streams as streams
                use (s = streams.lines("%s")) { }
                println("готово")
                """.formatted(file)));
    }

    @Test
    @DisplayName("Канал th.channel — источник: продюсер пишет, конвейер читает")
    void channelSource() {
        assertEquals("[2, 4, 6]", printed("""
                import sys.streams as streams
                import sys.thread as th

                ch = th.channel()
                producer = th.spawn("producer", def () {
                    for (i in 1..3) { ch.send(i) }
                    ch.close()
                })
                println(streams.of(ch).map(x => x * 2).list())
                producer.join()
                """));
    }

    @Test
    @DisplayName("Забытый источник закрывается вместе с запуском, а не течёт до конца процесса")
    void forgottenSourceIsReleased(@TempDir Path dir) {
        String file = log(dir, "строка");
        // Скрипт открыл файл и не закрыл. Reader после shutdownModules() обязан быть
        // закрыт — иначе на Windows файл нельзя было бы удалить.
        assertEquals("строка", printed("""
                import sys.streams as streams
                s = streams.lines("%s")
                println(s.first())
                forgotten = streams.lines("%s")
                """.formatted(file, file)));
        assertTrue(dir.resolve("data.txt").toFile().delete(),
                "файл держит незакрытый дескриптор");
    }

    // --- лимиты ---------------------------------------------------------------

    @Test
    @DisplayName("Конвейер без единой функции скрипта упирается в шаги: их считает источник")
    void libraryOnlyLoopStops() {
        FatalError stopped = assertThrows(FatalError.class, () -> printed("""
                import sys.streams as streams
                println(streams.repeat(1).count())
                """, Limits.builder().maxSteps(500).build()));
        assertTrue(stopped.getMessage().contains("исчерпал отведённые 500 шагов"),
                stopped::getMessage);
    }

    @Test
    @DisplayName("Тот же конвейер упирается и во время")
    void libraryOnlyLoopTimesOut() {
        FatalError stopped = assertThrows(FatalError.class, () -> printed("""
                import sys.streams as streams
                println(streams.iterate(1, x => x).skip(1000000000).first())
                """, Limits.builder().timeout(Duration.ofMillis(200)).build()));
        assertTrue(stopped.getMessage().contains("вышло отведённое время"), stopped::getMessage);
    }

    // --- конкурентная стадия ---------------------------------------------------

    @Test
    @DisplayName("mapConcurrent сохраняет порядок входа, а не порядок завершения")
    void concurrentKeepsOrder() {
        assertEquals("[0, 10, 20, 30, 40, 50]", printed("""
                import sys.streams as streams
                import sys.thread as th

                // Первая задача самая медленная: порядок завершения обратный входу
                println(streams.range(0, 5)
                        .mapConcurrent(4, def (x) {
                            th.sleep((5 - x) * 20)
                            return x * 10;
                        })
                        .list())
                """));
    }

    @Test
    @DisplayName("Ошибка задачи остаётся ошибкой языка и ловится обработчиком")
    void concurrentFailurePropagates() {
        assertEquals("поймал", printed("""
                import sys.streams as streams
                try {
                    streams.range(1, 4).mapConcurrent(2, x => [1][x]).list()
                } catch (e is IndexError) {
                    println("поймал")
                }
                """));
    }

    @Test
    @DisplayName("Потоки конкурентной стадии не переживают конец запуска")
    void concurrentThreadsDieWithRun() {
        printed("""
                import sys.streams as streams
                println(streams.range(1, 20).mapConcurrent(4, x => x).count())
                """);
        // Потоки берутся у реестра запуска, поэтому имя у них своё, а квота
        // освобождается стадией. Живых после запуска остаться не должно.
        assertEquals(0, Thread.getAllStackTraces().keySet().stream()
                        .filter(Thread::isAlive)
                        .filter(thread -> thread.getName().startsWith("wdl-stream"))
                        .count(),
                "поток стадии пережил запуск");
    }

    @Test
    @DisplayName("Квота потоков считается: конкурентной стадии её тоже не хватает")
    void concurrentRespectsQuota() {
        assertTrue(errorOf("""
                import sys.streams as streams
                streams.range(1, 4).mapConcurrent(8, x => x).list()
                """, Limits.builder().maxThreads(2).build()).getMessage().contains("поток"));
    }
}
