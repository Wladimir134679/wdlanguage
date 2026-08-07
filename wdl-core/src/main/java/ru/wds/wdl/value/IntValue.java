package ru.wds.wdl.value;

/**
 * Целое число, 64 бита со знаком.
 *
 * @param value значение
 */
public record IntValue(long value) implements NumberValue {

    public static final IntValue ZERO = new IntValue(0);
    public static final IntValue ONE = new IntValue(1);

    /** Кэш маленьких значений: индексы, счётчики и флаги живут именно здесь. */
    private static final int CACHE_LOW = -128;
    private static final int CACHE_HIGH = 1024;
    private static final IntValue[] CACHE = new IntValue[CACHE_HIGH - CACHE_LOW + 1];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new IntValue(CACHE_LOW + i);
        }
    }

    public static IntValue of(long value) {
        if (value >= CACHE_LOW && value <= CACHE_HIGH) {
            return CACHE[(int) value - CACHE_LOW];
        }
        return new IntValue(value);
    }

    @Override
    public double asDouble() {
        return value;
    }

    @Override
    public long asLong() {
        return value;
    }

    @Override
    public boolean isInteger() {
        return true;
    }

    @Override
    public String display() {
        return Long.toString(value);
    }

    @Override
    public String toString() {
        return display();
    }
}
