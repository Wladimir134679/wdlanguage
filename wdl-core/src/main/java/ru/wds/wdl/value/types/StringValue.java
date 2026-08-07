package ru.wds.wdl.value.types;

import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.Objects;

/**
 * Строка. Неизменяема, как и в Java: любая операция возвращает новую строку.
 *
 * @param value содержимое
 */
public record StringValue(String value) implements Value {

    public static final StringValue EMPTY = new StringValue("");

    public StringValue {
        Objects.requireNonNull(value, "value");
    }

    public static StringValue of(String value) {
        return value.isEmpty() ? EMPTY : new StringValue(value);
    }

    public int length() {
        return value.length();
    }

    @Override
    public ValueType type() {
        return ValueType.STRING;
    }

    @Override
    public String display() {
        return value;
    }

    /** Отладочный вид — в кавычках, чтобы {@code "1"} было видно как строку. */
    @Override
    public String toString() {
        return '"' + value + '"';
    }
}
