package ru.wds.wdl.idea.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Процесс адаптера как процесс сеанса отладки.
 * <p>
 * Своя реализация {@link ProcessHandler}, а не {@code OSProcessHandler}, ровно
 * по одной причине: <b>стандартный вывод адаптера — это протокол</b>, и обычный
 * обработчик вычитал бы его в консоль, оставив отладчику пустой поток. Здесь
 * стандартный вывод не читает никто, кроме транспорта, а в консоль идут два других
 * источника: поток ошибок адаптера (его собственная диагностика) и печать самого
 * скрипта, которая приходит событиями {@code output} и печатается
 * через {@link #print}.
 *
 * <h2>Напечатанное до консоли не теряется</h2>
 * {@code notifyTextAvailable} раздаёт строку слушателям <b>сразу</b> и ничего
 * не копит: напечатанное раньше, чем консоль подключилась, исчезло бы навсегда.
 * А раньше она подключается почти всегда — процесс адаптера заводится в
 * {@code WdlDebugRunner} до сеанса, и первые его строки (не найден JDK, не читается
 * скрипт) появляются прежде, чем платформа спросит {@code createConsole()}. Поэтому
 * до {@link #consoleAttached()} строки копятся, а по нему уходят в консоль в том же
 * порядке.
 */
final class AdapterProcess extends ProcessHandler {

    private static final Logger LOG = Logger.getInstance(AdapterProcess.class);

    /** Строка, напечатанная до подключения консоли: текст и его вид. */
    private record Line(String text, Key<?> kind) {
    }

    private final Process process;

    /** Что напечатано до консоли; после подключения не используется. Под своим замком. */
    private final List<Line> pending = new ArrayList<>();

    private volatile boolean attached;

    AdapterProcess(GeneralCommandLine command) throws ExecutionException {
        this.process = command.createProcess();
        pump(process.getErrorStream());
        Thread waiting = new Thread(this::await, "wdl-dap-exit");
        waiting.setDaemon(true);
        waiting.start();
    }

    /** Поток, из которого читает транспорт: в нём живут сообщения протокола. */
    InputStream protocolIn() {
        return process.getInputStream();
    }

    /** Поток, в который пишет транспорт. */
    OutputStream protocolOut() {
        return process.getOutputStream();
    }

    /** Строка в консоль сеанса: вывод скрипта или сообщение о его падении. */
    void print(String text, Key<?> kind) {
        if (!attached) {
            synchronized (pending) {
                if (!attached) {
                    pending.add(new Line(text, kind));
                    return;
                }
            }
        }
        notifyTextAvailable(text, kind);
    }

    /**
     * Консоль подключена: копившееся уходит в неё, дальше печатается напрямую.
     * <p>
     * Зовётся из {@code WdlDebugProcess.createConsole()} сразу после
     * {@code attachToProcess}, и только оттуда: раньше отдавать строки некому,
     * а позже — значит показать их не в том порядке, в котором они пришли.
     */
    void consoleAttached() {
        List<Line> waiting;
        synchronized (pending) {
            attached = true;
            waiting = List.copyOf(pending);
            pending.clear();
        }
        for (Line line : waiting) {
            notifyTextAvailable(line.text(), line.kind());
        }
    }

    private void pump(InputStream errors) {
        Thread reading = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(errors, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    print(line + System.lineSeparator(), ProcessOutputTypes.STDERR);
                }
            } catch (IOException closed) {
                LOG.debug("поток ошибок адаптера закрыт", closed);
            }
        }, "wdl-dap-stderr");
        reading.setDaemon(true);
        reading.start();
    }

    private void await() {
        try {
            notifyProcessTerminated(process.waitFor());
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            notifyProcessTerminated(1);
        }
    }

    /**
     * Кнопка «Стоп» доводит дело до конца сама.
     * <p>
     * Вежливое прощание — дело отладчика: он посылает {@code disconnect} и ждёт ответа
     * ({@code WdlDebugProcess.stop}). Сюда доходит уже последний довод, и он обязан
     * работать даже с адаптером, который завис: {@code destroyForcibly} вместе
     * с деревом процессов, потому что запускается адаптер стартовым скриптом,
     * а скрипт — новой JVM.
     */
    @Override
    protected void destroyProcessImpl() {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
    }

    @Override
    protected void detachProcessImpl() {
        // Отладчик отсоединился, а процесс наш: оставлять его жить незачем.
        destroyProcessImpl();
    }

    @Override
    public boolean detachIsDefault() {
        return false;
    }

    /**
     * Ввода у сеанса нет: единственный поток ввода процесса занят протоколом, и отдать
     * его консоли значило бы сломать обмен первой же набранной строкой.
     */
    @Override
    public @Nullable OutputStream getProcessInput() {
        return null;
    }

    @Override
    public @NotNull String toString() {
        return "wdl-dap[pid=" + process.pid() + "]";
    }
}
