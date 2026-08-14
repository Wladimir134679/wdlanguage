package ru.wds.wdl.stdlib.net;

import ru.wds.wdl.embed.Library;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.runtime.Environment;

import java.util.Objects;

/**
 * Встроенный модуль {@code sys.net.socket}: сетевые сокеты TCP.
 */
public final class Sockets implements Library {

    private Sockets() {
    }

    public static Library library() {
        return new Sockets();
    }

    @Override
    public String name() {
        return "sys/net/socket";
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");
        NativeClass socket = NativeSocket.in(scope);
        NativeClass server = NativeServerSocket.in(scope, socket);

        scope.define(socket.name(), socket);
        scope.define(server.name(), server);
        return scope;
    }
}
