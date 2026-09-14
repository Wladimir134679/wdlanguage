package ru.wds.wdl.stdlib.net;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BytesValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Нативный класс {@code Socket}: клиентское TCP-соединение сокетов.
 *
 * <h2>Два режима, и выбираются они при открытии</h2>
 * {@code "text"} (умолчание) — строки: {@code readLine}, {@code writeLine},
 * {@code onLine}. {@code "bytes"} — байты: {@code readBytes}, {@code readExactly},
 * {@code writeBytes}. Смешать их на одном соединении нельзя, и почему — написано
 * в {@link SocketState}: буфер строк читает вперёд и съедает байты, которых потом
 * не хватит. Обращение к чужому режиму — ошибка с объяснением, а не тихий ноль.
 * <pre>{@code
 * use (s = new net.Socket("localhost", 9000, "bytes")) {
 *     s.writeBytes(bin.hex("0001"))
 *     header = s.readExactly(4)
 * }
 * }</pre>
 */
public final class NativeSocket {

    private NativeSocket() {
    }

    public static Value wrap(NativeClass socketClass, Socket socket, String mode) throws IOException {
        NativeInstance instance = new NativeInstance(socketClass);
        instance.put("host", StringValue.of(socket.getInetAddress().getHostAddress()));
        instance.put("port", IntValue.of(socket.getPort()));
        instance.put("mode", StringValue.of(mode));
        instance.state(SocketState.of(socket, mode));
        return instance;
    }

    static NativeClass build() {
        return NativeClass.named("Socket")
                .field("host", StringValue.of("127.0.0.1"))
                .field("port", IntValue.of(9088))
                // Режим — обычное поле: он задан при открытии и потом не меняется,
                // значит и читается обычным обращением, s.mode.
                .field("mode", StringValue.of(SocketState.TEXT))

                .init((self, context, args, span) -> {
                    String host = args.string(0, "хост", "127.0.0.1");
                    int port = (int) args.integer(1, "порт", 9088);
                    String mode = SocketState.modeOf(
                            args.string(2, "режим", SocketState.TEXT), span);
                    self.put("mode", StringValue.of(mode));
                    try {
                        self.state(SocketState.of(new Socket(host, port), mode));
                    } catch (IOException e) {
                        throw new WdlRuntimeError(span, "Не удалось подключиться к сокету " + host + ":" + port + ": " + e.getMessage());
                    }
                    return NullValue.NULL;
                })

                .method("send", Arity.exactly(1), (self, context, args, span) -> {
                    String line = args.at(0).display();
                    state(self, span).text(span).println(line);
                    return self;
                })

                .method("writeLine", Arity.exactly(1), (self, context, args, span) -> {
                    String line = args.at(0).display();
                    state(self, span).text(span).println(line);
                    return self;
                })

                // --- байтовый режим ----------------------------------------------
                //
                // Отдельными именами, а не признаком у readLine: тип ответа должен
                // быть виден из текста вызова, а не выводиться из того, как открывали
                // соединение сотней строк выше.

                .method("writeBytes", Arity.exactly(1), (self, context, args, span) -> {
                    BytesValue data = args.bytes(0, "байты");
                    OutputStream out = state(self, span).output(span);
                    try {
                        out.write(data.toArray());
                        // Сразу же: пакет, оставшийся в буфере, — это «собеседник
                        // не отвечает», и причину будут искать где угодно, только
                        // не здесь.
                        out.flush();
                    } catch (IOException failed) {
                        throw new WdlRuntimeError(span, "не удалось отправить "
                                + data.size() + " байт: " + failed.getMessage());
                    }
                    return self;
                })

                // Сколько пришло, столько и отдаём: у TCP нет границ сообщений,
                // и «прочитать ровно n» — это отдельная просьба, readExactly.
                // Конец потока — пустые байты: подменять тип ответа на null там,
                // где пустой ответ законен, значит заставлять проверять оба.
                .method("readBytes", Arity.exactly(1), (self, context, args, span) -> {
                    int want = size(args, span);
                    byte[] buffer = new byte[want];
                    try {
                        int read = state(self, span).input(span).read(buffer, 0, want);
                        return read <= 0 ? BytesValue.EMPTY : BytesValue.of(buffer, 0, read);
                    } catch (IOException closed) {
                        return BytesValue.EMPTY;
                    }
                })

                // Заголовок фиксированной длины читают так: либо n байт, либо ошибка.
                // Короткий ответ здесь — это оборванное соединение, и отдать его
                // наверх молча значило бы разобрать формат по мусору.
                .method("readExactly", Arity.exactly(1), (self, context, args, span) -> {
                    int want = size(args, span);
                    byte[] buffer = new byte[want];
                    int read;
                    try {
                        read = state(self, span).input(span).readNBytes(buffer, 0, want);
                    } catch (IOException closed) {
                        read = 0;
                    }
                    if (read < want) {
                        throw new WdlRuntimeError(span, "соединение закрылось раньше времени:"
                                + " ждали " + want + " байт, получили " + read);
                    }
                    return BytesValue.owning(buffer);
                })

                // Блокирующее чтение — теперь просто блокирующее чтение. Раньше вокруг
                // него стоял allowOtherThreads: замок сеанса снимался, чтобы колбэки
                // соседних сокетов могли войти в скрипт. Замка нет, снимать нечего,
                // а остальные потоки и без того работают.
                .method("readLine", Arity.exactly(0), (self, context, args, span) -> {
                    SocketState state = state(self, span);
                    String line;
                    try {
                        line = state.lines(span).readLine();
                    } catch (IOException closed) {
                        return NullValue.NULL;
                    }
                    return line != null ? StringValue.of(line) : NullValue.NULL;
                })

                // Слушатель строк в своём потоке — и поток этот заводится через реестр
                // запуска, а не сырым new Thread. Иначе он переживает close() и зовёт
                // функцию скрипта по закрытым модулям.
                .method("onLine", Arity.exactly(1), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    SocketState state = state(self, span);
                    // Спрашиваем режим здесь, а не в потоке слушателя: отказ обязан
                    // прийти туда, где написан onLine, а не всплыть в чужом потоке.
                    state.lines(span);
                    String title = "socket-" + state.socket().getPort();
                    Thread thread = context.threads().start(title,
                            () -> listen(state, callback, context));
                    state.listening(thread);
                    return self;
                })

