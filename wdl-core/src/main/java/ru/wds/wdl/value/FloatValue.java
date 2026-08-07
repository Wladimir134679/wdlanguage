package ru.wds.wdl.value;

/**
 * Вещественное число, {@code double}.
 *
 * @param value значение
 */
public record FloatValue(double value) implements NumberValue {

    public static FloatValue of(double value) {
        return new FloatValue(value);
    }

    @Override
    public double asDouble() {
        return value;
    }

    @Override
    public long asLong() {
        return (long) value;
    }

    @Override
    public boolean isInteger() {
        return false;
    }

    /**
     * Печатаем коротко и без сюрпризов: {@code inf}, {@code -inf}, {@code nan} вместо
     * джавовских {@code Infinity} и {@code NaN} — это значения языка wdl, а не Java.
     * Целое по значению число всё равно печатается с точкой ({@code 2.0}): раз тип
     * вещественный, скрывать это от читателя не надо.
     */
    @Override
    public String display() {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        return Double.toString(value);
    }

    @Override
    public String toString() {
        return display();
    }
}
