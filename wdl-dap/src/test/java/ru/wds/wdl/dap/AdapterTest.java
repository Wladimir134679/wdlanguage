package ru.wds.wdl.dap;

import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Адаптер целиком, но без транспорта: запросы зовутся напрямую, события собирает
 * подставной клиент.
 * <p>
 * Без транспорта затем, что проверять здесь надо перевод, а не JSON: что точка
 * доехала до сессии, что остановка стала событием, что кадры и переменные нашлись
 * по числам, которые сам адаптер и выдал. Транспорт проверяет {@link TransportTest} —
 * одним сеансом, потому что за него отвечает lsp4j, а не мы.
 */
@DisplayName("Адаптер отладки DAP")
final class AdapterTest {

    private static final long WAIT_SECONDS = 20;

    private final TestClient client = new TestClient();
    private final WdlDebugAdapter adapter = new WdlDebugAdapter();

    @BeforeEach
    void connect() {
        adapter.connect(client);
    }

    @AfterEach
    void tearDown() {
        adapter.shutdown();
    }

    @Test
    @DisplayName("Точка останова: поток встаёт, кадры и переменные на месте")
    void breakpointStopsThread(@TempDir Path directory) throws Exception {
        Path script = write(directory, """
                def total(price, count) {
                    sum = price * count
                    return sum;
                }
                result = total(120, 3)
                println(result)
                """);
        start(script);

        List<Breakpoint> points = breakpoints(script, 2);
        assertTrue(points.get(0).isVerified(), "точка на инструкции обязана встать");
        assertEquals(2, points.get(0).getLine());

        adapter.configurationDone(new ConfigurationDoneArguments()).get();
        StoppedEventArguments stopped = client.stopped();
        assertEquals("breakpoint", stopped.getReason());

        ThreadsResponse threads = adapter.threads().get();
        assertEquals(1, threads.getThreads().length);

        StackFrame[] frames = frames(stopped.getThreadId());
        assertEquals("total", frames[0].getName(), "верхний кадр — вызванная функция");
        assertEquals(2, frames[0].getLine());
        assertEquals(script.toString(), frames[0].getSource().getPath());
        assertEquals(2, frames.length, "ниже — верхний уровень файла");
        assertEquals("script.wdl", frames[1].getName(),
                "верхний уровень назван файлом, а не путём во всю ширину панели");

        // Инструкция стоит в блоке тела функции, и своих имён у этого блока пока нет:
        // 'sum' объявится этой же строкой. Параметры лежат областью выше — панель
        // «Видимые» показывает ровно то, что кадру видно снаружи.
        assertTrue(locals(frames[0].getId()).isEmpty(), "в блоке ещё ничего не объявлено");
        Map<String, Variable> visible = visible(frames[0].getId());
        assertEquals("120", visible.get("price").getValue());
        assertEquals("3", visible.get("count").getValue());
        assertEquals("number", visible.get("price").getType());

        EvaluateResponse evaluated = evaluate(frames[0].getId(), "price * count");
        assertEquals("360", evaluated.getResult());

        resume(stopped.getThreadId());
        assertEquals(0, client.exited().getExitCode());
        assertTrue(client.text().contains("360"), "вывод скрипта: " + client.text());
    }

    @Test
    @DisplayName("Точка на пустой строке уезжает вниз, к ближайшей инструкции")
    void breakpointSlidesDown(@TempDir Path directory) throws Exception {
        Path script = write(directory, """
                // комментарий

                answer = 42
                """);
        start(script);
        List<Breakpoint> points = breakpoints(script, 1);
        assertTrue(points.get(0).isVerified());
        assertEquals(3, points.get(0).getLine(), "точка встала на первой инструкции ниже");

        adapter.configurationDone(new ConfigurationDoneArguments()).get();
        assertEquals("breakpoint", client.stopped().getReason());
    }

    @Test
    @DisplayName("Шаг через строку встаёт на следующей инструкции того же уровня")
    void stepOverStopsOnNextStatement(@TempDir Path directory) throws Exception {
        Path script = write(directory, """
                def twice(value) => value * 2
                first = twice(2)
                second = twice(3)
                """);
        start(script);
        breakpoints(script, 2);
        adapter.configurationDone(new ConfigurationDoneArguments()).get();
        StoppedEventArguments stopped = client.stopped();

        NextArguments step = new NextArguments();
        step.setThreadId(stopped.getThreadId());
        adapter.next(step).get();

        StoppedEventArguments after = client.stopped();
        assertEquals("step", after.getReason());
        assertEquals(3, frames(after.getThreadId())[0].getLine());
    }

    @Test
    @DisplayName("Останов на ошибке: поток стоит там, где она случилась")
    void stopsOnError(@TempDir Path directory) throws Exception {
        Path script = write(directory, """
                def broken() {
                    return missing + 1;
                }
                broken()
                """);
        start(script);
        SetExceptionBreakpointsArguments filters = new SetExceptionBreakpointsArguments();
        filters.setFilters(new String[]{"error"});
        adapter.setExceptionBreakpoints(filters).get();
        adapter.configurationDone(new ConfigurationDoneArguments()).get();

        StoppedEventArguments stopped = client.stopped();
        assertEquals("exception", stopped.getReason());
        assertEquals(2, frames(stopped.getThreadId())[0].getLine());
        assertNotNull(stopped.getText());

        // Останов ошибку не отменяет: после возобновления она летит наружу.
        resume(stopped.getThreadId());
        assertEquals(1, client.exited().getExitCode());
    }

