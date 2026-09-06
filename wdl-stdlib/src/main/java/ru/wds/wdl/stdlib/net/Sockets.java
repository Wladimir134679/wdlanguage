package ru.wds.wdl.stdlib.net;

import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.module.Library;

/**
 * Встроенный модуль {@code sys.net.socket}: сетевые сокеты TCP.
 * <p>
 * Весь модуль — два типа: клиентское соединение и слушающий сокет. Второй знает
 * первый (принятого клиента он отдаёт скрипту как {@code Socket}) и берёт его
 * из области: типы ставятся по порядку объявления, поэтому к моменту сборки
 * {@code Server} класс {@code Socket} там уже стоит.
 */
public final class Sockets {

    private Sockets() {
    }

    public static Library library() {
        return Module.named("sys/net/socket")
                .doc("TCP: соединение и слушающий сокет")
                .type("Socket", scope -> NativeSocket.build())
                .doc("соединение: чтение и запись строк, закрывается через use")
                .type("Server", scope -> NativeServerSocket.build(
                        Module.typeIn(scope, "Socket")))
                .doc("слушающий сокет: accept() отдаёт соединение")
                .build();
    }
}
