package ru.wds.wdl.stdlib.net;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Нативный класс {@code Socket}: клиентское TCP-соединение сокетов.
 */
public final class NativeSocket {

    private NativeSocket() {
    }

    public static Value wrap(NativeClass socketClass, Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        NativeInstance instance = new NativeInstance(socketClass);
        instance.put("host", StringValue.of(socket.getInetAddress().getHostAddress()));
        instance.put("port", IntValue.of(socket.getPort()));
        instance.state(new SocketState(socket, reader, writer));
        return instance;
    }

    static NativeClass build() {
        return NativeClass.named("Socket")
                .field("host", StringValue.of("127.0.0.1"))
                .field("port", IntValue.of(9088))

                .init((self, context, args, span) -> {
                    String host = args.string(0, "хост", "127.0.0.1");
                    int port = (int) args.integer(1, "порт", 9088);
                    try {
                        Socket socket = new Socket(host, port);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                        self.state(new SocketState(socket, reader, writer));
                    } catch (IOException e) {
                        throw new WdlRuntimeError(span, "Не удалось подключиться к сокету " + host + ":" + port + ": " + e.getMessage());
                    }
                    return NullValue.NULL;
                })

                .method("send", Arity.exactly(1), (self, context, args, span) -> {
                    String line = args.at(0).display();
                    SocketState state = state(self, span);
                    state.writer().println(line);
                    return self;
                })

                .method("writeLine", Arity.exactly(1), (self, context, args, span) -> {
                    String line = args.at(0).display();
                    SocketState state = state(self, span);
                    state.writer().println(line);
                    return self;
                })

                // Блокирующее чтение — теперь просто блокирующее чтение. Раньше вокруг
                // него стоял allowOtherThreads: замок сеанса снимался, чтобы колбэки
                // соседних сокетов могли войти в скрипт. Замка нет, снимать нечего,
                // а остальные потоки и без того работают.
                .method("readLine", Arity.exactly(0), (self, context, args, span) -> {
                    SocketState state = state(self, span);
                    String line;
                    try {
                        line = state.reader().readLine();
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
