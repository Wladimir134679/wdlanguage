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
 * <p>
 * Функций в модуле нет и не заводится: соединение — это объект с состоянием
 * и закрытием, и всё, что тут можно сделать, делается его методами. Рабочие
 * примеры — текстовый чат в {@code examples/concurrency} и двоичный
 * в {@code examples/chat_bin}.
 */
public final class Sockets {

    private Sockets() {
    }

    public static Library library() {
        return Module.named("sys/net/socket")
                .doc("TCP: соединение и слушающий сокет")
                .type("Socket", scope -> NativeSocket.build())
                .doc("соединение: строки или байты — режим выбирается при открытии")
                .type("Server", scope -> NativeServerSocket.build(
                        Module.typeIn(scope, "Socket")))
                .doc("слушающий сокет: accept() отдаёт соединение в режиме сервера")
                .build();
    }
}
