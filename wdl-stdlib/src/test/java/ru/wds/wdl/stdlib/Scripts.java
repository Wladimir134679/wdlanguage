package ru.wds.wdl.stdlib;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Limits;
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
        return printed(code, Limits.none());
    }

    /** То же, но запуск работает под заданными пределами: шаги, время, квота потоков. */
    static String printed(String code, Limits limits) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());

        ExecutionContext context = ExecutionContext.fresh((Output) output::append)
                .withNativeModules(Sys.modules())
                .withLimits(limits);
        try {
            new Interpreter().run(program, context);
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
