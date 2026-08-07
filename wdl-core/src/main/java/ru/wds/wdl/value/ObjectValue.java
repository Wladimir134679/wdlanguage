package ru.wds.wdl.value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Объект: набор пар «ключ — значение» с сохранением порядка вставки.
 * <p>
 * Ключ — любое значение, а не только строка. Из-за этого объект закрывает сразу
 * две привычные роли: запись с полями ({@code точка.x}) и словарь
 * ({@code счётчики["ошибки"]}). Обе записи — одно и то же обращение, см.
 * {@link ru.wds.wdl.ast.AccessExpr}.
 * <p>
 * Отсутствующий ключ даёт {@link NullValue#NULL}, а не ошибку: в динамическом
 * языке проверка «есть ли поле» — обычная операция, и заставлять писать её через
 * перехват ошибки неразумно.
 * <p>
 * Порядок вставки сохраняется намеренно: объект часто печатают и сериализуют,
 * и вывод не должен меняться от запуска к запуску.
 */
public final class ObjectValue implements Value {

    private final Map<Value, Value> entries = new LinkedHashMap<>();

    public ObjectValue() {
    }

    public static ObjectValue of(Map<Value, Value> entries) {
        ObjectValue result = new ObjectValue();
        entries.forEach(result::put);
        return result;
    }

    /** Значение по ключу или {@link NullValue#NULL}, если ключа нет. */
    public Value get(Value key) {
        Value value = entries.get(normalizeKey(key));
        return value != null ? value : NullValue.NULL;
    }

    public boolean has(Value key) {
        return entries.containsKey(normalizeKey(key));
    }

    public void put(Value key, Value value) {
        entries.put(normalizeKey(key), Objects.requireNonNull(value, "value"));
    }

    /** Удобный доступ по имени поля: {@code obj.get("x")}. */
    public Value get(String key) {
        return get(StringValue.of(key));
    }

    public void put(String key, Value value) {
        put(StringValue.of(key), value);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Map<Value, Value> entries() {
        return Collections.unmodifiableMap(entries);
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
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        entries.forEach((key, value) -> joiner.add(key + ": " + (value == this ? "{...}" : value.toString())));
        return joiner.toString();
    }

    @Override
    public String toString() {
        return display();
    }
}
