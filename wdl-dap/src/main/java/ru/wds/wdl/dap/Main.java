package ru.wds.wdl.dap;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Запуск адаптера.
 * <p>
 * По умолчанию — через стандартные потоки: так его запускают и IntelliJ IDEA,
 * и VS Code. Первым делом {@code System.out} подменяется на {@code System.err}:
 * <b>в стандартный вывод идёт только протокол</b>, и одна случайная печать ломает
 * поток сообщений так, что клиент отваливается без объяснений. Печати скрипта это
 * тоже касается, и её здесь не бывает вовсе — {@code println} уходит клиенту
 * событием {@code output}, а не в консоль процесса.
 * <p>
 * {@code --socket=<порт>} нужен для отладки самого адаптера: подключиться к нему
 * можно чем угодно, а печать в консоль при этом ничего не ломает.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 0;
        for (String argument : args) {
            if (argument.equals("--stdio")) {
                port = 0;
            } else if (argument.startsWith("--socket=")) {
                port = Integer.parseInt(argument.substring("--socket=".length()));
            } else if (argument.equals("--help") || argument.equals("-h")) {
                usage();
                return;
            } else if (argument.equals("--version")) {
                System.out.println(WdlDebugAdapter.NAME + " " + version());
                return;
            } else {
                System.err.println("Неизвестный ключ: " + argument);
                usage();
                System.exit(2);
                return;
            }
        }
        System.exit(port > 0 ? WdlDebugServer.serve(port) : serveStdio());
    }

    private static int serveStdio() throws Exception {
        InputStream in = System.in;
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false,
                StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                StandardCharsets.UTF_8));
        return WdlDebugServer.serve(in, out, new WdlDebugAdapter());
    }

    private static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "разработка" : version;
    }

    private static void usage() {
        System.err.println("""
                wdl-dap — адаптер отладки wdl (Debug Adapter Protocol).

                  --stdio            обмен через стандартные потоки (по умолчанию)
                  --socket=<порт>    слушать порт и обслужить одного клиента
                  --version          напечатать имя и версию

                Что запускать, адаптер узнаёт из запроса launch: поле program —
                файл .wdl или каталог с main.wdl, projectRoot — корень импортов,
                args — аргументы скрипта, stopOnEntry — встать до первой инструкции.
                """);
    }
}
