package ru.wds.wdl.value.types;

import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

/**
 * Логическое значение. Существует ровно два экземпляра: {@link #TRUE} и {@link #FALSE}.
 * <p>
 * Самостоятельный тип, а не число 0/1: иначе {@code true + 1} и {@code if 2}
 * становятся «работающим» кодом, а вместе с ними приходит целый класс ошибок,
 * которые язык мог бы поймать сам.
 */
public final class BoolValue implements Value {

    public static final BoolValue TRUE = new BoolValue(true);
    public static final BoolValue FALSE = new BoolValue(false);

    private final boolean value;

    private BoolValue(boolean value) {
        this.value = value;
    }

    public static BoolValue of(boolean value) {
        return value ? TRUE : FALSE;
    }

    public boolean value() {
        return value;
    }

    @Override
    public ValueType type() {
        return ValueType.BOOL;
    }

    @Override
    public boolean isTruthy() {
        return value;
    }

    @Override
    public String display() {
        return value ? "true" : "false";
    }

    @Override
    public String toString() {
        return display();
    }
}
