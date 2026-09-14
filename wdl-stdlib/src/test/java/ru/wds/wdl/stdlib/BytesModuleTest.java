package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.wds.wdl.runtime.FatalError;
import ru.wds.wdl.runtime.Limits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.stdlib.Scripts.errorOf;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Модуль {@code sys.bytes} и байтовый ввод-вывод вокруг него.
 * <p>
 * Проверяется то, ради чего всё затевалось: файл, прочитанный байтами и записанный
 * обратно, совпадает с исходным <b>байт в байт</b>. Прежний путь через строку этого
 * не давал: декодер UTF-8 подменяет непарные байты на {@code U+FFFD}, и картинка
 * после круга «прочитали — записали» переставала быть картинкой.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BytesModuleTest {

    /** Путь в скрипт — строкой с прямыми слэшами: обратные съел бы разбор строки. */
    private static String script(Path path) {
        return path.toString().replace('\\', '/');
    }

    // --- фабрики --------------------------------------------------------------

    @Test
    @DisplayName("Фабрики дают одни и те же байты разными записями")
    void factories() {
        assertEquals("true true true", printed("""
                import sys.bytes as bin
                png = bin.hex("89504e47")
                println(png == bin.of([0x89, 0x50, 0x4E, 0x47]), " ",
                        png == bin.of([-119, 80, 78, 71]), " ",
                        png == bin.base64("iVBORw=="))
                """));
    }

    @Test
    @DisplayName("bin.of берёт байт в любой записи, но не то, что байтом не бывает")
    void anyNotation() {
        assertEquals("c8c8", printed("""
                import sys.bytes as bin
                println(bin.of([200, -56]).hex)
                """));
        assertTrue(errorOf("""
                import sys.bytes as bin
                bin.of([256])
                """).getMessage().contains("не байт: 256"));
    }

    @Test
    @DisplayName("bin.hex отказывается угадывать: нечётная длина и не-цифра — ошибки")
    void hexIsStrict() {
        assertTrue(errorOf("""
                import sys.bytes as bin
                bin.hex("89504e4")
                """).getMessage().contains("нечётное число"));
        assertTrue(errorOf("""
                import sys.bytes as bin
                bin.hex("89g0")
                """).getMessage().contains("не шестнадцатеричная цифра"));
    }

    @Test
    @DisplayName("zeros, random и join")
    void bulkFactories() {
        assertEquals("bytes(4: 00 00 00 00) 8 true", printed("""
                import sys.bytes as bin
                println(bin.zeros(4), " ", bin.random(8).size, " ",
                        bin.join([bin.hex("ab"), bin.hex("cd")]) == bin.hex("abcd"))
                """));
    }

    // --- Writer ---------------------------------------------------------------

    @Test
    @DisplayName("Writer собирает пакет цепочкой, снимок не мешает писать дальше")
    void writerChains() {
        assertEquals("2a000000 6 2a0000006f6b", printed("""
                import sys.bytes as bin
                w = bin.writer()
                w.order("le").putInt32(42)
                first = w.bytes
                w.putText("ok")
                println(first.hex, " ", w.size, " ", w.bytes.hex)
                """));
    }

    @Test
    @DisplayName("Порядок байтов — везде одни и те же два слова")
    void byteOrder() {
        assertEquals("0000002a 2a000000", printed("""
                import sys.bytes as bin
                big = bin.writer().putInt32(42).bytes
                little = bin.writer().order("le").putInt32(42).bytes
                println(big.hex, " ", little.hex)
                """));
        assertTrue(errorOf("""
                import sys.bytes as bin
                bin.writer().order("middle")
                """).getMessage().contains("порядок байтов"));
    }

    @Test
    @DisplayName("Строгие put проверяют свой диапазон, put(v) — широкий")
    void strictPutters() {
        assertEquals("c8 c8", printed("""
                import sys.bytes as bin
                println(bin.writer().putUint8(200).bytes.hex, " ",
                        bin.writer().put(200).bytes.hex)
                """));
        assertTrue(errorOf("""
                import sys.bytes as bin
                bin.writer().putInt8(200)
                """).getMessage().contains("ожидался int8 (-128..127), а здесь 200"));
        assertTrue(errorOf("""
                import sys.bytes as bin
                bin.writer().putUint8(-1)
                """).getMessage().contains("ожидался uint8 (0..255)"));
    }

    @Test
    @DisplayName("bin.fixed не растёт и говорит об этом словами")
    void fixedIsFixed() {
        assertTrue(errorOf("""
                import sys.bytes as bin
                w = bin.fixed(2)
                w.put(1).put(2).put(3)
                """).getMessage().contains("буфер полон"));
    }

    // --- Reader ---------------------------------------------------------------

    @Test
    @DisplayName("Reader читает по порядку и сам двигает позицию")
    void readerWalks() {
        assertEquals("42 ok 4 true", printed("""
                import sys.bytes as bin
                packet = bin.writer().order("le").putInt32(42).putText("ok").putInt32(7).bytes
                r = bin.reader(packet)
                r.order("le")
                println(r.int32(), " ", r.text(2), " ", r.remaining, " ", r.done == false)
                """));
    }

    @Test
    @DisplayName("Конец данных — ошибка, а не правдоподобный мусор")
    void readerRefusesShortRead() {
        assertTrue(errorOf("""
                import sys.bytes as bin
                bin.reader(bin.hex("0102")).int32()
                """).getMessage().contains("нужно 4 байт, а от позиции 0 осталось 2"));
    }

    @Test
    @DisplayName("skip, seek и rest: разбор формата целиком")
    void readerNavigates() {
        assertEquals("cdef abcdef", printed("""
                import sys.bytes as bin
                r = bin.reader(bin.hex("abcdef"))
                r.skip(1)
                println(r.rest().hex, " ", r.seek(0).rest().hex)
                """));
    }

    // --- ввод-вывод -----------------------------------------------------------

    @Test
    @DisplayName("Файл читается и пишется байт в байт — то, ради чего всё затевалось")
    void fileRoundTrip(@TempDir Path dir) throws Exception {
        // Байты, которых нет в UTF-8: через строку такой файл не прошёл бы —
        // декодер подменил бы их на U+FFFD, и обратно того же файла не собрать.
        byte[] broken = {(byte) 0x89, 0x50, 0x4E, 0x47, (byte) 0xC8, (byte) 0xFF, 0x00};
        Path source = dir.resolve("image.png");
        Path copy = dir.resolve("copy.png");
        Files.write(source, broken);

        assertEquals("7 true", printed("""
                import sys.io as io
                data = io.readBytes("%s")
                io.writeBytes("%s", data)
                println(data.size, " ", io.readBytes("%s") == data)
                """.formatted(script(source), script(copy), script(copy))));
        assertArrayEquals(broken, Files.readAllBytes(copy));
    }

    @Test
    @DisplayName("Те же байты через класс File — read/write у него теперь двух видов")
    void fileClassBytes(@TempDir Path dir) {
        String file = script(dir.resolve("data.bin"));
        assertEquals("abcd true", printed("""
                import sys.io as io
                import sys.bytes as bin
                f = new io.File("%s")
                f.writeBytes(bin.hex("abcd"))
                println(f.readBytes().hex, " ", f.readBytes() == bin.hex("abcd"))
                """.formatted(file)));
    }

    @Test
    @DisplayName("streams.chunks читает файл кусками, последний короче")
    void chunkedReading(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.bin");
        Files.write(file, new byte[10]);
        assertEquals("3 [4, 4, 2] 10", printed("""
                import sys.streams as streams
                use (s = streams.chunks("%s", 4)) {
                    sizes = s.map(c => c.size).list()
                    println(len(sizes), " ", sizes, " ", sizes.sum)
                }
                """.formatted(script(file))));
    }

    // --- пределы --------------------------------------------------------------

    @Test
    @DisplayName("Предел выделения останавливает то, чего не видит счётчик шагов")
    void allocationLimit() {
        Limits tight = Limits.builder().maxBufferBytes(1024).build();
        // Один шаг скрипта — и гигабайт памяти: ради ровно этого случая предел
        // выделения и заведён.
        assertTrue(errorOf("""
                import sys.bytes as bin
                bin.zeros(1 << 30)
                """, tight).getMessage().contains("одним выделением разрешено 1024"));
        // Растущий буфер обойти предел тоже не может: рост отмечается как выделение.
        assertTrue(errorOf("""
                import sys.bytes as bin
                w = bin.writer()
                for (i in 1..100000) { w.put(0) }
                """, tight).getMessage().contains("одним выделением разрешено 1024"));
    }

    @Test
    @DisplayName("Крупная операция над байтами берёт шаги пачкой")
    void bulkOperationsTakeSteps() {
        // hex от мегабайта — цикл целиком внутри Java: без библиотечного шага
        // он не проходил бы ни одной точки движка и предел шагов обошёлся бы
        // одной строкой скрипта.
        FatalError stopped = assertThrows(FatalError.class, () -> printed("""
                import sys.bytes as bin
                text = bin.zeros(1 << 20).hex
                """, Limits.builder().maxSteps(300).build()));
        assertTrue(stopped.getMessage().contains("исчерпал отведённые 300 шагов"),
                stopped.getMessage());
    }
}
