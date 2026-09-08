package ru.wds.wdl.debug;

import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.source.Span;

/**
 * Выключённая отладка: один объект на весь процесс, оба метода пусты.
 * <p>
 * Заглушка нужна не ради экономии проверки на {@code null} — на горячем пути её
 * и так не спрашивают, там стоит {@code Run.debugging()}. Она нужна затем, чтобы
 * у поля запуска всегда было значение и «отладчик не задан» не превращалось
 * в отдельное состояние со своими ветками. Ровно так же устроены выключенные
 * метрики и выключенный профиль.
 */
final class NoDebugger implements Debugger {

    static final NoDebugger INSTANCE = new NoDebugger();

    private NoDebugger() {
    }

    @Override
    public void at(Stmt stmt, ExecutionContext context) {
    }

    @Override
    public void poll(Span span) {
    }
}
