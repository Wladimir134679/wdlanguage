package ru.wds.wdl.value.types;

import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Объект: набор пар «ключ — значение» с сохранением порядка вставки.
 * <p>
 * В языке этот тип называется {@code object} — так его показывает {@code typeof},
 * так он описан в <a href="../../../../../../../../docs/types.md">типах данных</a>.
 * Класс назван по устройству, а не по имени типа, потому что имён теперь два:
 * карта и {@link InstanceObjectValue экземпляр класса} — один тип языка,
 * две структуры данных.
 * <p>
 * Ключ — любое значение, а не только строка. Из-за этого объект закрывает сразу
 * две привычные роли: запись с полями ({@code точка.x}) и словарь
 * ({@code счётчики["ошибки"]}). Обе записи — одно и то же обращение, см.
 * {@link ru.wds.wdl.ast.expr.AccessExpr}.
 * <p>
 * Отсутствующий ключ даёт {@link NullValue#NULL}, а не ошибку: в динамическом
 * языке проверка «есть ли поле» — обычная операция, и заставлять писать её через
 * перехват ошибки неразумно.
 * <p>
 * Порядок вставки сохраняется намеренно: объект часто печатают и сериализуют,
 * и вывод не должен меняться от запуска к запуску.
 * <p>
 * <b>Здесь только карта.</b> Всё, что знает про классы, живёт в наследнике, и это
 * не косметика: объектов в скрипте много, экземпляров меньше, а видов {@code super}
 * — считанные разы, и платить за них полем в каждом объекте было бы неправильной
 * сделкой. Наследование при этом оставляет {@code case MapValue} рабочим для обоих:
 * что умеет объект, экземпляр умеет тоже.
 *
 * <h2>Потоки</h2>
 * Доступ к таблице идёт под коротким {@code synchronized}, и монитором служит
 * <b>сама таблица</b>, а не объект-значение. Разница видна на виде {@code super}:
 * {@link InstanceObjectValue#viewOf} даёт второе значение поверх той же карты, и замок
 * на {@code this} развёл бы их по разным мониторам — два потока писали бы в одну
 * {@code LinkedHashMap} без всякой защиты.
 * <p>
 * {@link #entries()} отдаёт <b>снимок</b>. Вьюха на живую карту стоила бы
 * {@code ConcurrentModificationException} в обычном {@code for (k in obj)}, стоило бы
 * соседнему потоку завести ключ, — а перебор объекта в языке обещан безопасным
 * и без потоков (см. {@code Interpreter.visitForEach}).
 * <p>
 * {@code LinkedHashMap} под замком, а не {@code ConcurrentHashMap}, потому что порядок
 * вставки — часть договорённости: объект печатают и сериализуют, и вывод не должен
 * меняться от запуска к запуску.
 */
public sealed class MapValue implements Value permits InstanceObjectValue {

    final Map<Value, Value> entries;

    public MapValue() {
        this(new LinkedHashMap<>());
    }

    MapValue(Map<Value, Value> entries) {
        this.entries = entries;
    }

    public static MapValue of(Map<Value, Value> source) {
        MapValue result = new MapValue();
        source.forEach(result::put);
        return result;
    }

    /** Значение по ключу или {@link NullValue#NULL}, если ключа нет. */
    public Value get(Value key) {
        Value normalized = normalizeKey(key);
        Value value;
        synchronized (entries) {
            value = entries.get(normalized);
        }
        return value != null ? value : NullValue.NULL;
    }

    public boolean has(Value key) {
        Value normalized = normalizeKey(key);
        synchronized (entries) {
            return entries.containsKey(normalized);
        }
    }

    public void put(Value key, Value value) {
        Value normalized = normalizeKey(key);
        Objects.requireNonNull(value, "value");
        synchronized (entries) {
            entries.put(normalized, value);
        }
    }

    /** Удобный доступ по имени поля: {@code obj.get("x")}. */
    public Value get(String key) {
        return get(StringValue.of(key));
    }

    public boolean has(String key) {
        return has(StringValue.of(key));
    }

    public void put(String key, Value value) {
        put(StringValue.of(key), value);
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public boolean isEmpty() {
        synchronized (entries) {
            return entries.isEmpty();
        }
    }

    /** Снимок пар в порядке вставки — почему снимок, разобрано в javadoc класса. */
    public Map<Value, Value> entries() {
        synchronized (entries) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    /**
     * Приводит ключ к каноническому виду, чтобы {@code obj[1]} и {@code obj[1.0]}
     * попадали в одну ячейку. Без этого одно и то же по смыслу число даёт два разных
     * ключа — ошибка, которую в динамическом языке почти невозможно заметить.
     */
    private static Value normalizeKey(Value key) {
        Objects.requireNonNull(key, "key");
        if (key instanceof FloatValue f) {
            double value = f.value();
            if (value == Math.rint(value) && !Double.isInfinite(value)) {
                return IntValue.of((long) value);
            }
        }
        return key;
    }

    @Override
    public ValueType type() {
        return ValueType.OBJECT;
    }

    @Override
    public String display() {
        return pairs(prefix() + "{");
    }

    /** Что стоит перед фигурной скобкой: у экземпляра — имя класса. */
    String prefix() {
        return "";
    }

    final String pairs(String opening) {
        StringJoiner joiner = new StringJoiner(", ", opening, "}");
        // По снимку, а не по живой карте: печать не должна ни бросать посреди
        // чужой записи, ни держать замок, пока считается display() вложенного значения.
        entries().forEach((key, value) ->
                joiner.add(key + ": " + (value == this ? "{...}" : value.toString())));
        return joiner.toString();
    }

    @Override
    public String toString() {
        return display();
    }
}
