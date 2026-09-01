package ru.wds.wdl.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Проверка аргумента-экземпляра — {@code args.instance(...)} на классе от приложения.
 * <p>
 * Сам {@link Args} живёт в ядре и от построителя не зависит; здесь проверяется
 * их встреча, поэтому тест и стоит в модуле, где построитель есть.
 */
class ArgsInstanceTest {

    private static final Span AT = Span.NONE;

    private static Args args(Value... values) {
        return Args.of("open", List.of(values), text -> {
        }, AT);
    }

    @Test
    @DisplayName("экземпляр проверяется как оператором is: подходит и наследник, и трейт")
    void instanceOfClass() {
        ClassValue point = NativeClass.named("Point").field("x").build();
        ClassValue other = NativeClass.named("Other").build();
        Value instance = point.instantiate(List.of(IntValue.of(1)), text -> {
        }, AT);

        assertSame(instance, args(instance).instance(0, "точка", point));
        assertEquals("open(): точка: ожидался экземпляр класса 'Other', а здесь объект (Point{\"x\": 1})",
                assertThrows(WdlRuntimeError.class,
                        () -> args(instance).instance(0, "точка", other)).getMessage());
    }
}
