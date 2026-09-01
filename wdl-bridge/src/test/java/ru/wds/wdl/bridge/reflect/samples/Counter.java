package ru.wds.wdl.bridge.reflect.samples;

/**
 * Объект с состоянием: поле, пара аксессоров и метод, который меняет получателя.
 * <p>
 * {@code getAndIncrement} здесь не для полноты — он и есть довод против правила
 * «метод на get это свойство».
 */
public class Counter {

    public static final int LIMIT = 10;

    public final String name;

    private int value;

    public Counter(String name) {
        this(name, 0);
    }

    public Counter(String name, int start) {
        this.name = name;
        this.value = start;
    }

    public static Counter from(int start) {
        return new Counter("from", start);
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public int getAndIncrement() {
        return value++;
    }

    public void add(int delta) {
        value += delta;
    }

    public void fail() {
        throw new IllegalStateException("так нельзя");
    }

    public void failChecked() throws Exception {
        throw new Exception("проверяемое");
    }

    @Override
    public String toString() {
        return name + "=" + value;
    }
}
