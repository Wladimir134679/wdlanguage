package ru.wds.wdl.stdlib.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Состояние открытого TCP-сокета: соединение, его потоки чтения и записи
 * и слушатель, если его завели.
 * <p>
 * Слушатель здесь, а не рядом, ровно затем, чтобы {@code close()} умел его снять:
 * поток, читающий из закрытого сокета, — это не «почти завершился», а поток, который
 * висит на {@code readLine} до конца процесса.
 */
public record SocketState(Socket socket, BufferedReader reader, PrintWriter writer,
                          AtomicReference<Thread> listener) {

    public SocketState(Socket socket, BufferedReader reader, PrintWriter writer) {
        this(socket, reader, writer, new AtomicReference<>());
    }

    /** Запоминает поток слушателя: он же будет остановлен при закрытии. */
    public void listening(Thread thread) {
        listener.set(thread);
    }

    /**
     * Останавливает слушателя, если он есть.
     * <p>
     * Прерывание <b>и</b> закрытие сокета: из блокирующего {@code readLine} поток
     * прерыванием не выходит — его будит именно закрытие потока ввода. Прерывание же
     * снимает его, если он в этот момент внутри скрипта.
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
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
