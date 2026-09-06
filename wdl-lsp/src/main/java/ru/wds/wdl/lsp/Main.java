package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import ru.wds.wdl.tools.service.LanguageService;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Запуск сервера.
 * <p>
 * По умолчанию — через стандартные потоки: так его запускают и IntelliJ IDEA,
 * и VS Code. Первым делом {@code System.out} подменяется на {@code System.err}:
 * <b>в стандартный вывод идёт только JSON-RPC</b>, и одна случайная печать
 * ломает поток сообщений так, что клиент отваливается без объяснений. Настоящий
 * вывод остаётся у launcher'а, и больше его ни у кого нет.
 * <p>
 * {@code --socket=<порт>} нужен для отладки самого сервера: подключиться к нему
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
                System.out.println(WdlLanguageServer.NAME);
                return;
            } else {
                System.err.println("Неизвестный ключ: " + argument);
                usage();
                System.exit(2);
                return;
            }
        }
        System.exit(port > 0 ? serveSocket(port) : serveStdio());
    }

    private static int serveStdio() throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false,
                StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                StandardCharsets.UTF_8));
        return serve(System.in, out);
    }

    private static int serveSocket(int port) throws Exception {
        try (ServerSocket server = new ServerSocket(port)) {
            System.err.println(WdlLanguageServer.NAME + ": жду клиента на порту "
                    + server.getLocalPort());
            try (Socket client = server.accept()) {
                return serve(client.getInputStream(), client.getOutputStream());
            }
        }
    }

    /** Один сеанс со стандартным каталогом имён. */
    static int serve(InputStream in, OutputStream out) throws Exception {
        return serve(in, out, LanguageService.of(StandardCatalog.create()));
    }

    /**
     * Один сеанс: сервер живёт, пока клиент не пришлёт {@code exit} или не закроет поток.
     * <p>
     * Ждать надо оба события сразу, и это не перестраховка. По спецификации процесс
     * заканчивается по {@code exit}, а поток ввода клиент при этом закрывать
     * не обязан — IDEA его и не закрывает. Ждать одного только конца потока значит
     * висеть после прощания, пока клиенту не надоест и он не убьёт процесс.
     */
    static int serve(InputStream in, OutputStream out, LanguageService service)
            throws Exception {
        WdlLanguageServer server = new WdlLanguageServer(service);
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());

        Future<Void> listening = launcher.startListening();
        CompletableFuture<Integer> stopped = server.stopped();
        Thread transport = new Thread(() -> {
            try {
                listening.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException failed) {
                System.err.println(WdlLanguageServer.NAME + ": обмен прерван — "
                        + failed.getCause());
            }
            // Поток кончился, а прощания не было: не наша ошибка, но и не успех.
            stopped.complete(1);
        }, "wdl-lsp-transport");
        transport.setDaemon(true);
        transport.start();
        return stopped.get();
    }

    private static void usage() {
        System.err.println("""
                wdl-lsp — языковой сервер wdl.

                  --stdio            обмен через стандартные потоки (по умолчанию)
                  --socket=<порт>    слушать порт и обслужить одного клиента
                  --version          напечатать имя и версию
                """);
    }
}
