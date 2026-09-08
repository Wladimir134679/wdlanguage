package ru.wds.wdl.dap;

import ru.wds.wdl.api.WdlInstance;
import ru.wds.wdl.debug.DebugListener;
import ru.wds.wdl.debug.DebugSession;

/**
 * Живой запуск чужого приложения, к которому подключились.
 * <p>
 * Это то, ради чего отладчик вообще устроен так, как устроен: скрипт правил
 * пинг-понга, обработчик события в {@code sys.gui}, мод на сервере — всё это уже
 * работает, и «перезапустить под отладчиком» для них не ответ. Сессия здесь заводится
 * {@link WdlInstance#debugger()} по первому спросу и подключается к запуску на ходу.
 * <p>
 * Хозяин запуска — приложение, и адаптер это соблюдает: {@link #terminate()} ничего
 * не прекращает, а {@link #close()} только отпускает отладку. Отключение отладчика
 * не должно ронять игру.
 */
final class AttachTarget implements DebugTarget {

    private final DebugSession session;

    AttachTarget(WdlInstance instance, DebugListener listener, LaunchOptions options) {
        this.session = instance.debugger();
        session.listener(listener);
        session.policy(options.policy());
        session.stopOnError(options.stopOnError());
        if (options.evalTimeout() > 0) {
            session.evalTimeout(options.evalTimeout());
        }
        if (options.stopOnEntry()) {
            // «Встать на входе» для работающего скрипта означает «встать сейчас»:
            // входа впереди уже нет, а показать человеку место — то, о чём просят.
            session.pause();
        }
    }

    @Override
    public DebugSession session() {
        return session;
    }

    /** Скрипт уже считает: пускать нечего. */
    @Override
    public void start() {
    }

    /** Чужое выполнение адаптер не прекращает. */
    @Override
    public void terminate() {
    }

    /**
     * Отпускает отладку: точки перестают срабатывать, стоящие потоки идут дальше,
     * запуск возвращается к цене выключенной отладки.
     */
    @Override
    public void close() {
        session.detach();
    }
}
