package ru.wds.wdl.dap;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.BreakpointLocation;
import org.eclipse.lsp4j.debug.BreakpointLocationsArguments;
import org.eclipse.lsp4j.debug.BreakpointLocationsResponse;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinueResponse;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.ExceptionBreakMode;
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter;
import org.eclipse.lsp4j.debug.ExceptionDetails;
import org.eclipse.lsp4j.debug.ExceptionInfoArguments;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.PauseArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminateArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.VariablesResponse;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import ru.wds.wdl.api.WdlInstance;
import ru.wds.wdl.debug.Breakpoints;
import ru.wds.wdl.debug.DebugFrame;
import ru.wds.wdl.debug.DebugListener;
import ru.wds.wdl.debug.DebugSession;
import ru.wds.wdl.debug.StepMode;
import ru.wds.wdl.debug.StopReason;
import ru.wds.wdl.debug.SuspendPolicy;
import ru.wds.wdl.debug.SuspendedEvent;
import ru.wds.wdl.debug.ThreadInfo;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.tools.debug.BreakpointPlaces;
import ru.wds.wdl.value.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Адаптер отладки: перевод сессии {@link DebugSession} в Debug Adapter Protocol.
 * <p>
 * Правил отладки здесь нет ни одной строки — ровно как в {@code wdl-lsp} нет правил
 * языка. Останавливает потоки, считает кадры и вычисляет выражения движок; здесь только
 * перевод его понятий в понятия протокола, и почти вся работа — это перевод
 * <b>адресов</b>: у DAP это числа, у движка — поток, глубина кадра и само значение
 * ({@link References}), а точка останова живёт смещением против строки редактора
 * ({@link Sources}).
 *
 * <h2>Кто в каком потоке</h2>
 * Запросы приходят в потоке транспорта, события об остановке рождаются в потоке
 * скрипта — том самом, который в этот момент встал. Отсюда единственное правило,
 * которое здесь легко нарушить: <b>в {@link #suspended} и {@link #resumed} нельзя
 * ждать</b>. Их выполняет стоящий поток скрипта, и любое ожидание в них — это
 * ожидание отладчиком самого себя. Поэтому оба обработчика только отправляют
 * уведомление и возвращаются.
 *
 * <h2>Чего в переводе нет</h2>
 * Условных точек, точек по счёту попаданий, точек на функцию, изменения переменной
 * из панели, обратного шага. Всего этого нет не в протоколе, а в самом отладчике,
 * и объявлять поддержку раньше времени значило бы получать от клиента запросы,
 * на которые нечем ответить.
 */
public final class WdlDebugAdapter implements IDebugProtocolServer, DebugListener {

    /** Имя адаптера — оно же имя дистрибутива. */
    public static final String NAME = "wdl-dap";

    /** Единственный фильтр исключений, который у языка есть: останов на ошибке. */
    private static final String ERROR_FILTER = "error";

    /** Живой запуск для {@code attach}; {@code null} у отдельного процесса-адаптера. */
    private final WdlInstance live;

    private final Sources sources = new Sources();
    private final References references = new References();

    /** Смещения точек по файлам: то, что прислал клиент и что уходит в сессию. */
    private final Map<String, List<Integer>> points = new ConcurrentHashMap<>();

    /** Ошибка, на которой стоит поток, — для запроса {@code exceptionInfo}. */
    private final Map<Long, WdlRuntimeError> errors = new ConcurrentHashMap<>();

    private final AtomicInteger nextBreakpointId = new AtomicInteger(1);

    /** Сеанс кончился: клиент отключился или транспорт закрылся. Ждёт его {@link Main}. */
    private final CompletableFuture<Integer> finished = new CompletableFuture<>();

    /** Чем кончился скрипт; он же станет кодом возврата процесса-адаптера. */
    private volatile int exitCode;

    private volatile IDebugProtocolClient client;
    private volatile DebugTarget target;
    private volatile boolean stopOnError;

    /**
     * Нумерует ли клиент строки с единицы.
     * <p>
     * Спрашивается один раз, в {@code initialize}, и учитывается на каждой границе:
     * внутри всё считается с единицы, как {@code Position} и вся диагностика языка
     * ({@code docs/tooling.md}), а нумерация с нуля — дело адаптера, ровно как у LSP.
     */
    private volatile boolean linesStartAt1 = true;

    /** Адаптер отдельным процессом: запуск заведёт {@code launch}. */
    public WdlDebugAdapter() {
        this(null);
    }

    /**
     * Адаптер поверх живого запуска: {@code attach} подключится к нему.
     * <p>
     * Так отладку открывает приложение, в которое движок встроен: сервер, игра, мод.
     * Скрипт при этом уже считает — см. {@link AttachTarget}.
     */
    public WdlDebugAdapter(WdlInstance live) {
        this.live = live;
    }

    /** Кому отправлять события; ставится транспортом сразу после сборки. */
    public void connect(IDebugProtocolClient value) {
        this.client = value;
    }

    /**
     * Конец сеанса: выполняется, когда клиент отключился, — с кодом возврата скрипта.
     * <p>
     * Не тогда, когда скрипт кончился. Между «скрипт отработал» и «отладчик закрыт»
     * есть время, и оно принадлежит человеку: он читает вывод, смотрит последнюю
     * ошибку, листает консоль. Оборвать сеанс на выходе скрипта значило бы забрать
     * у него это окно.
     */
    public CompletableFuture<Integer> finished() {
        return finished;
    }

    /**
     * Конец сеанса не по просьбе клиента: транспорт закрылся.
     * <p>
     * Свой запуск при этом прекращается. Иначе процесс-адаптер, у которого редактор
     * закрыли, остался бы жить вместе со скриптом — и остался бы навсегда, если
     * скрипт бесконечный.
     */
    public void shutdown() {
        DebugTarget known = target;
        if (known != null) {
            known.terminate();
            known.close();
        }
        finished.complete(exitCode);
    }

    // --- начало и конец сеанса -----------------------------------------------

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        if (args != null && Boolean.FALSE.equals(args.getLinesStartAt1())) {
            linesStartAt1 = false;
        }
        Capabilities capabilities = new Capabilities();
        // Точки расставляются между launch и запуском скрипта — иначе точка на первой
        // строке файла не сработала бы никогда.
        capabilities.setSupportsConfigurationDoneRequest(true);
        // Куда точка встанет на самом деле: инструкция бывает ниже строки, по которой
        // щёлкнули (BreakpointPlaces).
        capabilities.setSupportsBreakpointLocationsRequest(true);
        capabilities.setSupportsTerminateRequest(true);
        capabilities.setSupportTerminateDebuggee(true);
        capabilities.setSupportsEvaluateForHovers(true);
        capabilities.setSupportsExceptionInfoRequest(true);
        ExceptionBreakpointsFilter onError = new ExceptionBreakpointsFilter();
        onError.setFilter(ERROR_FILTER);
        onError.setLabel("Ошибки выполнения");
        onError.setDescription("Встать там, где ошибка случилась, — до раскрутки. "
                + "Ошибка внутри try при этом тоже остановит поток.");
        // Выключено по умолчанию, и это не осторожность, а честность: ошибка внутри
        // try — обычное течение скрипта (docs/debugging.md).
        onError.setDefault_(false);
        capabilities.setExceptionBreakpointFilters(new ExceptionBreakpointsFilter[]{onError});
        return CompletableFuture.completedFuture(capabilities);
    }

    @Override
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        return reply(() -> {
            if (target != null) {
                throw new DapError("этот адаптер уже занят запуском");
            }
            LaunchOptions options = LaunchOptions.of(args, true);
            stopOnError = options.stopOnError();
            target = new LaunchTarget(options, this,
                    text -> send("stdout", text), text -> send("stderr", text), this::exited);
            ready();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> attach(Map<String, Object> args) {
        return reply(() -> {
            if (live == null) {
                throw new DapError("к этому адаптеру подключаться нечему: он запущен "
                        + "отдельным процессом и умеет только launch. Отладку живого "
                        + "запуска открывает само приложение — WdlDebugServer");
            }
            if (target != null) {
                throw new DapError("этот адаптер уже занят запуском");
            }
            LaunchOptions options = LaunchOptions.of(args, false);
            stopOnError = options.stopOnError();
            target = new AttachTarget(live, this, options);
            ready();
            return null;
        });
    }

    /**
     * Сообщает клиенту, что можно расставлять точки.
     * <p>
     * Событие уходит <b>после</b> того, как запуск заведён, а не сразу после
     * {@code initialize}: до этой минуты сессии ещё нет, и точку девать некуда.
     * Скрипт при этом не запущен — его пустит {@code configurationDone}.
     */
    private void ready() {
        IDebugProtocolClient known = client;
        if (known != null) {
            known.initialized();
        }
    }

    @Override
    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
        return reply(() -> {
            required().start();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> terminate(TerminateArguments args) {
        return reply(() -> {
            DebugTarget known = target;
            if (known != null) {
                known.terminate();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        return reply(() -> {
            DebugTarget known = target;
            if (known != null) {
                // 'terminateDebuggee' — просьба прекратить сам скрипт, а не только
                // отладку. Своё прекращается, чужое остаётся работать: это решает
                // сама цель (DebugTarget).
                if (args == null || !Boolean.FALSE.equals(args.getTerminateDebuggee())) {
                    known.terminate();
                }
                known.close();
            }
            finished.complete(exitCode);
            return null;
        });
    }

    /**
     * Скрипт кончился: код возврата, а следом — конец отлаживаемого.
     * <p>
     * Сеанс при этом не закрывается: закрыть его — дело клиента, и он сделает это
     * запросом {@code disconnect}, когда человек нажмёт «Стоп» или закроет окно.
     */
    private void exited(int code) {
        exitCode = code;
        IDebugProtocolClient known = client;
        if (known != null) {
            ExitedEventArguments exited = new ExitedEventArguments();
            exited.setExitCode(code);
            known.exited(exited);
            known.terminated(new TerminatedEventArguments());
        }
    }

    // --- точки останова ------------------------------------------------------

    @Override
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        return reply(() -> {
            String path = pathOf(args.getSource() == null ? null : args.getSource().getPath());
            List<Integer> lines = requestedLines(args);
            List<BreakpointPlaces.Place> places = sources.places(path, lines);
            List<Integer> offsets = new ArrayList<>(places.size());
            List<Breakpoint> answer = new ArrayList<>(places.size());
            for (int index = 0; index < places.size(); index++) {
                BreakpointPlaces.Place place = places.get(index);
                Breakpoint breakpoint = new Breakpoint();
                breakpoint.setId(nextBreakpointId.getAndIncrement());
                breakpoint.setSource(args.getSource());
                breakpoint.setVerified(place != null);
                if (place == null) {
                    breakpoint.setLine(clientLine(lines.get(index)));
                    breakpoint.setMessage("ниже этой строки инструкций нет: "
                            + "останавливаться не на чем");
                } else {
                    // Точка уехала вниз, к ближайшей инструкции, — и клиент обязан
                    // узнать, куда именно: он рисует её в редакторе.
                    breakpoint.setLine(clientLine(place.line()));
                    breakpoint.setColumn(place.column());
                    breakpoint.setOffset(place.offset());
                    offsets.add(place.offset());
                }
                answer.add(breakpoint);
            }
            if (offsets.isEmpty()) {
                points.remove(path);
            } else {
                points.put(path, List.copyOf(offsets));
            }
            applyBreakpoints();
            SetBreakpointsResponse response = new SetBreakpointsResponse();
            response.setBreakpoints(answer.toArray(new Breakpoint[0]));
            return response;
        });
    }

    /** Строки, которые прислал клиент: новым полем или устаревшим {@code lines}. */
    private List<Integer> requestedLines(SetBreakpointsArguments args) {
        List<Integer> lines = new ArrayList<>();
        SourceBreakpoint[] breakpoints = args.getBreakpoints();
        if (breakpoints != null) {
            for (SourceBreakpoint breakpoint : breakpoints) {
                lines.add(adapterLine(breakpoint.getLine()));
            }
            return lines;
        }
        int[] plain = args.getLines();
        if (plain != null) {
            for (int line : plain) {
                lines.add(adapterLine(line));
            }
        }
        return lines;
    }

    /**
     * Переносит точки в сессию.
     * <p>
     * Набор точек принадлежит адаптеру, а не сессии: клиент расставляет их до
     * {@code launch} и меняет во время работы. Поэтому истина живёт здесь, а в сессию
     * переносится целиком — файл за файлом, заменой набора; файл, из которого точки
     * убрали все, из сессии вычёркивается.
     */
    private void applyBreakpoints() {
        DebugTarget known = target;
        if (known == null) {
            return;
        }
        Breakpoints breakpoints = known.session().breakpoints();
        for (String file : breakpoints.files()) {
            if (!points.containsKey(file)) {
                breakpoints.set(file, List.of());
            }
        }
        for (Map.Entry<String, List<Integer>> entry : points.entrySet()) {
            breakpoints.set(entry.getKey(), entry.getValue());
        }
        known.session().stopOnError(stopOnError);
    }

    @Override
    public CompletableFuture<BreakpointLocationsResponse> breakpointLocations(
            BreakpointLocationsArguments args) {
        return reply(() -> {
            String path = pathOf(args.getSource() == null ? null : args.getSource().getPath());
            int from = adapterLine(args.getLine());
            int to = args.getEndLine() == null ? from : adapterLine(args.getEndLine());
            List<BreakpointLocation> found = new ArrayList<>();
            for (BreakpointPlaces.Place place : sources.all(path)) {
                if (place.line() >= from && place.line() <= to) {
                    BreakpointLocation location = new BreakpointLocation();
                    location.setLine(clientLine(place.line()));
                    location.setColumn(place.column());
                    found.add(location);
                }
            }
            BreakpointLocationsResponse response = new BreakpointLocationsResponse();
            response.setBreakpoints(found.toArray(new BreakpointLocation[0]));
            return response;
        });
    }

    @Override
    public CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(
            SetExceptionBreakpointsArguments args) {
        return reply(() -> {
            boolean requested = false;
            if (args != null && args.getFilters() != null) {
                for (String filter : args.getFilters()) {
                    requested |= ERROR_FILTER.equals(filter);
                }
            }
            stopOnError = requested;
            DebugTarget known = target;
            if (known != null) {
                known.session().stopOnError(requested);
            }
            return new SetExceptionBreakpointsResponse();
        });
    }

    // --- потоки, кадры, переменные -------------------------------------------

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        return reply(() -> {
            List<org.eclipse.lsp4j.debug.Thread> found = new ArrayList<>();
            DebugTarget known = target;
            if (known != null) {
                for (ThreadInfo info : known.session().threads()) {
                    org.eclipse.lsp4j.debug.Thread thread = new org.eclipse.lsp4j.debug.Thread();
                    thread.setId(references.threadNumber(info.id()));
                    thread.setName(info.name());
                    found.add(thread);
                }
            }
            ThreadsResponse response = new ThreadsResponse();
            response.setThreads(found.toArray(new org.eclipse.lsp4j.debug.Thread[0]));
            return response;
        });
    }

    @Override
    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        return reply(() -> {
            long threadId = references.threadOf(args.getThreadId());
            List<DebugFrame> frames = required().session().frames(threadId);
            int from = args.getStartFrame() == null ? 0 : Math.max(args.getStartFrame(), 0);
            int limit = args.getLevels() == null || args.getLevels() <= 0
                    ? frames.size() : args.getLevels();
            List<StackFrame> answer = new ArrayList<>();
            for (int index = from; index < frames.size() && answer.size() < limit; index++) {
                answer.add(frameOf(threadId, frames.get(index)));
            }
            StackTraceResponse response = new StackTraceResponse();
            response.setStackFrames(answer.toArray(new StackFrame[0]));
            response.setTotalFrames(frames.size());
            return response;
        });
    }

    private StackFrame frameOf(long threadId, DebugFrame frame) {
        StackFrame described = new StackFrame();
        described.setId(references.frame(threadId, frame.depth()));
        // На верхнем уровне файла кадр назван самим файлом, и назван полным путём:
        // в трассировке ошибки это правильно, а в панели кадров — строка во всю
        // ширину окна. Там, где путь и так виден в колонке файла, хватит имени.
        described.setName(frame.function().equals(frame.file())
                ? nameOf(frame.file()) : frame.function());
        described.setLine(clientLine(frame.line()));
        described.setColumn(frame.column());
        if (!frame.file().isEmpty()) {
            org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
            source.setPath(frame.file());
            source.setName(nameOf(frame.file()));
            described.setSource(source);
        }
        return described;
    }

    @Override
    public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        return reply(() -> {
            References.Frame frame = references.frameOf(args.getFrameId());
            Scope locals = new Scope();
            locals.setName("Локальные");
            locals.setPresentationHint("locals");
            locals.setVariablesReference(references.node(
                    new References.Locals(frame.threadId(), frame.depth())));
            locals.setExpensive(false);
            Scope visible = new Scope();
            visible.setName("Видимые");
            visible.setVariablesReference(references.node(
                    new References.Visible(frame.threadId(), frame.depth())));
            // Дорогая: внешние области доходят до корневой, где лежит вся библиотека.
            visible.setExpensive(true);
            ScopesResponse response = new ScopesResponse();
            response.setScopes(new Scope[]{locals, visible});
            return response;
        });
    }

    @Override
    public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        return reply(() -> {
            List<Variable> found = switch (references.nodeOf(args.getVariablesReference())) {
                case References.Locals locals -> variablesOf(locals.threadId(),
                        frame(locals.threadId(), locals.depth()).locals());
                case References.Visible visible -> outerOf(visible.threadId(), visible.depth());
                case References.Children children -> {
                    List<Variable> variables = new ArrayList<>();
                    for (ValueView.Child child : ValueView.children(children.value())) {
                        variables.add(variableOf(children.threadId(), child.name(), child.value()));
                    }
                    yield variables;
                }
            };
            VariablesResponse response = new VariablesResponse();
            response.setVariables(found.toArray(new Variable[0]));
            return response;
        });
    }

    /** Имена кадра со значениями — готовый ответ панели. */
    private List<Variable> variablesOf(long threadId, Map<String, Value> values) {
        List<Variable> variables = new ArrayList<>(values.size());
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            variables.add(variableOf(threadId, entry.getKey(), entry.getValue()));
        }
        return variables;
    }

    /**
     * Имена, видимые кадру снаружи: всё, что он видит, минус его собственное.
     * <p>
     * Вычитание — не экономия, а разделение: одно и то же имя в двух панелях читалось
     * бы как два разных, а какое из них настоящее, панель не показывает.
     */
    private List<Variable> outerOf(long threadId, int depth) {
        DebugFrame frame = frame(threadId, depth);
        Set<String> outer = new LinkedHashSet<>(frame.names());
        outer.removeAll(frame.namesHere());
        List<Variable> variables = new ArrayList<>(outer.size());
        for (String name : outer) {
            Value value = frame.value(name);
            if (value != null) {
                variables.add(variableOf(threadId, name, value));
            }
        }
        return variables;
    }

    private Variable variableOf(long threadId, String name, Value value) {
        Variable variable = new Variable();
        variable.setName(name);
        variable.setValue(ValueView.summary(value));
        variable.setType(ValueView.type(value));
        variable.setEvaluateName(name);
        int children = ValueView.childCount(value);
        if (children > 0) {
            variable.setVariablesReference(
                    references.node(new References.Children(threadId, value)));
            if (ValueView.indexed(value)) {
                variable.setIndexedVariables(children);
            } else {
                variable.setNamedVariables(children);
            }
        } else {
            // Ноль — «раскрывать нечего»: так это читает клиент.
            variable.setVariablesReference(0);
        }
        return variable;
    }

    /** Кадр указанной глубины у остановленного потока. */
    private DebugFrame frame(long threadId, int depth) {
        for (DebugFrame frame : required().session().frames(threadId)) {
            if (frame.depth() == depth) {
                return frame;
            }
        }
        throw new DapError("кадра глубины " + depth + " у потока больше нет");
    }

    // --- вычисление ----------------------------------------------------------

    @Override
    public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        return reply(() -> {
            References.Frame frame = frameFor(args.getFrameId());
            Value value = required().session()
                    .evaluate(frame.threadId(), frame.depth(), args.getExpression());
            EvaluateResponse response = new EvaluateResponse();
            response.setResult(ValueView.summary(value));
            response.setType(ValueView.type(value));
            response.setVariablesReference(ValueView.childCount(value) > 0
                    ? references.node(new References.Children(frame.threadId(), value))
                    : 0);
            return response;
        });
    }

    /**
     * Кадр, в котором считать выражение.
     * <p>
     * Без кадра вычислять нечего, и это не придирка: выражение считает <b>сам
     * остановленный поток</b> в своей цепочке кадров ({@code DebugSession.evaluate}),
     * а «вычислить вообще» означало бы выполнить его в чужом потоке — с чужими
     * счётчиками и мимо замков, которые держит остановленный.
     */
    private References.Frame frameFor(Integer frameId) {
        if (frameId != null) {
            return references.frameOf(frameId);
        }
        for (ThreadInfo info : required().session().threads()) {
            if (info.suspended()) {
                return new References.Frame(info.id(), topDepthOf(info.id()));
            }
        }
        throw new DapError("вычислять негде: ни один поток не остановлен");
    }

    private int topDepthOf(long threadId) {
        List<DebugFrame> frames = required().session().frames(threadId);
        if (frames.isEmpty()) {
            throw new DapError("у остановленного потока нет кадров");
        }
        return frames.get(0).depth();
    }

    @Override
    public CompletableFuture<ExceptionInfoResponse> exceptionInfo(ExceptionInfoArguments args) {
        return reply(() -> {
            long threadId = references.threadOf(args.getThreadId());
            WdlRuntimeError error = errors.get(threadId);
            if (error == null) {
                throw new DapError("этот поток стоит не на ошибке");
            }
            ExceptionInfoResponse response = new ExceptionInfoResponse();
            response.setExceptionId(error.kindName());
            response.setDescription(error.getMessage());
            response.setBreakMode(ExceptionBreakMode.ALWAYS);
            ExceptionDetails details = new ExceptionDetails();
            details.setMessage(error.getMessage());
            details.setTypeName(error.kindName());
            details.setStackTrace(String.join(System.lineSeparator(), error.trace()));
            response.setDetails(details);
            return response;
        });
    }

    // --- движение ------------------------------------------------------------

    @Override
    public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
        return reply(() -> {
            long threadId = references.threadOf(args.getThreadId());
            required().session().resume(threadId);
            ContinueResponse response = new ContinueResponse();
            // Под политикой «все» возобновление одного отпускает всех, и клиент обязан
            // это знать: иначе он оставит соседние потоки нарисованными стоящими.
            response.setAllThreadsContinued(policy() == SuspendPolicy.ALL);
            return response;
        });
    }

    @Override
    public CompletableFuture<Void> next(NextArguments args) {
        return step(args.getThreadId(), StepMode.OVER);
    }

    @Override
    public CompletableFuture<Void> stepIn(StepInArguments args) {
        return step(args.getThreadId(), StepMode.INTO);
    }

    @Override
    public CompletableFuture<Void> stepOut(StepOutArguments args) {
        return step(args.getThreadId(), StepMode.OUT);
    }

    private CompletableFuture<Void> step(int threadNumber, StepMode mode) {
        return reply(() -> {
            required().session().step(references.threadOf(threadNumber), mode);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> pause(PauseArguments args) {
        return reply(() -> {
            DebugSession session = required().session();
            if (policy() == SuspendPolicy.ALL) {
                // Просьба стоять — всем: остановить один поток, пока остальные меняют
                // общее состояние, значит показать человеку картинку, которой уже нет.
                session.pause();
            } else {
                session.pause(references.threadOf(args.getThreadId()));
            }
            return null;
        });
    }

    // --- события сессии ------------------------------------------------------

    /**
     * Поток встал.
     * <p>
     * Зовётся <b>стоящим потоком скрипта</b>, поэтому здесь только отправка события:
     * кадры клиент спросит сам и спросит у сессии, пока поток стоит.
     */
    @Override
    public void suspended(SuspendedEvent event) {
        if (event.reason() == StopReason.ERROR && event.error() != null) {
            errors.put(event.thread().id(), event.error());
        } else {
            errors.remove(event.thread().id());
        }
        IDebugProtocolClient known = client;
        if (known == null) {
            return;
        }
        StoppedEventArguments stopped = new StoppedEventArguments();
        stopped.setThreadId(references.threadNumber(event.thread().id()));
        stopped.setReason(reasonOf(event.reason()));
        stopped.setDescription(event.reason().title());
        stopped.setAllThreadsStopped(policy() == SuspendPolicy.ALL);
        if (event.error() != null) {
            stopped.setText(event.error().getMessage());
        }
        known.stopped(stopped);
    }

    @Override
    public void resumed(long threadId) {
        // Ссылки этого потока больше ничего не адресуют: кадр после шага — другой кадр.
        references.invalidate(threadId);
        errors.remove(threadId);
        IDebugProtocolClient known = client;
        if (known == null) {
            return;
        }
        ContinuedEventArguments continued = new ContinuedEventArguments();
        continued.setThreadId(references.threadNumber(threadId));
        continued.setAllThreadsContinued(policy() == SuspendPolicy.ALL);
        known.continued(continued);
    }

    /** Причина остановки словом протокола. */
    private static String reasonOf(StopReason reason) {
        return switch (reason) {
            case BREAKPOINT -> "breakpoint";
            case STEP -> "step";
            case PAUSE -> "pause";
            // У языка нет исключений отдельно от ошибок, а у протокола нет «ошибки»
            // отдельно от исключения: слово одно на оба понятия.
            case ERROR -> "exception";
        };
    }

    // --- вывод скрипта -------------------------------------------------------

    /**
     * Печать скрипта уходит клиенту событием, а не в консоль процесса.
     * <p>
     * В stdio-режиме иначе и нельзя: в стандартном выводе живёт сам протокол, и одна
     * строка {@code println} из скрипта сломала бы поток сообщений. Но дело не только
     * в транспорте — вывод скрипта принадлежит сеансу отладки, и показывать его надо
     * в том же окне, где кадры и переменные.
     */
    private void send(String category, String text) {
        IDebugProtocolClient known = client;
        if (known == null || text.isEmpty()) {
            return;
        }
        OutputEventArguments output = new OutputEventArguments();
        output.setCategory(category);
        output.setOutput(text);
        known.output(output);
    }

    // --- мелочи --------------------------------------------------------------

    private DebugTarget required() {
        DebugTarget known = target;
        if (known == null) {
            throw new DapError("сеанс не начат: не было ни launch, ни attach");
        }
        return known;
    }

    private SuspendPolicy policy() {
        DebugTarget known = target;
        return known == null ? SuspendPolicy.ALL : known.session().policy();
    }

    private static String pathOf(String path) {
        if (path == null || path.isBlank()) {
            throw new DapError("в запросе нет пути к файлу");
        }
        return Sources.canonical(path);
    }

    private static String nameOf(String path) {
        Path name = Path.of(path).getFileName();
        return name == null ? path : name.toString();
    }

    /** Строка внутрь адаптера: у нас нумерация с единицы всегда. */
    private int adapterLine(int line) {
        return linesStartAt1 ? line : line + 1;
    }

    /** Строка клиенту: так, как считает он. */
    private int clientLine(int line) {
        return linesStartAt1 ? line : line - 1;
    }

    /**
     * Ответ на запрос, а неудача — ответом об ошибке, а не аварией транспорта.
     * <p>
     * Разница видна человеку: отказ с текстом «поток не остановлен» отладчик покажет
     * в окне, а исключение, вылетевшее в транспорт, оборвало бы сеанс без объяснений.
     */
    private static <T> CompletableFuture<T> reply(Callable<T> body) {
        try {
            return CompletableFuture.completedFuture(body.call());
        } catch (DapError refused) {
            return failure(ResponseErrorCode.InvalidParams, refused.getMessage());
        } catch (RuntimeException failed) {
            String message = failed.getMessage();
            return failure(ResponseErrorCode.RequestFailed,
                    message == null || message.isBlank() ? String.valueOf(failed) : message);
        } catch (Exception broken) {
            return failure(ResponseErrorCode.InternalError, String.valueOf(broken));
        }
    }

    private static <T> CompletableFuture<T> failure(ResponseErrorCode code, String message) {
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(new ResponseErrorException(
                new ResponseError(code, message, null)));
        return failed;
    }
}
