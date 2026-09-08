package ru.wds.wdl.debug;

/**
 * Отладчик попросил невозможное: вычислить выражение в работающем потоке, разобрать
 * то, что не разбирается, дождаться того, что не укладывается в срок.
 * <p>
 * Отдельно от {@code WdlError} намеренно: это не ошибка скрипта, и обработчик
 * {@code try} в скрипте её видеть не должен — она случилась не в нём, а в разговоре
 * отладчика с движком.
 */
public final class DebugException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DebugException(String message) {
        super(message);
    }

    public DebugException(String message, Throwable cause) {
        super(message, cause);
    }
}
