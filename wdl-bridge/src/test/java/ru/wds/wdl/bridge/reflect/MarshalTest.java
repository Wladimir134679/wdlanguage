package ru.wds.wdl.bridge.reflect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перевод через границу с Java.
 * <p>
 * Проверяется не «одно стало другим» — это очевидно, — а три вещи, ради которых
 * переводчик вообще написан отдельно от {@code api.Values}: что сужение числа
 * не проходит молча, что коллекция копируется, а не делится, и что стоимость
 * приведения расставляет перегрузки в том порядке, в каком их выбрал бы человек.
 */
class MarshalTest {

    private static final Span AT = new Span(0, 5);

    private final Marshal marshal = Marshal.plain();

    private Object toJava(Value value, Class<?> target) {
        return marshal.toJava(value, target, "f(): аргумент 1", AT);
    }

    // ------------------------------------------------------------------
    // Java → язык
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Числа Java приходят в язык целыми или дробными по своему типу")
    void numbersToValue() {
        assertEquals(IntValue.of(7), marshal.toValue(7, AT));
        assertEquals(IntValue.of(7), marshal.toValue(7L, AT));
        assertEquals(IntValue.of(7), marshal.toValue((short) 7, AT));
        assertEquals(FloatValue.of(0.5), marshal.toValue(0.5, AT));
        assertEquals(FloatValue.of(0.5), marshal.toValue(0.5f, AT));
    }

    @Test
    @DisplayName("null Java — это null языка, пустой Optional — тоже")
    void nullToValue() {
        assertSame(NullValue.NULL, marshal.toValue(null, AT));
        assertSame(NullValue.NULL, marshal.toValue(Optional.empty(), AT));
        assertEquals(StringValue.of("есть"), marshal.toValue(Optional.of("есть"), AT));
    }

    @Test
    @DisplayName("Перечисление приходит именем константы, а не номером")
    void enumToValue() {
        assertEquals(StringValue.of("MONDAY"), marshal.toValue(DayOfWeek.MONDAY, AT));
    }

    @Test
    @DisplayName("Коллекция, карта и массив приходят копией")
    void collectionsToValue() {
        assertEquals(List.of(IntValue.of(1), IntValue.of(2)),
                ((ArrayValue) marshal.toValue(List.of(1, 2), AT)).items());
        assertEquals(List.of(StringValue.of("a")),
                ((ArrayValue) marshal.toValue(new String[]{"a"}, AT)).items());

        MapValue entries = (MapValue) marshal.toValue(Map.of("k", 1), AT);
        assertEquals(IntValue.of(1), entries.get("k"));
    }

