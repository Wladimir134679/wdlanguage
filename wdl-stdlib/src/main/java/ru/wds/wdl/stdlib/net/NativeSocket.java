package ru.wds.wdl.stdlib.net;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Нативный класс {@code Socket}: клиентское TCP-соединение сокетов.
 */
public final class NativeSocket {

    private NativeSocket() {
    }

    public static NativeClass in(Environment scope) {
        return Types.in(scope, "Socket", NativeSocket::build);
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

    private static NativeClass build() {
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

                .method("readLine", Arity.exactly(0), (self, context, args, span) -> {
                    SocketState state = state(self, span);
                    AtomicReference<String> lineRef = new AtomicReference<>();
                    context.allowOtherThreads(() -> {
                        try {
                            lineRef.set(state.reader().readLine());
                        } catch (IOException ignored) {
                        }
                    });
                    String line = lineRef.get();
                    return line != null ? StringValue.of(line) : NullValue.NULL;
                })

                .method("onLine", Arity.exactly(1), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    SocketState state = state(self, span);
                    Thread thread = new Thread(() -> {
                        try {
                            String line;
                            while ((line = state.reader().readLine()) != null) {
                                try {
                                    callback.call(StringValue.of(line));
                                } catch (Throwable t) {
                                    System.err.println("[Socket onLine Listener Error] " + t.getMessage());
                                    t.printStackTrace();
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    });
                    thread.setDaemon(true);
                    thread.start();
                    return NullValue.NULL;
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

    private static SocketState state(NativeInstance self, Span span) {
        SocketState state = self.state(SocketState.class);
        if (state == null) {
            throw new WdlRuntimeError(span, "Сокет закрыт");
        }
        return state;
    }
}
