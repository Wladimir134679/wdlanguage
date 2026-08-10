package ru.wds.wdl.stdlib;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Запуск скрипта со встроенными модулями — то же, что делает консольный интерпретатор,
 * только вывод собирается в строку.
 */
final class Scripts {

    private Scripts() {
    }

    /** Вывод скрипта, у которого есть весь набор {@code sys.*}. */
    static String printed(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        Resolution resolution = Resolver.resolve(program, diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки резолвера:\n" + diagnostics.renderAll());

        ExecutionContext context = ExecutionContext.fresh((Output) output::append)
                .withNativeModules(Sys.modules());
        try {
            new Interpreter().run(program, resolution, context);
        } finally {
            // Как в CLI: запуск кончился — модули закрываются, чем бы он ни кончился.
            context.shutdownModules();
        }
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }
}
