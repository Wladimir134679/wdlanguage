package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.embed.Library;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeTrait;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.NativeModules;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Трейт, объявленный приложением: модуль даёт интерфейс, скрипт его реализует.
 * <p>
 * Проверяется главное обещание трейта — что требование спрашивают <b>на строке
 * {@code class}</b>, а не при вызове, — и что для языка нативный трейт неотличим
 * от трейта на wdl: то же {@code with}, тот же {@code is}, те же тексты ошибок.
 */
class NativeTraitTest {

    private static final String NL = System.lineSeparator();

    /** Модуль с трейтом-контрактом и функцией, которая его требует. */
    private static Library net() {
        NativeTrait handler = NativeTrait.named("Handler")
                .requireMethod("onMessage", Arity.exactly(1))
                .requireField("name")
                .field("retries", IntValue.of(3))
                .build();

        return new Library() {
            @Override
            public String name() {
                return "sys/net";
            }

            @Override
            public Environment installTo(Environment scope) {
                scope.define(handler.name(), handler);
                // Java-часть зовёт метод скрипта как обычное значение-функцию.
                scope.define("deliver", BuiltinFunction.of("deliver", Arity.exactly(2),
                        (context, arguments, span) -> {
                            var target = arguments.instance(0, "обработчик", handler);
                            return target.owner().method(target, "onMessage")
                                    .call(context, List.of(arguments.at(1)), span);
                        }));
                return scope;
            }
        };
    }

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        ExecutionContext context = ExecutionContext.fresh(printed::append)
                .withNativeModules(NativeModules.of(Map.of("sys/net", NativeTraitTest::net)));
        Unit unit = Unit.of(source, program);
        new Interpreter().run(unit, context);
        return printed.toString();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    @Test
    @DisplayName("класс на wdl реализует трейт модуля — и отвечает ему на is")
    void implementsNativeTrait() {
        assertEquals("эхо: привет|true|3" + NL, run("""
                import sys.net as net

                class Echo(name) with net.Handler {
                    def onMessage(text) => "эхо: " + text
                }

                e = new Echo("первый")
                println(e.onMessage("привет"), "|", e is net.Handler, "|", e.retries)
                """));
    }

    @Test
    @DisplayName("забытый метод — ошибка на строке class, а не при вызове")
    void missingMethod() {
        WdlRuntimeError error = errorOf("""
                import sys.net as net

                class Echo(name) with net.Handler {
                }
                """);
        assertTrue(error.getMessage().contains(
                "класс 'Echo' не выполняет требование трейта 'Handler': нет метода 'onMessage'"),
                error.getMessage());
    }

    @Test
    @DisplayName("метод не с тем числом аргументов — ошибка там же")
    void wrongArity() {
        assertTrue(errorOf("""
                import sys.net as net

                class Echo(name) with net.Handler {
                    def onMessage() => "нечем"
                }
                """).getMessage().contains("метод 'onMessage' должен принимать ровно 1 аргумент"));
    }

    @Test
    @DisplayName("забытое поле — ошибка про заголовок класса")
    void missingField() {
        assertTrue(errorOf("""
                import sys.net as net

                class Echo() with net.Handler {
                    def onMessage(text) => text
                }
                """).getMessage().contains("нет поля 'name'. Объявите его в заголовке класса"));
    }

    @Test
    @DisplayName("поле трейта достаётся классу готовым и перекрывается своим")
    void declaredField() {
        assertEquals("3|своё" + NL, run("""
                import sys.net as net

                class A(name) with net.Handler {
                    def onMessage(text) => text
                }
                class B(name, retries = "своё") with net.Handler {
                    def onMessage(text) => text
                }
                println(new A("a").retries, "|", new B("b").retries)
                """));
    }

    @Test
    @DisplayName("Java-часть зовёт метод скрипта как обычную функцию")
    void javaCallsBack() {
        assertEquals("эхо: пинг" + NL, run("""
                import sys.net as net

                class Echo(name) with net.Handler {
                    def onMessage(text) => "эхо: " + text
                }
                println(net.deliver(new Echo("первый"), "пинг"))
                """));
    }

    @Test
    @DisplayName("нативный класс тоже обязан выполнить требование — и проверяется это при сборке")
    void nativeClassChecked() {
        NativeTrait counted = NativeTrait.named("Counted")
                .requireMethod("count", Arity.exactly(0))
                .build();

        NativeClass ok = NativeClass.named("Bag")
                .method("count", Arity.exactly(0), (self, context, arguments, span) -> IntValue.of(0))
                .with(counted)
                .build();
        assertTrue(ok.conformsTo(counted));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> NativeClass.named("Bag").with(counted).build());
        assertEquals("класс 'Bag' не выполняет требование трейта 'Counted': нет метода 'count'",
                failure.getMessage());
    }

    @Test
    @DisplayName("нативный класс: арность метода сверяется, а поле метод не закрывает")
    void nativeClassArity() {
        NativeTrait counted = NativeTrait.named("Counted")
                .requireMethod("count", Arity.exactly(1))
                .build();

        assertTrue(assertThrows(IllegalStateException.class, () -> NativeClass.named("Bag")
                .method("count", Arity.exactly(0), (self, context, arguments, span) -> IntValue.of(0))
                .with(counted)
                .build()).getMessage().contains("должен принимать ровно 1 аргумент"));

        assertTrue(assertThrows(IllegalStateException.class, () -> NativeClass.named("Bag")
                .field("count")
                .with(counted)
                .build()).getMessage().contains("нет метода 'count'"));
    }

    @Test
    @DisplayName("поле трейта достаётся и нативному классу")
    void nativeClassGetsTraitField() {
        NativeTrait sized = NativeTrait.named("Sized").field("limit", IntValue.of(10)).build();
        NativeClass bag = NativeClass.named("Bag").field("name").with(sized).build();
        Value instance = bag.instantiate(List.of(StringValue.of("мешок")), text -> { }, Span.NONE);
        assertEquals("Bag{\"limit\": 10, \"name\": \"мешок\"}", instance.display());
    }

    @Test
    @DisplayName("экземпляр трейтом не создаётся — как и трейтом языка")
    void traitIsNotAClass() {
        assertTrue(errorOf("""
                import sys.net as net
                x = new net.Handler()
                """).getMessage().contains("'Handler' — трейт, экземпляр создаёт класс"));
    }
}
