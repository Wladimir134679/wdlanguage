package ru.wds.wdl.api;

import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Перевод между значениями языка и обычными Java-объектами.
 * <p>
 * Нужен затем, что приложение, встроившее движок ради двух вызовов, не должно изучать
 * {@link IntValue} и {@link MapValue}. Оно передаёт строку и получает строку; типы
 * языка остаются тем, кто с ним работает всерьёз, — {@link Value} никуда не делся
 * и доступен рядом ({@link WdlCallable#call}, {@link WdlInstance#defineValue}).
 *
 * <h2>Перевод — по значению и в одну сторону</h2>
 * {@code List} и {@code Map} <b>копируются</b>, а не оборачиваются. Иначе скрипт менял бы
 * коллекцию приложения из своего потока — молча, в обход всякой синхронизации, — а список,
 * положенный в скрипт, продолжал бы жить чужой жизнью. Копия честнее: у каждой стороны
 * своё, и видно, где граница.
 * <p>
 * Функция, класс и объект скрипта наружу переводятся <b>сами собой</b>: заворачивать
 * {@code FunctionValue} в {@code Map} значило бы потерять то единственное, что от неё
 * нужно, — возможность позвать. Их приложение получает как {@link Value} и работает
 * с ними через {@link WdlCallable}.
 *
 * <h2>Где проходит граница</h2>
 * Типизированный слой ({@link WdlEngine}, {@link WdlScript}, {@link WdlInstance},
 * {@link WdlCallable#call}) работает значениями языка: он для тех, кто работает
 * с языком всерьёз, и терять на границе тип числа там незачем. Короткий фасад
 * ({@link Wdl}, {@link WdlCallable#invoke}, {@link WdlInstance#get}) отдаёт
 * Java-объекты. Перевести одно в другое можно в любой момент — этим классом.
 */
public final class Values {

    private Values() {
    }

    /**
     * Java-объект как значение языка.
     *
     * @throws IllegalArgumentException если такого перевода нет: молча превращать
     *                                  неизвестный объект в строку — верный способ
     *                                  получить {@code "ru.wds.App@1a2b3c"} в скрипте
     */
    public static Value of(Object object) {
        return switch (object) {
            case null -> NullValue.NULL;
            case Value value -> value;
            case String text -> StringValue.of(text);
            case Boolean flag -> BoolValue.of(flag);
            case Integer number -> IntValue.of(number);
            case Long number -> IntValue.of(number);
            case Short number -> IntValue.of(number);
            case Byte number -> IntValue.of(number);
            case Double number -> FloatValue.of(number);
            case Float number -> FloatValue.of(number);
            case Character symbol -> StringValue.of(String.valueOf(symbol));
            case List<?> items -> array(items);
            case Map<?, ?> entries -> object(entries);
            case Object[] items -> array(List.of(items));
            default -> throw new IllegalArgumentException("нечем представить в языке: "
                    + object.getClass().getName() + ". Строки, числа, логические, списки "
                    + "и карты переводятся сами; для своего типа есть embed.NativeClass");
        };
    }

    private static Value array(List<?> items) {
        List<Value> values = new ArrayList<>(items.size());
        for (Object item : items) {
            values.add(of(item));
        }
        return ArrayValue.of(values);
    }

    private static Value object(Map<?, ?> entries) {
        Map<Value, Value> values = new LinkedHashMap<>();
        entries.forEach((key, value) -> values.put(of(key), of(value)));
        return MapValue.of(values);
    }

    /**
     * Значение языка как Java-объект.
     * <p>
     * Функции, классы и экземпляры возвращаются как есть — см. javadoc класса.
     */
    public static Object toJava(Value value) {
        return switch (value) {
            case NullValue ignored -> null;
            case StringValue text -> text.value();
            case BoolValue flag -> flag.value();
            case IntValue number -> number.value();
            case FloatValue number -> number.value();
            case ArrayValue items -> list(items);
            // Экземпляр класса — это MapValue с полями, и картой он выглядит верно;
            // спрашивается о нём до MapValue, потому что InstanceObjectValue им и является.
            case InstanceObjectValue instance -> map(instance);
            case MapValue entries -> map(entries);
            case FunctionValue function -> function;
            default -> value;
        };
    }

    private static List<Object> list(ArrayValue items) {
        List<Object> result = new ArrayList<>(items.items().size());
        for (Value item : items.items()) {
            result.add(toJava(item));
        }
        return result;
    }

    private static Map<Object, Object> map(MapValue entries) {
        Map<Object, Object> result = new LinkedHashMap<>();
        entries.entries().forEach((key, value) -> result.put(toJava(key), toJava(value)));
        return result;
    }
}
