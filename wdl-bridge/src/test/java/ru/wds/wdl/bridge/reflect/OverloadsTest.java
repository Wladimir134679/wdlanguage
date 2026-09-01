package ru.wds.wdl.bridge.reflect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.bridge.reflect.samples.Counter;
import ru.wds.wdl.bridge.reflect.samples.Overloaded;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выбор перегрузки и сам вызов.
 * <p>
 * Проверяется то, ради чего выбор вообще написан руками: что он попадает туда же,
 * куда попал бы человек, и что при неоднозначности он <b>отказывается</b>, а не
 * угадывает.
 */
class OverloadsTest {

    private static final Span AT = new Span(0, 5);

    private final Marshal marshal = Marshal.plain();
    private final JavaShape shape = JavaShape.of(Overloaded.class);
    private final Overloaded target = new Overloaded();

    private Object call(String name, Value... arguments) {
        List<Value> values = List.of(arguments);
        JavaExecutable chosen = Overloads.select("Overloaded." + name + "()",
                shape.methods(name), values, marshal, AT);
        return chosen.invoke(target, values, marshal, "Overloaded." + name + "()", null, AT);
    }

    @Test
    @DisplayName("Целое уходит в long, а не в Object: точное совпадение дешевле")
    void integerPrefersLong() {
        assertEquals("long:5", call("take", IntValue.of(5)));
    }

    @Test
    @DisplayName("Строка уходит в String, а не в Object")
    void stringPrefersString() {
        assertEquals("String:да", call("take", StringValue.of("да")));
    }

    @Test
    @DisplayName("Логическое, которому подходит только Object, туда и уходит")
    void objectIsLastResort() {
        assertEquals("Object:true", call("take", ru.wds.wdl.value.types.BoolValue.TRUE));
    }

    @Test
    @DisplayName("Целое подходит методу с double, когда другого нет")
    void integerWidensToDouble() {
        assertEquals("double:5.0", call("only", IntValue.of(5)));
    }

    @Test
    @DisplayName("Ничья — отказ с перечислением кандидатов, а не молчаливый выбор")
    void tieIsRefused() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> call("tie", IntValue.of(1), IntValue.of(2)));
        assertEquals(ErrorKind.CALL, error.kind());
        assertTrue(error.getMessage().contains("подходит несколько"), error.getMessage());
        assertTrue(error.getMessage().contains("tie(int, Object)"), error.getMessage());
    }

    @Test
    @DisplayName("Хвост varargs собирается из лишних аргументов")
    void varargsAreCollected() {
        assertEquals("a-b-c", call("join", StringValue.of("-"),
                StringValue.of("a"), StringValue.of("b"), StringValue.of("c")));
        assertEquals("", call("join", StringValue.of("-")));
    }

    @Test
    @DisplayName("Массив языка уходит в список целиком, а не в хвост varargs")
    void arrayGoesToList() {
        assertEquals(2, call("count", ArrayValue.of(IntValue.of(1), IntValue.of(2))));
    }

    @Test
    @DisplayName("Неподходящее число аргументов — ошибка вызова с подписями Java")
    void wrongCount() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class, () -> call("only"));
        assertEquals(ErrorKind.CALL, error.kind());
        assertTrue(error.getMessage().contains("only(double)"), error.getMessage());
    }

    @Test
    @DisplayName("Неподходящий тип аргумента — ошибка типа, а не вызова")
    void wrongType() {
        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> call("only", StringValue.of("нет")));
        assertEquals(ErrorKind.TYPE, error.kind());
        assertTrue(error.getMessage().contains("строка"), error.getMessage());
    }

    @Test
    @DisplayName("Дробное с дробью не подходит целочисленной перегрузке и уходит в Object")
    void fractionalRefusesInteger() {
        assertEquals("double:2.5", call("only", FloatValue.of(2.5)));
        assertEquals("Object:2.5", call("take", FloatValue.of(2.5)));
    }

    @Test
    @DisplayName("Исключение из Java становится ошибкой класса JavaException")
    void javaExceptionIsWrapped() {
        Counter counter = new Counter("c");
        JavaShape counterShape = JavaShape.of(Counter.class);
        JavaExecutable fail = counterShape.methods("fail").get(0);

        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> fail.invoke(counter, List.of(), marshal, "Counter.fail()", null, AT));
        assertEquals(ErrorKind.JAVA, error.kind());
        assertTrue(error.getMessage().contains("так нельзя"), error.getMessage());
        assertEquals(IllegalStateException.class, error.javaCause().getClass(),
                "причина сохранена целиком — приложению она нужна в логе");
    }

    @Test
    @DisplayName("Проверяемое исключение заворачивается так же, как непроверяемое")
    void checkedExceptionIsWrapped() {
        Counter counter = new Counter("c");
        JavaExecutable fail = JavaShape.of(Counter.class).methods("failChecked").get(0);

        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> fail.invoke(counter, List.of(), marshal, "Counter.failChecked()", null, AT));
        assertEquals(ErrorKind.JAVA, error.kind());
        assertTrue(error.getMessage().contains("проверяемое"), error.getMessage());
    }

    @Test
    @DisplayName("Описание типа кэшируется: второй раз рефлексия не нужна")
    void shapeIsCached() {
        assertSame(JavaShape.of(Overloaded.class), JavaShape.of(Overloaded.class));
    }

    @Test
    @DisplayName("Непубличный класс описывается своим публичным видом")
    void publicView() {
        assertEquals(List.class, JavaShape.of(List.of(1).getClass()).type());
    }

    @Test
    @DisplayName("getClass у обёртки нет — это не настройка")
    void getClassIsNeverThere() {
        assertTrue(shape.methods("getClass").isEmpty());
        assertTrue(shape.methods("wait").isEmpty());
    }

    @Test
    @DisplayName("Пара аксессоров опознана, но свойством сама не становится")
    void beansAreOnlyCandidates() {
        JavaShape counter = JavaShape.of(Counter.class);
        JavaShape.Bean bean = counter.bean("value");
        assertNotNull(bean);
        assertNotNull(bean.getter());
        assertNotNull(bean.setter());
        assertTrue(counter.methodNames().contains("getValue"), "метод остаётся методом");
    }
}
