package ru.wds.wdl.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Договор модуля: одна и та же установка, написанная один раз.
 * <p>
 * Проверяется то, что раньше писали руками в каждой библиотеке и потому иногда
 * писали по-разному: класс один на запуск, порядок объявления — порядок установки,
 * закрытие переживает падение одного обработчика.
 */
class ModuleTest {

    private static Environment scope() {
        return ExecutionContext.fresh(text -> {
        }).scope();
    }

    private static NativeClass counter() {
        return named("Counter");
    }

    private static NativeClass named(String name) {
        return NativeClass.named(name).field("value", IntValue.of(0)).build();
    }

    @Test
    @DisplayName("модуль кладёт типы, функции и константы одной установкой")
    void installsEverything() {
        Library module = Module.named("sys/demo")
                .type("Counter", scope -> counter())
                .function("twice", Arity.exactly(1),
                        (context, args, span) -> IntValue.of(args.integer(0) * 2))
                .constant("LIMIT", IntValue.of(10))
                .define("NAME", StringValue.of("demo"))
                .build();

        Environment scope = module.installTo(scope());

        assertEquals("sys/demo", module.name());
        assertNotNull(scope.lookup("Counter"));
        assertEquals(IntValue.of(10), scope.lookup("LIMIT"));
        assertEquals(StringValue.of("demo"), scope.lookup("NAME"));
        assertEquals(IntValue.of(8), ((ru.wds.wdl.value.FunctionValue) scope.lookup("twice"))
                .call(text -> { }, List.of(IntValue.of(4)), ru.wds.wdl.source.Span.NONE));
    }

    @Test
    @DisplayName("класс один на запуск: второй модуль отдаёт тот же, что уже в области")
    void typeIsSharedWithinRun() {
        Environment scope = scope();
        Module.named("std").type("Counter", s -> counter()).build().installTo(scope);
        Value first = scope.lookup("Counter");

        // Второй модуль объявляет тот же тип — и обязан отдать тот же объект:
        // иначе 'c is Counter' врал бы в зависимости от того, откуда взяли имя.
        Module.named("sys/demo").type("Counter", s -> counter()).build().installTo(scope);

        assertSame(first, scope.lookup("Counter"));
    }

    @Test
    @DisplayName("порядок объявления — порядок установки: второй тип видит первый")
    void secondTypeSeesFirst() {
        List<String> seen = new ArrayList<>();
        Module.named("sys/demo")
                .type("First", scope -> named("First"))
                .type("Second", scope -> {
                    seen.add(scope.lookup("First") instanceof ClassValue found ? found.name() : "нет");
                    return named("Second");
                })
                .install(scope -> seen.add(scope.lookup("Second") instanceof ClassValue found
                        ? found.name() : "нет"))
                .build()
                .installTo(scope());

        assertEquals(List.of("First", "Second"), seen);
    }

    @Test
    @DisplayName("закрытие зовёт всех: упавший обработчик не отменяет остальных")
    void closeRunsEveryHandler() {
        List<String> closed = new ArrayList<>();
        Library module = Module.named("sys/demo")
                .onClose(() -> closed.add("первый"))
                .onClose(() -> {
                    throw new IllegalStateException("сокет уже закрыт");
                })
                .onClose(() -> closed.add("третий"))
                .build();

        assertEquals("сокет уже закрыт",
                assertThrows(IllegalStateException.class, module::close).getMessage());
        assertEquals(List.of("первый", "третий"), closed);
    }

    @Test
    @DisplayName("чужой одноимённый класс за свой не принимается")
    void foreignTypeIsNotReused() {
        Environment scope = scope();
        // Скрипт объявил своё 'Counter' — модуль обязан собрать своё, а не взять это.
        scope.define("Counter", NullValue.NULL);
        Module.named("sys/demo").type("Counter", s -> counter()).build().installTo(scope);

        assertEquals("Counter", ((ClassValue) scope.lookup("Counter")).name());
    }

    @Test
    @DisplayName("typeIn не находит тип — отказ, а не молча собранный второй")
    void missingTypeIsRefused() {
        Environment scope = scope();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Module.named("sys/demo")
                        .install(s -> Module.typeIn(s, "Counter"))
                        .build()
                        .installTo(scope));
        assertEquals("класса 'Counter' нет в области: его ставит сам модуль, "
                + "и объявлен он должен быть до install(...)", error.getMessage());
    }

    @Test
    @DisplayName("typeIn берёт свой класс и не принимает чужой одноимённый")
    void typeInWantsItsOwn() {
        Environment scope = scope();
        Module.named("sys/demo")
                .type("Counter", s -> counter())
                .install(s -> assertSame(s.lookup("Counter"), Module.typeIn(s, "Counter")))
                .build()
                .installTo(scope);

        Environment foreign = scope();
        foreign.define("Counter", StringValue.of("не класс"));
        assertThrows(IllegalStateException.class, () -> Module.typeIn(foreign, "Counter"));
    }
}
