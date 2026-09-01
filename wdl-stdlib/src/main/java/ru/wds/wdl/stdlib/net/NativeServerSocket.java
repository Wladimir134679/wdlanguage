package ru.wds.wdl.stdlib.net;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Нативный класс {@code Server}: серверное TCP-соединение сокетов (ServerSocket).
 *
 * <h2>Приём клиентов и потоки</h2>
 * {@code onConnection} заводит поток приёма <b>через реестр запуска</b>
 * ({@code CallContext.threads()}), а не сырым {@code new Thread}: закрытие запуска
 * обязано его остановить, иначе он переживает {@code close()} и зовёт функцию скрипта
 * по закрытым модулям.
 * <p>
 * Обработчик вызывается <b>в том же потоке приёма</b> и потому обязан быть коротким:
 * его дело — принять клиента и подписаться на его строки ({@code client.onLine(...)}),
 * а не разговаривать с ним. Каждый {@code onLine} заводит свой поток, и вот в них
 * клиенты уже обслуживаются одновременно. Долгая работа прямо здесь задержала бы
 * приём следующего клиента — и это видно в коде, а не спрятано за пулом.
 */
public final class NativeServerSocket {

    private NativeServerSocket() {
    }

    static NativeClass build(NativeClass socketClass) {
        return NativeClass.named("Server")
                .field("port", IntValue.of(9088))

                .init((self, context, args, span) -> {
                    int port = (int) args.integer(0, "порт", 9088);
                    try {
                        self.state(new ServerState(new ServerSocket(port)));
                    } catch (IOException e) {
                        throw new WdlRuntimeError(span, "Не удалось открыть ServerSocket на порту "
                                + port + ": " + e.getMessage());
                    }
                    return NullValue.NULL;
                })

                // Ожидание клиента — обычное блокирующее ожидание. Раньше вокруг него
                // стоял allowOtherThreads: пока главный поток висел в accept(), замок
                // сеанса снимался, и это был единственный способ дать колбэкам войти
                // в скрипт. Замка нет — заплатка не нужна.
                .method("accept", Arity.exactly(0), (self, context, args, span) -> {
                    ServerState state = state(self, span);
                    Socket client;
                    try {
                        client = state.server().accept();
                    } catch (IOException failed) {
                        throw new WdlRuntimeError(span, "Ошибка при accept(): " + failed.getMessage());
                    }
                    try {
                        return NativeSocket.wrap(socketClass, client);
                    } catch (IOException e) {
                        throw new WdlRuntimeError(span, "Ошибка при обертке сокета: " + e.getMessage());
                    }
                })

                .method("onConnection", Arity.exactly(1), (self, context, args, span) -> {
                    Callback callback = args.callback(0, "обработчик");
                    ServerState state = state(self, span);
                    Thread thread = context.threads().start(
                            "server-" + state.server().getLocalPort(),
                            () -> accepting(state, socketClass, callback, context));
                    state.listening(thread);
                    return self;
                })

                // Снять приём клиентов, не закрывая сервер: порт остаётся занятым,
                // а accept() по-прежнему можно позвать вручную.
                .method("stopListening", Arity.exactly(0), (self, context, args, span) -> {
                    ServerState state = self.state(ServerState.class);
                    if (state != null) {
                        state.stopListening();
                    }
                    return self;
                })

                .method("close", Arity.exactly(0), (self, context, args, span) -> {
                    ServerState state = self.state(ServerState.class);
                    if (state != null) {
                        state.close();
                        self.state((Object) null);
                    }
                    return NullValue.NULL;
                })

                .build();
    }

    /**
     * Принимает клиентов, пока сервер жив.
     * <p>
     * Ошибка обработчика не роняет приём и не уходит в {@code System.err}: она
     * печатается в вывод запуска. Один клиент, на котором обработчик споткнулся,
     * не должен закрывать сервер для остальных.
     */
    private static void accepting(ServerState state, NativeClass socketClass, Callback callback,
                                  CallContext context) {
        ServerSocket server = state.server();
        while (!server.isClosed() && !Thread.currentThread().isInterrupted()) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException closed) {
                // Сервер закрыли — это конец приёма, а не ошибка.
                return;
            }
            try {
                callback.call(NativeSocket.wrap(socketClass, client));
            } catch (WdlError error) {
                context.write("обработчик подключения: " + describe(error) + System.lineSeparator());
            } catch (IOException | RuntimeException | LinkageError failure) {
                context.write("обработчик подключения: " + failure + System.lineSeparator());
            }
        }
    }

    private static String describe(WdlError error) {
        String kind = error.kindName();
        return kind == null ? error.getMessage() : kind + ": " + error.getMessage();
    }

    private static ServerState state(NativeInstance self, Span span) {
        ServerState state = self.state(ServerState.class);
        if (state == null) {
            throw new WdlRuntimeError(span, "ServerSocket закрыт");
        }
        return state;
    }
}
