package ru.wds.wdl.metrics;

/**
 * Выключенные метрики: один объект на весь процесс, он же и заглушка замера.
 * <p>
 * Приёмник и замер здесь совмещены не ради экономии класса, а ради того, чтобы
 * {@code begin} не создавал вообще ничего: он возвращает себя, а {@code close()} пуст.
 * Пара «пустой вызов — пустой вызов» разворачивается JIT в ничто, поэтому выключенные
 * метрики стоят ровно ноль и мерять их наличие флагом в вызывающем не нужно.
 */
final class NoMetrics implements Metrics, Measure {

    static final NoMetrics INSTANCE = new NoMetrics();

    private NoMetrics() {
    }

    @Override
    public Measure begin(Stage stage, String subject, boolean module) {
        return this;
    }

    @Override
    public void close() {
    }
}
