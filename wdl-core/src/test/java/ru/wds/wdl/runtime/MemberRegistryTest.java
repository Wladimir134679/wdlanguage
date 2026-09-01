package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.MemberRegistry;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.ValueType;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Члены от приложения: то, что библиотека добавляет значениям через
 * {@link MemberRegistry}.
 * <p>
 * Проверяется главное обещание — <b>набор принадлежит запуску</b>: соседний
 * интерпретатор в том же процессе его не видит. Ради этого таблица и живёт
 * в запуске, а не в статике.
 */
class MemberRegistryTest {

    /** Набор, который добавляет строке два члена: свойство и метод. */
    private static MemberSet stringExtras() {
        return MemberSet.builder()
                .property("shout", (receiver, context, span) ->
                        StringValue.of(((StringValue) receiver).value().toUpperCase() + "!"))
                .method("times", Arity.exactly(1), (receiver, context, arguments, span) ->
                        IntValue.of(((StringValue) receiver).length()
                                * (int) ((ru.wds.wdl.value.NumberValue) arguments.get(0)).asLong()))
                .build();
    }

    private static String run(String code, ExecutionContext context, StringBuilder printed) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, context);
        return printed.toString().strip();
    }

    @Test
    @DisplayName("библиотека добавляет члены через область, которую и так получает")
    void installThroughScope() {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append);
        // Ровно то, что сделала бы Library.installTo(scope).
        context.scope().members().install(ValueType.STRING, stringExtras());
        assertEquals("ПРИВЕТ! 12", run("println(\"привет\".shout, \" \", \"привет\".times(2))",
                context, printed));
    }

    @Test
    @DisplayName("добавленный член виден и через дескриптор — таблица одна")
    void visibleThroughDescriptor() {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append);
        context.scope().members().install(ValueType.STRING, stringExtras());
        assertEquals("ДА!", run("println(String.shout(\"да\"))", context, printed));
    }

    @Test
    @DisplayName("набор принадлежит запуску: соседний интерпретатор его не видит")
    void runsAreIsolated() {
        StringBuilder first = new StringBuilder();
        ExecutionContext with = ExecutionContext.fresh(first::append);
        with.scope().members().install(ValueType.STRING, stringExtras());
        assertEquals("ЭЙ!", run("println(\"эй\".shout)", with, first));

        StringBuilder second = new StringBuilder();
        ExecutionContext without = ExecutionContext.fresh(second::append);
        assertThrows(RuntimeException.class,
                () -> run("println(\"эй\".shout)", without, second));
    }

    @Test
    @DisplayName("перекрыть встроенное библиотека тоже не вправе")
    void builtinIsNotOverridable() {
        ExecutionContext context = ExecutionContext.fresh();
        MemberSet clash = MemberSet.builder()
                .property("size", (receiver, ignored, span) -> IntValue.of(0))
                .build();
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> context.scope().members().install(ValueType.STRING, clash));
        assertTrue(refusal.getMessage().contains("встроенное перекрывать нельзя"), refusal.getMessage());
    }

    @Test
    @DisplayName("скрипт не может перекрыть то, что добавило приложение")
    void scriptCannotOverrideApplication() {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append);
        context.scope().members().install(ValueType.STRING, stringExtras());
        RuntimeException refusal = assertThrows(RuntimeException.class, () ->
                run("extend String {\n    property shout => \"своё\"\n}", context, printed));
        assertTrue(refusal.getMessage().contains("уже объявлен"), refusal.getMessage());
    }
}
