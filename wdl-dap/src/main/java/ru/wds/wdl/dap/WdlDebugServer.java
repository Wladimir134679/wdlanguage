package ru.wds.wdl.dap;

import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import ru.wds.wdl.api.WdlInstance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Транспорт адаптера: один сеанс отладки поверх пары потоков или сокета.
 *
 * <h2>Зачем это публично</h2>
 * Ради {@code attach}. Отладка из редактора запускает адаптер отдельным процессом
 * ({@link Main}), и для скрипта, который запускают <i>сейчас</i>, этого хватает.
 * Но самое интересное происходит с запуском, который уже работает внутри чужого
 * приложения: правила игры, обработчики окна, мод на сервере. Такой запуск
 * перезапускать «под отладчиком» бессмысленно, и подключиться к нему может только
 * само приложение — одной строкой:
 *
 * <pre>{@code
 * try (WdlInstance script = engine.compile(file).instance()) {
 *     WdlDebugServer.listen(5005, script);   // ждёт отладчик, не мешая скрипту
 *     script.execute();
 * }
 * }</pre>
 *
 * Порт слушается в отдельном потоке-демоне: приложение не должно ждать, придёт ли
 * кто-нибудь отлаживать. Пока никто не подключился, запуск не платит за отладку
 * вовсе — сессию заводит {@link WdlInstance#debugger()} по первому спросу, и спросит
 * его только пришедший клиент.
 */
public final class WdlDebugServer {

    private WdlDebugServer() {
    }

    /**
     * Один сеанс по готовым потокам: адаптер живёт, пока клиент не отключится
     * или пока поток не кончится.
     *
     * @return код возврата скрипта
     */
    public static int serve(InputStream in, OutputStream out, WdlDebugAdapter adapter)
            throws InterruptedException, ExecutionException {
        Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(adapter, in, out);
        adapter.connect(launcher.getRemoteProxy());
        Future<Void> listening = launcher.startListening();
        CompletableFuture<Integer> finished = adapter.finished();
        // Ждать надо оба события сразу. По протоколу сеанс кончается запросом
        // 'disconnect', но клиент вправе просто умереть — и тогда конец приходит
        // только концом потока. Ждать одного 'disconnect' значило бы висеть с живым
        // скриптом после того, как редактор закрыли.
        Thread transport = new Thread(() -> {
            try {
                listening.get();
            } catch (CancellationException ours) {
                // Обмен прекратили мы сами, дождавшись 'disconnect': это конец сеанса,
                // а не поломка, и стек в поток ошибок ронять незачем — в отладчике
                // редактора он выглядел бы аварией на каждой кнопке «Стоп».
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException failed) {
                System.err.println(WdlDebugAdapter.NAME + ": обмен прерван — "
                        + failed.getCause());
            }
            adapter.shutdown();
        }, "wdl-dap-transport");
        transport.setDaemon(true);
        transport.start();
        int code = finished.get();
        listening.cancel(true);
        return code;
    }

    /**
     * Слушает порт и обслуживает одного клиента: сеанс {@code launch} для редактора,
     * которому удобнее сокет, чем стандартные потоки.
     *
     * @return код возврата скрипта
     */
    public static int serve(int port) throws IOException, InterruptedException, ExecutionException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.err.println(WdlDebugAdapter.NAME + ": жду отладчик на порту "
                    + server.getLocalPort());
            try (Socket client = server.accept()) {
                return serve(client.getInputStream(), client.getOutputStream(),
                        new WdlDebugAdapter());
            }
        }
    }

    /**
     * Открывает отладку живого запуска на порту и возвращает управление немедленно.
     * <p>
     * Это режим {@code attach} со стороны приложения. Поток-слушатель — демон:
     * приложение, которое никто не пришёл отлаживать, обязано закрыться само.
     * Отключение отладчика запуск не трогает — это записано в {@link AttachTarget}.
     *
     * @param port   порт; {@code 0} — любой свободный, номер напечатается в stderr
     * @param script запуск, к которому подключится пришедший отладчик
     * @return сокет слушателя — чтобы приложение могло закрыть его вместе с собой
     */
    public static ServerSocket listen(int port, WdlInstance script) throws IOException {
        ServerSocket server = new ServerSocket(port);
        System.err.println(WdlDebugAdapter.NAME + ": отладка открыта на порту "
                + server.getLocalPort());
        Thread waiting = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    serve(client.getInputStream(), client.getOutputStream(),
                            new WdlDebugAdapter(script));
                } catch (IOException closed) {
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException failed) {
                    System.err.println(WdlDebugAdapter.NAME + ": сеанс прерван — "
                            + failed.getCause());
                }
                // Клиент ушёл — ждём следующего: отладчик вправе подключиться
                // к тому же приложению второй раз.
            }
        }, "wdl-dap-listener");
        waiting.setDaemon(true);
        waiting.start();
        return server;
    }
}
