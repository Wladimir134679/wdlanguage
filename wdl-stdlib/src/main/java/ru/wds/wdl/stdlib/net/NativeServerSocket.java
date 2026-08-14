package ru.wds.wdl.stdlib.net;

import ru.wds.wdl.embed.Callback;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.stdlib.Types;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Нативный класс {@code Server}: серверное TCP-соединение сокетов (ServerSocket).
 */
public final class NativeServerSocket {

    private NativeServerSocket() {
    }

    public static NativeClass in(Environment scope, NativeClass socketClass) {
        return Types.in(scope, "Server", () -> build(socketClass));
    }

    private static NativeClass build(NativeClass socketClass) {
        return NativeClass.named("Server")
                .field("port", IntValue.of(9088))

                .init((self, context, args, span) -> {
                    int port = (int) args.integer(0, "порт", 9088);
                    try {
                        ServerSocket server = new ServerSocket(port);
                        self.state(server);
                    } catch (IOException e) {
                        throw new WdlRuntimeError(span, "Не удалось открыть ServerSocket на порту " + port + ": " + e.getMessage());
                    }
                    return NullValue.NULL;
                })

                .method("accept", Arity.exactly(0), (self, context, args, span) -> {
                    ServerSocket server = server(self, span);
                    AtomicReference<Socket> clientRef = new AtomicReference<>();
                    AtomicReference<IOException> exceptionRef = new AtomicReference<>();

                    context.allowOtherThreads(() -> {
                        try {
                            clientRef.set(server.accept());
                        } catch (IOException e) {
                            exceptionRef.set(e);
                        }
                    });

                    if (exceptionRef.get() != null) {
                        throw new WdlRuntimeError(span, "Ошибка при accept(): " + exceptionRef.get().getMessage());
                    }
                    try {
                        return NativeSocket.wrap(socketClass, clientRef.get());
                    } catch (IOException e) {
                        throw new WdlRuntimeError(span, "Ошибка при обертке сокета: " + e.getMessage());
                    }
                })

                .method("onConnection", Arity.exactly(1), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    ServerSocket server = server(self, span);
                    Thread thread = new Thread(() -> {
                        while (!server.isClosed()) {
                            try {
                                Socket client = server.accept();
                                callback.call(NativeSocket.wrap(socketClass, client));
                            } catch (IOException ignored) {
                                break;
                            }
                        }
                    });
                    thread.setDaemon(true);
                    thread.start();
                    return NullValue.NULL;
                })

                .method("close", Arity.exactly(0), (self, context, args, span) -> {
                    ServerSocket server = self.state(ServerSocket.class);
                    if (server != null) {
                        try {
                            server.close();
                        } catch (IOException ignored) {
                        }
                        self.state((Object) null);
                    }
                    return NullValue.NULL;
                })

                .build();
    }

    private static ServerSocket server(NativeInstance self, Span span) {
        ServerSocket server = self.state(ServerSocket.class);
        if (server == null) {
            throw new WdlRuntimeError(span, "ServerSocket закрыт");
        }
        return server;
    }
}
