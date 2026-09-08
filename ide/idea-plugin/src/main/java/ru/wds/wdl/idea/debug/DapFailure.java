package ru.wds.wdl.idea.debug;

/**
 * Адаптер не сделал то, о чём просили: отказал, не ответил или умер.
 * <p>
 * Отдельный класс ради текста. Сообщение приходит от адаптера по-русски и написано
 * для человека («поток не остановлен: шагать в нём нечем»), а значит, обязано дойти
 * до окна отладчика целым, а не превратиться в {@code ExecutionException} с чужой
 * обёрткой поверх.
 */
final class DapFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    DapFailure(String message) {
        super(message);
    }
}
