package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.ValueType;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.StringValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Дескриптор типа ({@link TypeValue}) и его реестр ({@link Types}): одиночность,
 * печать и справка {@code info}. Поведение в скрипте ({@code is}, {@code typeof},
 * отказ создания и записи) проверяют {@code ClassTest} и {@code ProgramTest} —
 * здесь только сам дескриптор, отдельно от интерпретатора.
 */
class TypeValueTest {

    @Test
    @DisplayName("дескриптор одного типа — всегда один и тот же экземпляр")
    void oneInstancePerType() {
        assertSame(Types.of(ValueType.NUMBER), Types.of(ValueType.NUMBER));
        // Разным типам — разные дескрипторы, а не один на всех.
        assertNotSame(Types.of(ValueType.NUMBER), Types.of(ValueType.STRING));
    }

    @Test
    @DisplayName("Types.of(Value) отвечает дескриптором типа этого значения")
    void ofValue() {
        assertSame(Types.of(ValueType.STRING), Types.of(StringValue.of("привет")));
    }

    @Test
    @DisplayName("всего десять дескрипторов — по одному на элемент ValueType")
    void allCoversEveryValueType() {
        assertEquals(ValueType.values().length, Types.all().size());
        for (ValueType type : ValueType.values()) {
            assertTrue(Types.all().contains(Types.of(type)), type + " не попал в Types.all()");
        }
    }

    @Test
    @DisplayName("display() — идентификатор типа, name() — имя с большой буквы")
    void printing() {
        ClassValue number = Types.of(ValueType.NUMBER);
        assertEquals("number", number.display());
        assertEquals("number", number.toString());
        assertEquals("Number", number.name());
    }

    @Test
    @DisplayName("info — единственный ключ статики, с именем и заголовком типа")
    void info() {
        ClassValue array = Types.of(ValueType.ARRAY);
        assertEquals(1, array.statics().size());
        assertTrue(array.statics().has("info"));

        assertTrue(array.statics().get("info") instanceof MapValue);
        MapValue info = (MapValue) array.statics().get("info");
        assertEquals(StringValue.of("array"), info.get("name"));
        assertEquals(StringValue.of("массив"), info.get("title"));
    }
}