    @Test
    @DisplayName("Массив и объект раскрываются по частям")
    void containersExpand(@TempDir Path directory) throws Exception {
        Path script = write(directory, """
                items = [10, 20]
                point = {x: 1, y: 2}
                done = true
                """);
        start(script);
        breakpoints(script, 3);
        adapter.configurationDone(new ConfigurationDoneArguments()).get();
        StoppedEventArguments stopped = client.stopped();

        Map<String, Variable> locals = locals(frames(stopped.getThreadId())[0].getId());
        Variable items = locals.get("items");
        assertEquals("array", items.getType());
        assertEquals(2, items.getIndexedVariables());
        assertEquals(List.of("[0]", "[1]"), names(children(items)));
        assertEquals("10", children(items).get(0).getValue());

        Variable point = locals.get("point");
        assertEquals(List.of("x", "y"), names(children(point)));
    }

    @Test
    @DisplayName("Запуск несуществующего файла — отказ с объяснением, а не авария")
    void missingProgramIsRefused() {
        Exception failed = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> adapter.launch(Map.of("program", "нет-такого.wdl")).get());
        assertTrue(String.valueOf(failed.getCause().getMessage()).contains("файл не найден"),
                "сообщение: " + failed.getCause());
    }

    // --- вспомогательное -----------------------------------------------------

    private void start(Path script) throws Exception {
        InitializeRequestArguments initialize = new InitializeRequestArguments();
        initialize.setAdapterID("wdl");
        initialize.setLinesStartAt1(true);
        assertTrue(adapter.initialize(initialize).get().getSupportsConfigurationDoneRequest());
        Map<String, Object> launch = new HashMap<>();
        launch.put("program", script.toString());
        launch.put("projectRoot", script.getParent().toString());
        adapter.launch(launch).get();
        assertTrue(client.initialized, "клиенту сказали, что можно ставить точки");
    }

    private List<Breakpoint> breakpoints(Path script, int... lines) throws Exception {
        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(script.toString());
        SourceBreakpoint[] requested = new SourceBreakpoint[lines.length];
        for (int index = 0; index < lines.length; index++) {
            requested[index] = new SourceBreakpoint();
            requested[index].setLine(lines[index]);
        }
        SetBreakpointsArguments args = new SetBreakpointsArguments();
        args.setSource(source);
        args.setBreakpoints(requested);
        SetBreakpointsResponse response = adapter.setBreakpoints(args).get();
        return List.of(response.getBreakpoints());
    }

    private StackFrame[] frames(int threadId) throws Exception {
        StackTraceArguments args = new StackTraceArguments();
        args.setThreadId(threadId);
        StackTraceResponse response = adapter.stackTrace(args).get();
        return response.getStackFrames();
    }

    /** Панель «Локальные» кадра — именем к переменной. */
    private Map<String, Variable> locals(int frameId) throws Exception {
        return panel(frameId, 0, "Локальные");
    }

    /** Панель «Видимые»: внешние области кадра, включая параметры вызова. */
    private Map<String, Variable> visible(int frameId) throws Exception {
        return panel(frameId, 1, "Видимые");
    }

    private Map<String, Variable> panel(int frameId, int index, String name) throws Exception {
        ScopesArguments args = new ScopesArguments();
        args.setFrameId(frameId);
        Scope[] scopes = adapter.scopes(args).get().getScopes();
        assertEquals(name, scopes[index].getName());
        Map<String, Variable> found = new HashMap<>();
        for (Variable variable : variables(scopes[index].getVariablesReference())) {
            found.put(variable.getName(), variable);
        }
        return found;
    }

    private List<Variable> children(Variable variable) throws Exception {
        return variables(variable.getVariablesReference());
    }

    private List<Variable> variables(int reference) throws Exception {
        VariablesArguments args = new VariablesArguments();
        args.setVariablesReference(reference);
        return List.of(adapter.variables(args).get().getVariables());
    }

    private EvaluateResponse evaluate(int frameId, String expression) throws Exception {
        EvaluateArguments args = new EvaluateArguments();
        args.setFrameId(frameId);
        args.setExpression(expression);
        return adapter.evaluate(args).get();
    }

    private void resume(int threadId) throws Exception {
        ContinueArguments args = new ContinueArguments();
        args.setThreadId(threadId);
        adapter.continue_(args).get();
    }

    private static List<String> names(List<Variable> variables) {
        List<String> names = new ArrayList<>(variables.size());
        for (Variable variable : variables) {
            names.add(variable.getName());
        }
        return names;
    }

    private static Path write(Path directory, String code) throws Exception {
        Path script = directory.resolve("script.wdl");
        Files.writeString(script, code, StandardCharsets.UTF_8);
        return script;
    }

    /** Клиент, который только запоминает: события приходят из потока скрипта. */
    private static final class TestClient implements IDebugProtocolClient {

        private final BlockingQueue<StoppedEventArguments> stops = new LinkedBlockingQueue<>();
        private final BlockingQueue<ExitedEventArguments> exits = new LinkedBlockingQueue<>();
        private final StringBuilder output = new StringBuilder();
        private volatile boolean initialized;

        @Override
        public void initialized() {
            initialized = true;
        }

        @Override
        public void stopped(StoppedEventArguments args) {
            stops.add(args);
        }

        @Override
        public void exited(ExitedEventArguments args) {
            exits.add(args);
        }

        @Override
        public void output(OutputEventArguments args) {
            synchronized (output) {
                output.append(args.getOutput());
            }
        }

        StoppedEventArguments stopped() throws Exception {
            return waited(stops, "остановки");
        }

        ExitedEventArguments exited() throws Exception {
            return waited(exits, "конца скрипта");
        }

        String text() {
            synchronized (output) {
                return output.toString();
            }
        }

        private static <T> T waited(BlockingQueue<T> queue, String what) throws Exception {
            T event = queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            if (event == null) {
                throw new TimeoutException("не дождались " + what);
            }
            return event;
        }
    }
}
