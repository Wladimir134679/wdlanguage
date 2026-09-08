package ru.wds.wdl.idea.debug;

import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.PauseArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminateArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Разговор с адаптером: запросы туда, события обратно.
 * <p>
 * Всё, что знает плагин об отладке, проходит через этот класс, и правил отладки
 * здесь ровно ноль: адаптер запускает скрипт, останавливает потоки и считает кадры,
 * а здесь — вопросы и ответы.
 *
 * <h2>Запросы ждут, и ждать их можно не везде</h2>
 * Методы блокирующие: отладчик IDEA спрашивает кадры и переменные там, где ему удобно
 * получить их сразу, и раздавать наружу {@code CompletableFuture} значило бы разносить
 * ожидание по всему плагину. Отсюда правило для вызывающего: <b>ни один из них нельзя
 * звать из потока событий IDEA</b> — все они зовутся из пула
 * ({@code XDebugProcess} и узлы панели делают это сами).
 * <p>
 * У ожидания есть срок. Адаптер отвечает быстро, но он отдельный процесс: зависший
 * или убитый снаружи, он оставил бы отладчик висеть на {@code get()} без объяснений,
 * а окно IDEA — пустым. По сроку получается внятная ошибка в сеансе.
 */
final class DapSession {

    private static final Logger LOG = Logger.getInstance(DapSession.class);

    /** Сколько ждать ответа адаптера. */
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * Сколько ждать вычисления в кадре.
     * <p>
     * Дольше остальных: у самого вычисления есть свой срок на стороне движка
     * (пять секунд по умолчанию), и обрывать его раньше значило бы получать «нет
     * ответа» там, где ответ был бы «выражение не уложилось в срок».
     */
    private static final long EVALUATE_SECONDS = 60;

    /** Кому сессия рассказывает о событиях адаптера. */
    interface Events {

        /** Поток встал: причина, поток и текст ошибки, если встали на ней. */
        void stopped(StoppedEventArguments args);

        /** Печать скрипта или сообщение о его падении. */
        void output(OutputEventArguments args);

        /** Скрипт кончился: код возврата. */
        void exited(int code);

        /** Отлаживать больше нечего: адаптер закончил. */
        void terminated();
    }

