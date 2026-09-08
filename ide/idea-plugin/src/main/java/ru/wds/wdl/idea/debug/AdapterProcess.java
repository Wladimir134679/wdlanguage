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
 */
final class AdapterProcess extends ProcessHandler {

    private static final Logger LOG = Logger.getInstance(AdapterProcess.class);

    private final Process process;

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
        notifyTextAvailable(text, kind);
    }

    private void pump(InputStream errors) {
        Thread reading = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(errors, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    notifyTextAvailable(line + System.lineSeparator(), ProcessOutputTypes.STDERR);
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
