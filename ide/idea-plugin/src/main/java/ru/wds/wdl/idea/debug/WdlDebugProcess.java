package ru.wds.wdl.idea.debug;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Key;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Сеанс отладки со стороны IDEA: кнопки шага, панели кадров и переменных, консоль.
 * <p>
 * Отладкой занимается адаптер, а этот класс — переводчик между ним и платформой.
 * Правило у переводчика одно и жёсткое: <b>ни один запрос к адаптеру не делается
 * в потоке событий</b>. Отвечает на них остановленный поток скрипта, ответ приходит
 * через отдельный процесс, и ожидание в потоке интерфейса — это замерзшая IDEA.
 * Поэтому всё, что спрашивает, уходит в пул ({@link #ask}).
 *
 * <h2>Порядок начала</h2>
 * Платформа сначала заводит точки останова, потом зовёт {@link #sessionInitialized()}.
 * Разговор с адаптером начинается там, а не в конструкторе, ровно поэтому: точки,
 * поставленные до запуска, обязаны уехать адаптеру <b>до</b> того, как скрипт сделает
 * первый шаг, — иначе точка на первой строке файла не сработает никогда.
 */
public final class WdlDebugProcess extends XDebugProcess {

    private final AdapterProcess process;
    private final DapSession dap;
    private final WdlBreakpoints lines;
    private final ErrorBreakpoints errors;
    private final Map<String, Object> launch;
    private final XDebuggerEditorsProvider editors = new WdlEditorsProvider();

    /** Поток, в котором мы стоим: им адресуются шаг и возобновление. */
    private volatile int currentThread;

    private volatile boolean stopping;

    /** Договорились ли уже с адаптером: до этой минуты спрашивать его нечего. */
    private volatile boolean ready;

    WdlDebugProcess(@NotNull XDebugSession session, AdapterProcess process,
                    Map<String, Object> launch) {
        super(session);
        this.process = process;
        this.launch = launch;
        this.lines = new WdlBreakpoints(session);
        this.errors = new ErrorBreakpoints();
        this.dap = new DapSession(process.protocolIn(), process.protocolOut(), new Listener());
    }

    @Override
    public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return editors;
    }

    @Override
    protected @Nullable ProcessHandler doGetProcessHandler() {
        return process;
    }

    @Override
    public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
        return new XBreakpointHandler<?>[]{lines, errors};
    }

    /** Начало разговора: договориться, запустить, расставить точки, пустить скрипт. */
    @Override
    public void sessionInitialized() {
        ask("запуск", () -> {
            dap.start(launch, lines.snapshot(), errors.enabled);
            ready = true;
            lines.ready(dap);
        });
    }

    // --- движение ------------------------------------------------------------

    @Override
    public void resume(@Nullable XSuspendContext context) {
        int thread = threadOf(context);
        ask("продолжение", () -> dap.resume(thread));
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        int thread = threadOf(context);
        ask("шаг", () -> dap.stepOver(thread));
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        int thread = threadOf(context);
        ask("шаг внутрь", () -> dap.stepInto(thread));
    }

    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        int thread = threadOf(context);
        ask("шаг наружу", () -> dap.stepOut(thread));
    }

    @Override
    public void startPausing() {
        int thread = currentThread;
        ask("пауза", () -> dap.pause(thread));
    }

    /**
     * «Выполнить до курсора» — временной точкой останова.
     * <p>
     * Своего запроса для этого в протоколе нет, а точка — есть, и снимается она
     * первой же остановкой ({@link Listener#stopped}). Строка при этом уедет вниз
     * к ближайшей инструкции: щелчок мимо инструкции значит «примерно сюда» и здесь.
     */
    @Override
    public void runToPosition(@NotNull XSourcePosition position, @Nullable XSuspendContext context) {
        int thread = threadOf(context);
        String file = SourcePositions.protocolPath(position.getFile());
        int line = SourcePositions.protocolLine(position.getLine());
        ask("выполнение до курсора", () -> {
            lines.runTo(file, line);
            dap.resume(thread);
        });
    }

    /**
     * Конец сеанса: попрощаться с адаптером и дать процессу закрыться.
     * <p>
     * Прощание — не вежливость: {@code terminate} прекращает скрипт по-настоящему,
     * отпуская стоящие потоки и давая отработать {@code defer}. Убить процесс мы
     * всегда успеем — это делает {@link AdapterProcess} следом.
     */
    @Override
    public void stop() {
        stopping = true;
        ask("остановка", dap::disconnect);
    }

    private int threadOf(@Nullable XSuspendContext context) {
        if (context instanceof WdlSuspendContext known) {
            return known.activeThread();
        }
        return currentThread;
    }

    /**
     * Спросить адаптер в пуле и показать отказ человеку.
     * <p>
     * Отказ («поток не остановлен», «кадр устарел») — это ответ, и место ему в окне
     * сеанса, а не в логе: он объясняет, почему кнопка не сработала.
     */
    private void ask(String what, Runnable request) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                request.run();
            } catch (DapFailure failed) {
                if (!stopping) {
                    getSession().reportMessage(what + ": " + failed.getMessage(), MessageType.ERROR);
                }
            }
        });
    }

    // --- события адаптера ----------------------------------------------------

    /** Что приходит от адаптера, пока сеанс жив. */
    private final class Listener implements DapSession.Events {

        @Override
        public void stopped(StoppedEventArguments args) {
            int thread = args.getThreadId() == null ? currentThread : args.getThreadId();
            currentThread = thread;
            for (String file : lines.dropTemporary()) {
                lines.send(file);
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> show(args, thread));
        }

        /**
         * Собирает картинку остановки и отдаёт её платформе.
         * <p>
         * Кадры спрашиваются <b>сразу</b>, пока поток заведомо стоит: между «встал»
         * и «покажи кадры» его мог отпустить кто угодно, включая соседнее окно IDEA.
         */
        private void show(StoppedEventArguments args, int thread) {
            try {
                List<StackFrame> frames = dap.stackTrace(thread);
                WdlSuspendContext context = new WdlSuspendContext(dap, dap.threads(), thread, frames);
                XLineBreakpoint<XBreakpointProperties> hit = hitOf(frames);
                if (hit != null) {
                    getSession().breakpointReached(hit, args.getText(), context);
                    return;
                }
                if (args.getText() != null && !args.getText().isBlank()) {
                    // Встали на ошибке: её текст — главное, что человек хочет увидеть,
                    // и в панели кадров его нет.
                    process.print(args.getText() + System.lineSeparator(),
                            ProcessOutputTypes.STDERR);
                }
                getSession().positionReached(context);
            } catch (DapFailure failed) {
                getSession().reportMessage("Не удалось показать остановку: "
                        + failed.getMessage(), MessageType.ERROR);
            }
        }

        /** Точка человека, на которой встали, — или {@code null}, если встали не на ней. */
        private @Nullable XLineBreakpoint<XBreakpointProperties> hitOf(List<StackFrame> frames) {
            if (frames.isEmpty() || frames.get(0).getSource() == null) {
                return null;
            }
            StackFrame top = frames.get(0);
            return lines.at(top.getSource().getPath(), top.getLine());
        }

        @Override
        public void output(OutputEventArguments args) {
            if (args.getOutput() == null) {
                return;
            }
            Key<?> kind = "stderr".equals(args.getCategory())
                    ? ProcessOutputTypes.STDERR : ProcessOutputTypes.STDOUT;
            process.print(args.getOutput(), kind);
        }

        @Override
        public void exited(int code) {
            process.print(System.lineSeparator() + "Скрипт завершился с кодом " + code
                    + System.lineSeparator(), ProcessOutputTypes.SYSTEM);
        }

        @Override
        public void terminated() {
            // Отлаживать больше нечего. Сеанс закрывает платформа — она же позовёт
            // stop() и уберёт процесс.
            getSession().stop();
        }
    }

    /**
     * Флажок «Ошибки выполнения wdl»: включён — адаптер встаёт на ошибке.
     * <p>
     * Точка без места, поэтому и обработчик у неё простой: он не помнит ничего, кроме
     * «включено или нет», и сообщает это адаптеру.
     */
    private final class ErrorBreakpoints
            extends XBreakpointHandler<XBreakpoint<XBreakpointProperties>> {

        private volatile boolean enabled;

        private ErrorBreakpoints() {
            super(WdlErrorBreakpointType.class);
        }

        @Override
        public void registerBreakpoint(@NotNull XBreakpoint<XBreakpointProperties> breakpoint) {
            set(true);
        }

        @Override
        public void unregisterBreakpoint(@NotNull XBreakpoint<XBreakpointProperties> breakpoint,
                                         boolean temporary) {
            set(false);
        }

        private void set(boolean value) {
            enabled = value;
            if (!ready) {
                // Сеанс ещё не начат: значение уедет адаптеру вместе с запуском.
                return;
            }
            ask("останов на ошибке", () -> dap.exceptionBreakpoints(value));
        }
    }
}
