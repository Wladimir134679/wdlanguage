package ru.wds.wdl.profile;

/**
 * Выключенный профиль: один объект на весь процесс, он же и заглушка счёта.
 * <p>
 * Приёмник и счёт совмещены не ради экономии класса, а ради того, чтобы {@code enter}
 * не создавал вообще ничего: он возвращает себя, а {@code close()} пуст. Ровно так же
 * устроены выключенные метрики — и по той же причине.
 */
final class NoProfiler implements Profiler, Probe {

    static final NoProfiler INSTANCE = new NoProfiler();

    private NoProfiler() {
    }

    @Override
    public Probe enter(CallSite site) {
        return this;
    }

    @Override
    public boolean recording() {
        return false;
    }

    @Override
    public void close() {
    }
}
