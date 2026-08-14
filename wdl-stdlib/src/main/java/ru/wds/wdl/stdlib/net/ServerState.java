package ru.wds.wdl.stdlib.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Состояние слушающего сервера: сам сокет и поток приёма клиентов, если его завели.
 * <p>
 * Отдельным типом — по той же причине, что и {@link SocketState}: закрытие обязано
 * снимать не только порт, но и поток, который на нём висит. Поток, стоящий
 * в {@code accept()} закрытого сервера, — не «почти завершился», а поток до конца
 * процесса.
 */
public record ServerState(ServerSocket server, AtomicReference<Thread> listener) {

    public ServerState(ServerSocket server) {
        this(server, new AtomicReference<>());
    }

    /** Запоминает поток приёма: он же будет остановлен при закрытии. */
    public void listening(Thread thread) {
        listener.set(thread);
    }

    /**
     * Останавливает приём клиентов.
     * <p>
     * Только прерывание: из {@code accept()} поток выходит по закрытию сокета,
     * и делает это {@link #close()}. Прерывание же снимает его, если он в этот момент
     * внутри обработчика.
     */
    public void stopListening() {
        Thread thread = listener.getAndSet(null);
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void close() {
        stopListening();
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }
}
