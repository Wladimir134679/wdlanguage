package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.source.Source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Запуски в одном процессе не должны видеть друг друга.
 * <p>
 * Класс, встроенный приложением, — обычное значение, и поля самого класса
 * ({@code File.mark}) скрипт вправе писать так же, как у класса на wdl. Значит,
 * общий на весь процесс {@code File} переносил бы состояние одного скрипта
 * в следующий — незаметно и до первой странной ошибки. Поэтому библиотека собирает
 * классы в {@code installTo}, то есть на запуск.
 * <p>
 * Внутри одного запуска класс при этом остаётся одним — это проверяет
 * {@code IoTest} («класс File из модуля — тот же самый, что кладёт std»).
 */
class IsolationTest {

    /** Один запуск: своя корневая область, свой набор модулей, своя стандартная библиотека. */
    private static String run(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());

        ExecutionContext context = ExecutionContext.fresh((Output) output::append)
                .withNativeModules(Sys.modules());
        Std.install(context.scope());
        try {
            new Interpreter().run(program, context);
        } finally {
            context.shutdownModules();
        }
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    @Test
    @DisplayName("поле, дописанное классу в одном запуске, не достаётся следующему")
    void staticsDoNotLeak() {
        assertEquals("1", run("File.mark = 1\nprintln(File.mark)"));
        assertEquals("null", run("println(File.mark)"));
    }

    @Test
    @DisplayName("испорченное поле класса чинится следующим запуском")
    void constantsAreRebuilt() {
        String separator = run("println(File.SEPARATOR)");
        assertEquals("?", run("File.SEPARATOR = \"?\"\nprintln(File.SEPARATOR)"));
        assertEquals(separator, run("println(File.SEPARATOR)"));
    }

    @Test
    @DisplayName("класс из модуля тоже свой у каждого запуска")
    void moduleClassesDoNotLeak() {
        assertEquals("2", run("""
                import sys.net.http as http
                http.Response.mark = 2
                println(http.Response.mark)
                """));
        assertEquals("null", run("""
                import sys.net.http as http
                println(http.Response.mark)
                """));
    }

    @Test
    @DisplayName("фабрика остаётся на месте: её нельзя сломать соседнему запуску")
    void factorySurvives() {
        assertEquals("5", run("File.temp = 5\nprintln(File.temp)"));
        assertEquals("def File.temp", run("println(File.temp)"));
    }
}
