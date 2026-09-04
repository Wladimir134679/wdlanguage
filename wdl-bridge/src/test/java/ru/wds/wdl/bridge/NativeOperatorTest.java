package ru.wds.wdl.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.op.Overloads;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Операторы у класса, встроенного приложением.
 * <p>
 * Проверяется обещание из {@code docs/embedding.md}: для оператора построителю
 * не нужно ничего особенного — это <b>обычный метод</b>, у которого имя совпадает
 * с ключом таблицы. Интерпретатор спрашивает его тем же путём, что у класса на wdl,
 * потому что путь один и таблица одна.
 */
class NativeOperatorTest {

    /** Деньги: сложение, сравнение, зеркало и унарная смена знака — всё методами. */
    private static NativeClass money() {
        return NativeClass.named("Money")
                .field("amount", IntValue.of(0))
                .method("+", Arity.exactly(1), (self, context, arguments, span) ->
                        IntValue.of(amount(self) + number(arguments.get(0))))
                .method(Overloads.ORDER, Arity.exactly(1), (self, context, arguments, span) ->
                        IntValue.of(amount(self) - number(arguments.get(0))))
                .method(Overloads.mirrorName("-"), Arity.exactly(1),
                        (self, context, arguments, span) ->
                                IntValue.of(number(arguments.get(0)) - amount(self)))
                .method(Overloads.unaryName("-"), Arity.exactly(0),
                        (self, context, arguments, span) -> IntValue.of(-amount(self)))
                .build();
    }

    private static long amount(NativeInstance self) {
        return ((NumberValue) self.get("amount")).asLong();
    }

    private static long number(Value value) {
        return value instanceof NumberValue number
                ? number.asLong()
                : ((NumberValue) ((ru.wds.wdl.value.types.MapValue) value).get(
                        ru.wds.wdl.value.types.StringValue.of("amount"))).asLong();
    }

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append);
        Environment scope = context.scope();
        scope.define("Money", money());
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, context);
        return printed.toString().strip();
    }

    @Test
    @DisplayName("метод с именем '+' работает оператором сам собой")
    void binaryOperator() {
        assertEquals("30", run("println(new Money(10) + new Money(20))"));
    }

    @Test
    @DisplayName("метод '<=>' кормит все четыре сравнения — как у класса на wdl")
    void comparison() {
        assertEquals("true false", run("""
                a = new Money(10)
                b = new Money(20)
                print(a < b)
                print(" ")
                print(a >= b)
                """));
    }

    @Test
    @DisplayName("зеркальный и унарный отличаются только ключом в таблице")
    void mirrorAndUnary() {
        assertEquals("15 -10", run("""
                print(25 - new Money(10))
                print(" ")
                print(-new Money(10))
                """));
    }

    @Test
    @DisplayName("классу от приложения оператор добавляется и из скрипта")
    void extendFromScript() {
        assertEquals("100", run("""
                extend Money {
                    def `*`(right) => this.amount * right
                }
                println(new Money(10) * 10)
                """));
    }
}