    @Test
    @DisplayName("Объекту без перевода отказывают с именем его класса")
    void unknownToValue() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> marshal.toValue(new Object(), AT));
        assertEquals(ErrorKind.TYPE, error.kind());
        assertTrue(error.getMessage().contains("java.lang.Object"), error.getMessage());
    }

    @Test
    @DisplayName("Обёртка вокруг объекта — дело моста, а не переводчика")
    void wrappingIsDelegated() {
        Object opaque = new Object() {
            @Override
            public String toString() {
                return "текст";
            }
        };
        Marshal wrapping = Marshal.of((object, span) -> StringValue.of("<" + object + ">"));
        assertEquals(StringValue.of("<текст>"), wrapping.toValue(opaque, AT));
    }

    // ------------------------------------------------------------------
    // Язык → Java
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Целое переводится в тот целочисленный тип, который просит метод")
    void integerToJava() {
        assertEquals(5L, toJava(IntValue.of(5), long.class));
        assertEquals(5, toJava(IntValue.of(5), int.class));
        assertEquals((short) 5, toJava(IntValue.of(5), short.class));
        assertEquals(5.0, toJava(IntValue.of(5), double.class));
    }

    @Test
    @DisplayName("Не влезшее в int число — ошибка значения, а не тихое усечение")
    void narrowingIsRefused() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> toJava(IntValue.of(Integer.MAX_VALUE + 1L), int.class));
        assertEquals(ErrorKind.VALUE, error.kind());
        assertTrue(error.getMessage().contains("не помещается в int"), error.getMessage());
    }

    @Test
    @DisplayName("Дробное в целое проходит, только если дроби нет")
    void realToIntegral() {
        assertEquals(2, toJava(FloatValue.of(2.0), int.class));

        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> toJava(FloatValue.of(2.5), int.class));
        assertEquals(ErrorKind.VALUE, error.kind());
        assertTrue(error.getMessage().contains("ожидалось целое"), error.getMessage());
    }

    @Test
    @DisplayName("Строка переводится в перечисление по имени константы")
    void stringToEnum() {
        assertEquals(DayOfWeek.FRIDAY, toJava(StringValue.of("FRIDAY"), DayOfWeek.class));
    }

    @Test
    @DisplayName("Неизвестной константе отказывают, перечислив известные")
    void unknownEnumConstant() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> toJava(StringValue.of("ПЯТНИЦА"), DayOfWeek.class));
        assertEquals(ErrorKind.TYPE, error.kind());
        assertTrue(error.getMessage().contains("MONDAY"), error.getMessage());
    }

    @Test
    @DisplayName("Массив языка переводится в список, множество и Java-массив копией")
    void arrayToJava() {
        ArrayValue items = ArrayValue.of(IntValue.of(1), IntValue.of(2));

        assertEquals(List.of(1L, 2L), toJava(items, List.class));
        assertEquals(2, ((int[]) toJava(items, int[].class))[1]);

        @SuppressWarnings("unchecked")
        List<Object> copy = (List<Object>) toJava(items, List.class);
        copy.clear();
        assertEquals(2, items.size(), "копия, а не общий список");
    }

    @Test
    @DisplayName("Карта переводится копией, а не разделяется со скриптом")
    void mapToJava() {
        MapValue entries = new MapValue();
        entries.put("k", IntValue.of(1));

        @SuppressWarnings("unchecked")
        Map<Object, Object> copy = (Map<Object, Object>) toJava(entries, Map.class);
        assertEquals(1L, copy.get("k"));
        copy.clear();
        assertNotSame(0, entries.size());
    }

    @Test
    @DisplayName("null языка годится ссылочному типу и не годится примитиву")
    void nullToJava() {
        assertNull(toJava(NullValue.NULL, String.class));
        assertThrows(WdlRuntimeError.class, () -> toJava(NullValue.NULL, int.class));
    }

    @Test
    @DisplayName("Значение языка проходит как есть, если метод просит Value")
    void valueAsIs() {
        Value value = ArrayValue.of(IntValue.of(1));
        assertSame(value, toJava(value, Value.class));
    }

    // ------------------------------------------------------------------
    // Стоимость приведения
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Точное совпадение дешевле приведения, а Object дороже всех")
    void costOrder() {
        assertEquals(Marshal.EXACT, marshal.cost(StringValue.of("a"), String.class));
        assertEquals(Marshal.EXACT, marshal.cost(IntValue.of(1), long.class));
        assertTrue(marshal.cost(IntValue.of(1), int.class) > Marshal.EXACT);
        assertTrue(marshal.cost(IntValue.of(1), double.class)
                > marshal.cost(IntValue.of(1), int.class));
        assertEquals(Marshal.WORST, marshal.cost(StringValue.of("a"), Object.class));
    }

    @Test
    @DisplayName("Стоимость считается по значению: не влезающее число типу не подходит")
    void costLooksAtValue() {
        assertEquals(Marshal.IMPOSSIBLE, marshal.cost(IntValue.of(1000), byte.class));
        assertEquals(Marshal.IMPOSSIBLE, marshal.cost(StringValue.of("две"), char.class));
        assertTrue(marshal.accepts(StringValue.of("д"), char.class));
        assertFalse(marshal.accepts(BoolValue.TRUE, int.class));
    }

    @Test
    @DisplayName("Дробное дороже всего приводится к целому — перегрузка с double побеждает")
    void costPrefersDouble() {
        assertEquals(Marshal.EXACT, marshal.cost(FloatValue.of(2.0), double.class));
        assertEquals(Marshal.WORST, marshal.cost(FloatValue.of(2.0), int.class));
    }
}
