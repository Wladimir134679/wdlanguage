package ru.wds.wdl.game;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.NativeModules;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Запуск скрипта с одним подключённым модулем {@code game} — то же, что делает
 * {@link GameLauncher}, только вывод собирается в строку, а окно не открывается.
 */
final class GameScripts {

    private GameScripts() {
    }

    static String printed(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());

        ExecutionContext context = ExecutionContext.fresh((Output) output::append)
                .withNativeModules(NativeModules.of(Games.registry()));
        try {
            new Interpreter().run(program, context);
        } finally {
            // Как в запуске игры: миры, оставшиеся от скрипта, гасятся вместе с ним.
            context.shutdownModules();
        }
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }
}