                // Снять слушателя, не закрывая соединение: читать перестали, писать
                // по-прежнему можно.
                .method("stopListening", Arity.exactly(0), (self, context, args, span) -> {
                    SocketState state = self.state(SocketState.class);
                    if (state != null) {
                        state.stopListening();
                    }
                    return self;
                })

                .method("close", Arity.exactly(0), (self, context, args, span) -> {
                    SocketState state = self.state(SocketState.class);
                    if (state != null) {
                        state.close();
                        self.state((Object) null);
                    }
                    return NullValue.NULL;
                })

                .build();
    }

    /**
     * Читает строки, пока соединение живо, и отдаёт каждую обработчику.
     * <p>
     * Ошибка обработчика не роняет слушателя и не уходит в {@code System.err}: она
     * печатается в вывод запуска — тот самый, который задало приложение. Одно
     * испорченное сообщение от одного клиента не должно отключать чат остальным.
     */
    private static void listen(SocketState state, Callback callback, CallContext context) {
        try {
            String line;
            while (!Thread.currentThread().isInterrupted()
                    && (line = state.reader().readLine()) != null) {
                try {
                    callback.call(StringValue.of(line));
                } catch (WdlError error) {
                    context.write("обработчик строки сокета: " + describe(error)
                            + System.lineSeparator());
                } catch (RuntimeException | LinkageError failure) {
                    context.write("обработчик строки сокета: " + failure
                            + System.lineSeparator());
                }
            }
        } catch (IOException closed) {
            // Соединение закрыто — с той стороны или нашим же close(). Это конец
            // работы слушателя, а не ошибка: сообщать тут не о чем.
        }
    }

    /** Сколько байтов просят: положительное число, влезающее в массив Java. */
    private static int size(Args arguments, Span span) {
        long want = arguments.integer(0, "сколько байт");
        if (want <= 0 || want > Integer.MAX_VALUE - 8) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span,
                    "сколько байт читать — положительное число, а здесь " + want);
        }
        arguments.context().allocating(want, "сокет: чтение байтов", span);
        return (int) want;
    }

    private static String describe(WdlError error) {
        String kind = error.kindName();
        return kind == null ? error.getMessage() : kind + ": " + error.getMessage();
    }

    private static SocketState state(NativeInstance self, Span span) {
        SocketState state = self.state(SocketState.class);
        if (state == null) {
            throw new WdlRuntimeError(span, "Сокет закрыт");
        }
        return state;
    }
}
