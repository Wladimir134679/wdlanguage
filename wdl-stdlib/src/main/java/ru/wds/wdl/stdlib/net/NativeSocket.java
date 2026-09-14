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
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
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
 * {@code writeBytes}, {@code onBytes}. Смешать их на одном соединении нельзя,
 * и почему — написано в {@link SocketState}: буфер строк читает вперёд и съедает
 * байты, которых потом не хватит. Обращение к чужому режиму — ошибка с объяснением,
 * а не тихий ноль.
 * <pre>{@code
 * use (s = new net.Socket("localhost", 9000, "bytes")) {
 *     s.writeBytes(bin.hex("0001"))
 *     header = s.readExactly(4)
 * }
 * }</pre>
 *
 * <h2>У каждого режима свой слушатель, и это не удвоение</h2>
 * {@code onLine} и {@code onBytes} — один и тот же приём (поток читает, пока
 * соединение живо, и зовёт обработчик), но разного типа ответ, и знать его надо
 * из текста вызова, а не из того, как открывали соединение сотней строк выше.
 * Разница между ними — не только тип: <b>строка — это сообщение, а кусок байтов
 * нет</b>. У TCP нет границ сообщений, поэтому {@code onBytes} отдаёт то, что
 * пришло, — кадр может прийти двумя кусками, а два кадра одним. Склейка и нарезка
 * — дело скрипта, и делается она накопителем: рабочий пример —
 * {@code examples/chat_bin/protocol.wdl}.
 */
public final class NativeSocket {

    /**
     * Сколько байтов байтовый слушатель берёт за одно чтение, если скрипт не сказал
     * иного. Это ёмкость буфера, а не размер выдачи: {@code read} отдаёт столько,
     * сколько уже пришло, и обработчик получает именно этот кусок.
     */
    private static final int CHUNK = 8192;

    /** Контракт {@code onBytes}: обработчик обязателен, размер куска — нет. */
    private static final Signature ON_BYTES = Signature.of(
            Param.required("handler"), Param.optional("chunk", IntValue.of(CHUNK)));

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

                .method("send", Signature.of(Param.required("message")), (self, context, args, span) -> {
                    String line = args.at(0).display();
                    state(self, span).text(span).println(line);
                    return self;
                })

                .method("writeLine", Signature.of(Param.required("line")), (self, context, args, span) -> {
                    String line = args.at(0).display();
                    state(self, span).text(span).println(line);
                    return self;
                })

                // --- байтовый режим ----------------------------------------------
                //
                // Отдельными именами, а не признаком у readLine: тип ответа должен
                // быть виден из текста вызова, а не выводиться из того, как открывали
                // соединение сотней строк выше.

                .method("writeBytes", Signature.of(Param.required("data")), (self, context, args, span) -> {
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
                .method("readBytes", Signature.of(Param.required("size")), (self, context, args, span) -> {
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
                .method("readExactly", Signature.of(Param.required("size")), (self, context, args, span) -> {
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
                .method("onLine", Signature.of(Param.required("handler")), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    SocketState state = state(self, span);
                    // Спрашиваем режим здесь, а не в потоке слушателя: отказ обязан
                    // прийти туда, где написан onLine, а не всплыть в чужом потоке.
                    state.lines(span);
                    String title = "socket-" + state.socket().getPort();
                    // Будильник обязателен: из readLine поток прерыванием не выходит,
                    // и без него закрытие запуска ждало бы его весь свой срок,
                    // а потом называло в логе.
                    Thread thread = context.threads().start(title,
                            () -> listen(state, callback, context), state::stopListening);
                    state.listening(thread);
                    return self;
                })

                // Байтовый слушатель: тот же приём, что onLine, только кусками.
                // Размер куска — необязательный второй аргумент: у одного протокола
                // кадры по сотне байтов, у другого по мегабайту, и держать буфер
                // на мегабайт ради первого незачем.
                .method("onBytes", ON_BYTES, (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    int chunk = count(args, 1, "сколько байт брать за раз", CHUNK, span);
                    SocketState state = state(self, span);
                    // Режим спрашиваем здесь, а не в потоке слушателя: отказ обязан
                    // прийти туда, где написан onBytes, а не всплыть в чужом потоке.
                    state.input(span);
                    args.context().allocating(chunk, "сокет: буфер слушателя", span);
                    Thread thread = context.threads().start(
                            "socket-bytes-" + state.socket().getPort(),
                            () -> listenBytes(state, callback, chunk, context),
                            state::stopListening);
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
                deliver(callback, StringValue.of(line), "обработчик строки сокета", context);
            }
        } catch (IOException closed) {
            // Соединение закрыто — с той стороны или нашим же close(). Это конец
            // работы слушателя, а не ошибка: сообщать тут не о чем.
        }
    }

    /**
     * Читает байты кусками, пока соединение живо, и отдаёт каждый кусок обработчику.
     * <p>
     * Буфер один на весь слушатель, а наружу каждый раз уходит снимок нужной длины:
     * байты в языке неизменяемы, и отдать обработчику сам буфер значило бы менять
     * уже отданное значение под ногами следующего чтения.
     * <p>
     * Конец потока ({@code read} вернул -1) завершает слушателя молча — это закрытое
     * соединение, а не ошибка, ровно как у {@link #listen}.
     */
    private static void listenBytes(SocketState state, Callback callback, int chunk,
                                    CallContext context) {
        byte[] buffer = new byte[chunk];
        try {
            int read;
            while (!Thread.currentThread().isInterrupted()
                    && (read = state.in().read(buffer)) >= 0) {
                if (read > 0) {
                    deliver(callback, BytesValue.of(buffer, 0, read),
                            "обработчик байтов сокета", context);
                }
            }
        } catch (IOException closed) {
            // То же, что у listen: соединение закрыли — с той стороны, нашим close()
            // или будильником при закрытии запуска. Сообщать тут не о чем.
        }
    }

    /**
     * Отдаёт прочитанное обработчику скрипта.
     * <p>
     * Ошибка обработчика не роняет слушателя и не уходит в {@code System.err}: она
     * печатается в вывод запуска — тот самый, который задало приложение. Одно
     * испорченное сообщение от одного клиента не должно отключать чат остальным.
     */
    private static void deliver(Callback callback, Value item, String what,
                                CallContext context) {
        try {
            callback.call(item);
        } catch (WdlError error) {
            context.write(what + ": " + describe(error) + System.lineSeparator());
        } catch (RuntimeException | LinkageError failure) {
            context.write(what + ": " + failure + System.lineSeparator());
        }
    }

    /** Сколько байтов просят читать: положительное число, влезающее в массив Java. */
    private static int size(Args arguments, Span span) {
        int want = count(arguments, 0, "сколько байт читать", 0, span);
        arguments.context().allocating(want, "сокет: чтение байтов", span);
        return want;
    }

    /**
     * Число байтов из аргумента метода: положительное и влезающее в массив Java.
     *
     * @param fallback ответ, когда аргумента нет; {@code 0} — «аргумент обязателен»
     */
    private static int count(Args arguments, int index, String what, int fallback, Span span) {
        long want = fallback > 0
                ? arguments.integer(index, what, fallback)
                : arguments.integer(index, what);
        if (want <= 0 || want > Integer.MAX_VALUE - 8) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span,
                    what + " — положительное число, а здесь " + want);
        }
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