    private final IDebugProtocolServer remote;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    DapSession(InputStream in, OutputStream out, Events events) {
        Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                new Client(events, ready), in, out);
        launcher.startListening();
        this.remote = launcher.getRemoteProxy();
    }

    /**
     * Начало сеанса: договориться, запустить, расставить точки, пустить скрипт.
     * <p>
     * Порядок задан протоколом и осмыслен: точки ставятся <b>между</b> запросом
     * {@code launch} и разрешением работать ({@code configurationDone}), иначе скрипт
     * успел бы проскочить точку на первой строке файла. Разрешение расставлять точки
     * приходит событием {@code initialized} — его-то и ждёт {@link #ready}.
     *
     * @param launch      что запускать: поля {@code program}, {@code projectRoot}, {@code args}
     * @param breakpoints точки по файлам: путь этой системы — строки, нумерация с единицы
     * @param stopOnError вставать ли на ошибке выполнения
     */
    void start(Map<String, Object> launch, Map<String, List<Integer>> breakpoints,
               boolean stopOnError) {
        InitializeRequestArguments initialize = new InitializeRequestArguments();
        initialize.setClientID("intellij");
        initialize.setClientName("IntelliJ IDEA");
        initialize.setAdapterID("wdl");
        // Договариваемся считать с единицы: так считает и сам язык (docs/tooling.md),
        // а перевод в нумерацию IDEA живёт одним местом — SourcePositions.
        initialize.setLinesStartAt1(true);
        initialize.setColumnsStartAt1(true);
        initialize.setPathFormat("path");
        initialize.setSupportsVariableType(true);
        await(remote.initialize(initialize), "initialize", TIMEOUT_SECONDS);
        await(remote.launch(launch), "launch", TIMEOUT_SECONDS);
        await(ready, "initialized", TIMEOUT_SECONDS);
        for (Map.Entry<String, List<Integer>> entry : breakpoints.entrySet()) {
            setBreakpoints(entry.getKey(), entry.getValue());
        }
        exceptionBreakpoints(stopOnError);
        await(remote.configurationDone(new ConfigurationDoneArguments()),
                "configurationDone", TIMEOUT_SECONDS);
    }

    /**
     * Ставит точки в одном файле — заменой всех прежних.
     *
     * @return что ответил адаптер: проверена ли каждая точка и на какой строке встала
     */
    List<Breakpoint> setBreakpoints(String path, List<Integer> lines) {
        Source source = new Source();
        source.setPath(path);
        SourceBreakpoint[] requested = new SourceBreakpoint[lines.size()];
        for (int index = 0; index < lines.size(); index++) {
            requested[index] = new SourceBreakpoint();
            requested[index].setLine(lines.get(index));
        }
        SetBreakpointsArguments args = new SetBreakpointsArguments();
        args.setSource(source);
        args.setBreakpoints(requested);
        return List.of(await(remote.setBreakpoints(args), "setBreakpoints", TIMEOUT_SECONDS)
                .getBreakpoints());
    }

    /** Вставать ли на ошибке выполнения: единственный фильтр, который есть у языка. */
    void exceptionBreakpoints(boolean stopOnError) {
        SetExceptionBreakpointsArguments args = new SetExceptionBreakpointsArguments();
        args.setFilters(stopOnError ? new String[]{"error"} : new String[0]);
        await(remote.setExceptionBreakpoints(args), "setExceptionBreakpoints", TIMEOUT_SECONDS);
    }

    List<org.eclipse.lsp4j.debug.Thread> threads() {
        return List.of(await(remote.threads(), "threads", TIMEOUT_SECONDS).getThreads());
    }

    List<StackFrame> stackTrace(int threadId) {
        StackTraceArguments args = new StackTraceArguments();
        args.setThreadId(threadId);
        return List.of(await(remote.stackTrace(args), "stackTrace", TIMEOUT_SECONDS)
                .getStackFrames());
    }

    List<Scope> scopes(int frameId) {
        ScopesArguments args = new ScopesArguments();
        args.setFrameId(frameId);
        return List.of(await(remote.scopes(args), "scopes", TIMEOUT_SECONDS).getScopes());
    }

    List<Variable> variables(int reference) {
        VariablesArguments args = new VariablesArguments();
        args.setVariablesReference(reference);
        return List.of(await(remote.variables(args), "variables", TIMEOUT_SECONDS).getVariables());
    }

    EvaluateResponse evaluate(int frameId, String expression, String context) {
        EvaluateArguments args = new EvaluateArguments();
        args.setFrameId(frameId);
        args.setExpression(expression);
        args.setContext(context);
        return await(remote.evaluate(args), "evaluate", EVALUATE_SECONDS);
    }

    void resume(int threadId) {
        ContinueArguments args = new ContinueArguments();
        args.setThreadId(threadId);
        await(remote.continue_(args), "continue", TIMEOUT_SECONDS);
    }

    void stepOver(int threadId) {
        NextArguments args = new NextArguments();
        args.setThreadId(threadId);
        await(remote.next(args), "next", TIMEOUT_SECONDS);
    }

    void stepInto(int threadId) {
        StepInArguments args = new StepInArguments();
        args.setThreadId(threadId);
        await(remote.stepIn(args), "stepIn", TIMEOUT_SECONDS);
    }

    void stepOut(int threadId) {
        StepOutArguments args = new StepOutArguments();
        args.setThreadId(threadId);
        await(remote.stepOut(args), "stepOut", TIMEOUT_SECONDS);
    }

    void pause(int threadId) {
        PauseArguments args = new PauseArguments();
        args.setThreadId(threadId);
        await(remote.pause(args), "pause", TIMEOUT_SECONDS);
    }

    /**
     * Прощание: прекратить скрипт и закрыть сеанс.
     * <p>
     * Неудача здесь не важна и потому не бросается наружу: адаптер мог уже умереть,
     * а «Стоп» обязан сработать и в этом случае — процесс всё равно будет убит
     * ({@link AdapterProcess#destroyProcessImpl}).
     */
    void disconnect() {
        try {
            remote.terminate(new TerminateArguments()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            LOG.debug("адаптер не ответил на terminate");
        }
        DisconnectArguments args = new DisconnectArguments();
        args.setTerminateDebuggee(true);
        try {
            remote.disconnect(args).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            LOG.debug("адаптер не ответил на disconnect");
        }
    }

    /**
     * Ждёт ответа и переводит неудачу в понятную человеку.
     * <p>
     * Отказ адаптера («поток не остановлен», «кадр устарел») — это <b>ответ</b>,
     * и его текст написан по-русски для того, чтобы дойти до окна отладчика, а не
     * до лога. Поэтому здесь он не проглатывается и не обрастает служебными словами.
     */
    private static <T> T await(CompletableFuture<T> answer, String request, long seconds) {
        try {
            return answer.get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new DapFailure("ожидание ответа на " + request + " прервано");
        } catch (TimeoutException slow) {
            throw new DapFailure("адаптер не ответил на " + request + " за " + seconds + " с");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ResponseErrorException refused) {
                throw new DapFailure(refused.getResponseError().getMessage());
            }
            throw new DapFailure(request + ": " + cause);
        }
    }

    /** Приёмник событий: переводит уведомления протокола в вызовы {@link Events}. */
    private static final class Client implements IDebugProtocolClient {

        private final Events events;
        private final CompletableFuture<Void> ready;

        private Client(Events events, CompletableFuture<Void> ready) {
            this.events = events;
            this.ready = ready;
        }

        @Override
        public void initialized() {
            ready.complete(null);
        }

        @Override
        public void stopped(StoppedEventArguments args) {
            events.stopped(args);
        }

        @Override
        public void output(OutputEventArguments args) {
            events.output(args);
        }

        @Override
        public void exited(ExitedEventArguments args) {
            events.exited(args.getExitCode());
        }

        @Override
        public void terminated(TerminatedEventArguments args) {
            events.terminated();
        }
    }
}
