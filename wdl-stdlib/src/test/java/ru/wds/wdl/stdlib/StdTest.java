package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Стандартная библиотека и через неё — весь механизм встраивания: класс, написанный
 * на Java, обязан вести себя в скрипте как класс, написанный на wdl.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StdTest {

    /** Запускает скрипт с подключённой стандартной библиотекой. */
    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());

        ExecutionContext context = ExecutionContext.fresh((Output) output::append);
        Std.install(context.scope());
        new Interpreter().run(program, context);
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    /** Путь в скрипт — строкой с прямыми слэшами: обратные съел бы разбор строки. */
    private static String script(Path path) {
        return path.toString().replace('\\', '/');
    }

    // --- функции -------------------------------------------------------------

    @Test
    @DisplayName("pow держит целое целым, как и арифметика языка")
    void powKeepsIntegers() {
        assertEquals("1024 1 0.25 1.4142135623730951",
                printed("println(pow(2, 10), \" \", pow(5, 0), \" \", pow(2, -2), \" \", pow(2, 0.5))"));
    }

    @Test
    @DisplayName("переполнение уходит в вещественное, а не заворачивает разряды")
    void powOverflow() {
        assertEquals("true", printed("println(pow(10, 30) > 0)"));
    }

    @Test
    @DisplayName("sqrt и abs")
    void sqrtAndAbs() {
        assertEquals("4.0 7 1.5", printed("println(sqrt(16), \" \", abs(-7), \" \", abs(-1.5))"));
        assertTrue(errorOf("println(sqrt(-1))").getMessage()
                .contains("аргумент: ожидалось неотрицательное число"));
    }

    @Test
    @DisplayName("ошибка библиотеки говорит на языке скрипта")
    void argumentErrors() {
        assertTrue(errorOf("println(pow(\"два\", 10))")
                .getMessage().contains("pow(): основание: ожидалось число, а здесь строка"));
        assertTrue(errorOf("println(pow(2))")
                .getMessage().contains("принимает ровно 2 аргумента"));
    }

    // --- File ----------------------------------------------------------------

    @Test
    @DisplayName("File — обычный класс: поле, печать, typeof и is")
    void fileLooksLikeAClass(@TempDir Path dir) {
        String path = script(dir.resolve("данные.txt"));
        assertEquals("object class true " + path + " File{\"path\": \"" + path + "\"}",
                printed("""
                        f = new File("%s")
                        println(typeof(f), " ", typeof(File), " ", f is File, " ", f.path, " ", f)
                        """.formatted(path)));
    }

    @Test
    @DisplayName("запись, чтение и дозапись; методы возвращают сам файл")
    void writeAndRead(@TempDir Path dir) {
        assertEquals("first second true", printed("""
                f = new File("%s")
                f.write("first").append(" second")
                println(f.read(), " ", f.exists())
                """.formatted(script(dir.resolve("a.txt")))));
    }

    @Test
    @DisplayName("lines даёт массив строк, name — имя файла")
    void linesAndName(@TempDir Path dir) {
        assertEquals("2 вторая a.txt", printed("""
                f = new File("%s")
                f.write("первая\\nвторая")
                println(len(f.lines()), " ", f.lines()[1], " ", f.name())
                """.formatted(script(dir.resolve("a.txt")))));
    }

    @Test
    @DisplayName("несуществующий файл — ошибка скрипта с местом, а не IOException")
    void missingFile(@TempDir Path dir) {
        WdlRuntimeError error = errorOf(
                "println(new File(\"%s\").read())".formatted(script(dir.resolve("нет.txt"))));
        assertTrue(error.getMessage().contains("не удалось обратиться к файлу"), error.getMessage());
        assertFalse(error.span().isNone(), "у ошибки должно быть место в исходнике");
    }

    @Test
    @DisplayName("remove сообщает, был ли файл")
    void remove(@TempDir Path dir) {
        assertEquals("true false", printed("""
                f = new File("%s")
                f.write("x")
                println(f.remove(), " ", f.exists())
                """.formatted(script(dir.resolve("a.txt")))));
    }

    @Test
    @DisplayName("фабрика File.temp и поле класса File.SEPARATOR")
    void factoryAndConstant() {
        assertEquals("true true", printed("""
                t = File.temp("wdl-test")
                println(t.exists(), " ", len(File.SEPARATOR) > 0)
                t.remove()
                """));
    }

    @Test
    @DisplayName("путь — поле, а не метод: поле перекрывает метод по правилу языка")
    void pathIsAField(@TempDir Path dir) {
        assertTrue(errorOf("""
                f = new File("%s")
                println(f.path())
                """.formatted(script(dir.resolve("a.txt"))))
                .getMessage().contains("вызвать можно только функцию"));
    }

    @Test
    @DisplayName("число аргументов проверяется до входа в класс")
    void arity() {
        assertTrue(errorOf("new File()").getMessage().contains("принимает ровно 1 аргумент"));
        assertTrue(errorOf("new File(\"a\", \"b\")").getMessage().contains("принимает ровно 1 аргумент"));
    }

    // --- Random --------------------------------------------------------------

    @Test
    @DisplayName("зерно — свойство, печать — toString самого генератора")
    void randomKeepsSeedAsField() {
        assertEquals("42 Random(seed: 42) true", printed("""
                r = new Random(42)
                println(r.seed, " ", r, " ", r is Random)
                """));
    }

    @Test
    @DisplayName("одно зерно — одна последовательность")
    void sameSeedSameSequence() {
        assertEquals("true", printed("""
                a = new Random(1)
                b = new Random(1)
                println(a.int(1000) == b.int(1000) && a.int(1000) == b.int(1000))
                """));
    }

    @Test
    @DisplayName("зерно необязательно: поле со значением по умолчанию")
    void seedIsOptional() {
        assertEquals("null true", printed("""
                r = new Random()
                x = r.next()
                println(r.seed, " ", x >= 0 && x < 1)
                """));
    }

    @Test
    @DisplayName("pick выбирает из массива и жалуется на пустой")
    void pick() {
        assertEquals("болт", printed("println(new Random(7).pick([\"болт\"]))"));
        assertTrue(errorOf("println(new Random(1).pick([]))").getMessage()
                .contains("ожидался непустой массив"));
        assertTrue(errorOf("println(new Random(1).pick(5))").getMessage()
                .contains("Random.pick(): аргумент 1: ожидался массив"));
    }

    @Test
    @DisplayName("int проверяет границу")
    void intBound() {
        assertTrue(errorOf("println(new Random(1).int(0))").getMessage()
                .contains("граница: ожидалось положительное число"));
        // 1.5 не проходит раньше — на «целое», и это точнее прежнего общего сообщения.
        assertTrue(errorOf("println(new Random(1).int(1.5))").getMessage()
                .contains("ожидалось целое число"));
    }

    // --- встроенный класс как значение ---------------------------------------

    @Test
    @DisplayName("встроенный класс передаётся и лежит в массиве, как любой другой")
    void classIsAValue(@TempDir Path dir) {
        assertEquals("a.txt b.txt", printed("""
                def make(cls, path) => new cls(path)
                kinds = [File]
                println(make(File, "%s").name(), " ", new kinds[0]("%s").name())
                """.formatted(script(dir.resolve("a.txt")), script(dir.resolve("b.txt")))));
    }

    @Test
    @DisplayName("метод встроенного класса — значение и помнит свой объект")
    void boundNativeMethod(@TempDir Path dir) {
        assertEquals("привет", printed("""
                f = new File("%s")
                f.write("привет")
                read = f.read
                println(read())
                """.formatted(script(dir.resolve("a.txt")))));
    }

    @Test
    @DisplayName("экземпляры разных встроенных классов друг другу не родня")
    void separateClasses() {
        assertEquals("false false", printed("""
                r = new Random(1)
                println(r is File, " ", new File("x") is Random)
                """));
    }
}
