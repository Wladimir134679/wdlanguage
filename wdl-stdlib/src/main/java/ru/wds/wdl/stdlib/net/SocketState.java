package ru.wds.wdl.stdlib.net;

import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Состояние открытого TCP-сокета: соединение, его потоки и слушатель, если его завели.
 * <p>
 * Слушатель здесь, а не рядом, ровно затем, чтобы {@code close()} умел его снять:
 * поток, читающий из закрытого сокета, — это не «почти завершился», а поток, который
 * висит на {@code readLine} до конца процесса.
 *
 * <h2>Режим выбирается при открытии и потом не меняется</h2>
 * Текстовый и байтовый режимы на одном соединении <b>не сочетаются</b>, и это не
 * ограничение реализации, а свойство буферизации: {@code BufferedReader} читает
 * вперёд — он забирает из сокета столько, сколько дадут, и декодирует. Прочитай
 * после него сырые байты — и первых нескольких килобайт уже не будет, они съедены
 * и превращены в символы. Поэтому режим — часть открытия ({@code net.Socket(host,
 * port, "bytes")}), и потоки заводятся только те, которые в этом режиме нужны:
 * в текстовом — {@code BufferedReader}/{@code PrintWriter}, в байтовом —
 * {@code BufferedInputStream}/{@code OutputStream}. Слушатель тоже свой у каждого
 * режима: {@code onLine} читает строки, {@code onBytes} — куски байтов.
 * <p>
 * Обращение к чужому режиму — <b>ошибка с объяснением</b>, а не тихий ноль: строка,
 * прочитанная из байтового сокета «как-нибудь», — это испорченные данные, о которых
 * узнают через час.
 */
public record SocketState(Socket socket, String mode, BufferedReader reader, PrintWriter writer,
                          InputStream in, OutputStream out, AtomicReference<Thread> listener) {

    /** Режим строк: {@code readLine}, {@code writeLine}, {@code onLine}. Умолчание. */
    public static final String TEXT = "text";

    /** Режим байтов: {@code readBytes}, {@code readExactly}, {@code writeBytes}, {@code onBytes}. */
    public static final String BYTES = "bytes";

    /**
     * Открывает потоки, нужные этому режиму, — и только их.
     *
     * @param mode {@link #TEXT} или {@link #BYTES}
     */
    public static SocketState of(Socket socket, String mode) throws IOException {
        if (BYTES.equals(mode)) {
            return new SocketState(socket, BYTES, null, null,
                    new BufferedInputStream(socket.getInputStream()),
                    socket.getOutputStream(), new AtomicReference<>());
        }
        return new SocketState(socket, TEXT,
                new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        StandardCharsets.UTF_8)),
                new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),
                        StandardCharsets.UTF_8), true),
                null, null, new AtomicReference<>());
    }

    /** Имя режима как значение языка: {@code "text"} или {@code "bytes"}. */
    public static String modeOf(String given, Span span) {
        if (TEXT.equals(given) || BYTES.equals(given)) {
            return given;
        }
        throw new WdlRuntimeError(ErrorKind.VALUE, span, "режим сокета — это \"" + TEXT
                + "\" (строки) или \"" + BYTES + "\" (байты), а здесь \"" + given + "\"."
                + " Поменять его после открытия нельзя: буфер строк читает вперёд"
                + " и съедает байты, которых потом не хватит");
    }

    /** Строки этого соединения или отказ, если оно открыто под байты. */
    public BufferedReader lines(Span span) {
        if (reader == null) {
            throw wrongMode(BYTES, span);
        }
        return reader;
    }

    /** Запись строк или отказ по той же причине. */
    public PrintWriter text(Span span) {
        if (writer == null) {
            throw wrongMode(BYTES, span);
        }
        return writer;
    }

    /** Байты этого соединения или отказ, если оно открыто под строки. */
    public InputStream input(Span span) {
        if (in == null) {
            throw wrongMode(TEXT, span);
        }
        return in;
    }

    /** Запись байтов или отказ по той же причине. */
    public OutputStream output(Span span) {
        if (out == null) {
            throw wrongMode(TEXT, span);
        }
        return out;
    }

    private WdlRuntimeError wrongMode(String actual, Span span) {
        return new WdlRuntimeError(ErrorKind.TYPE, span, "соединение открыто в режиме \""
                + actual + "\", и здесь его так использовать нельзя. Режим задаётся"
                + " при открытии: 'new net.Socket(host, port, \"" + (TEXT.equals(actual)
                ? BYTES : TEXT) + "\")'");
    }

    /** Запоминает поток слушателя: он же будет остановлен при закрытии. */
    public void listening(Thread thread) {
        listener.set(thread);
    }

    /**
     * Останавливает слушателя, если он есть.
     * <p>
     * Прерывание <b>и</b> конец ввода, потому что одного прерывания мало: поток,
     * стоящий в {@code readLine} или {@code read}, из них прерыванием не выходит —
     * его будит только конец потока ввода. Прерывание снимает его, если он в этот
     * момент внутри скрипта, а {@code shutdownInput} — если он внутри чтения.
     * Без второго {@code stopListening} на живом соединении оставлял бы поток висеть
     * до конца процесса: раньше здесь так и было, и написанное в этом абзаце
     * расходилось с кодом.
     * <p>
     * Закрывается именно ввод, а не соединение: писать после этого по-прежнему
     * можно, и это ровно то, что обещает метод — «читать перестали». Обратной
     * дороги нет: следующее чтение из этого сокета увидит конец данных.
     */
    public void stopListening() {
        Thread thread = listener.getAndSet(null);
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            if (!socket.isClosed() && !socket.isInputShutdown()) {
                socket.shutdownInput();
            }
        } catch (IOException ignored) {
            // Соединение уже разорвано — значит слушатель и так выходит.
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
