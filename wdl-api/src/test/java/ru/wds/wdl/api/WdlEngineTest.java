package ru.wds.wdl.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.metrics.MetricsReport;
import ru.wds.wdl.metrics.Stage;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.Limits;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.types.NullValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Фасад встраивания: сборка движка, разбор один раз — запусков много, доступ
 * к тому, что скрипт объявил, и вызов его функций из приложения.
 * <p>
 * Проверяется не столько перекладывание вызовов — оно очевидно, — сколько граница:
 * что скрипту доступно, что он отдаёт наружу и что происходит, когда он падает.
 */
class WdlEngineTest {
    @Test
    @DisplayName("явный корень проекта и каталог файла по умолчанию")
    void projectRoot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var nested = java.nio.file.Files.createDirectories(root.resolve("nested"));
        var file = nested.resolve("main.wdl");
        java.nio.file.Files.writeString(file, "import helper; def result() => value; result();");
        java.nio.file.Files.writeString(root.resolve("helper.wdl"), "value = 42;");
        java.nio.file.Files.writeString(nested.resolve("helper.wdl"), "value = 99;");
        assertEquals(42L, Values.toJava(WdlEngine.builder().projectRoot(root).build().compile(file).run()));
        assertEquals(42L, Values.toJava(WdlEngine.builder().sourceRoot(root).build().compile(file).run()));
        assertEquals(99L, Values.toJava(WdlEngine.builder().build().compile(file).run()));
    }

    private static WdlEngine engine() {
        return WdlEngine.builder().stdlib(Stdlib.SAFE).build();
    }

    private static WdlEngine enginePrintln() {
        return WdlEngine.builder().output(Output.standard()).stdlib(Stdlib.SAFE).build();
    }

    /**
     * Выполняет скрипт и переводит результат в Java-объект.
     * <p>
     * Типизированный слой отдаёт {@code Value} — тесту про границу удобнее сравнивать
     * с обычными {@code Long} и {@code String}, как это делает приложение.
     */
    private static Object result(WdlEngine engine, String code) {
        return Values.toJava(engine.run(code));
    }

    @Test
    @DisplayName("Результат скрипта — значение его последнего выражения")
    void resultIsLastExpression() {
        assertEquals(360L, Wdl.run("""
                def total(price, count) => price * count
                total(120, 3)
                """));
    }

    @Test
    @DisplayName("Скрипт без выражения в конце возвращает null")
    void noExpressionMeansNull() {
        assertNull(Wdl.run("x = 5"));
    }

    @Test
    @DisplayName("Значения переводятся в обычные Java-объекты")
    void convertsToJava() {
        assertEquals("привет", Wdl.eval("\"привет\""));
        assertEquals(42L, Wdl.eval("42"));
        assertEquals(1.5, Wdl.eval("1.5"));
        assertEquals(true, Wdl.eval("1 < 2"));
        assertEquals(List.of(1L, 2L), Wdl.eval("[1, 2]"));
        assertEquals(Map.of("a", 1L), Wdl.eval("{ a: 1 }"));
    }

    @Test
    @DisplayName("Вывод идёт туда, куда сказало приложение")
    void printsWhereAsked() {
        StringBuilder printed = new StringBuilder();
        WdlEngine engine = WdlEngine.builder().output(printed::append).build();

        engine.run("println(\"строка\")");

        assertEquals("строка" + System.lineSeparator(), printed.toString());
    }

    @Test
    @DisplayName("По умолчанию движок не печатает никуда")
    void silentByDefault() {
        // Не «отключено флагом», а «вывода нет»: встроенный движок не имеет права
        // печатать в чужую консоль без спроса. Проверяется тем, что печать проходит
        // без ошибки и не оставляет следа — куда именно, знает только сам движок.
        assertNull(Values.toJava(WdlEngine.minimal().run("println(\"молчи\")")));
    }

    @Test
    @DisplayName("Имя от приложения скрипт видит готовым")
    void seesDefinedGlobals() {
        WdlEngine engine = WdlEngine.builder().define("appName", "демо").build();

        assertEquals("демо-1", Values.toJava(engine.eval("appName + \"-1\"")));
    }

    @Test
    @DisplayName("SAFE не даёт файлов и сети, STANDARD даёт")
    void stdlibPresetsDecideWhatExists() {
        // Скрипту не отказывают в правах — модуля просто нет, и ошибка обычная.
        WdlException denied = assertThrows(WdlException.class,
                () -> WdlEngine.builder().stdlib(Stdlib.SAFE).build().run("import sys.io as io"));
        assertTrue(denied.getMessage().contains("sys"), denied.getMessage());

        assertEquals(2L, result(WdlEngine.builder().stdlib(Stdlib.STANDARD).build(),
                "import sys.json as json\nlen(json.parse(\"[1,2]\"))"));
    }

    @Test
    @DisplayName("std кладётся в корень: sqrt доступен без импорта")
    void stdGoesToRoot() {
        assertEquals(3.0, Values.toJava(engine().eval("sqrt(9)")));
    }

    @Test
    @DisplayName("Одно дерево, много запусков — состояние не переносится")
    void oneScriptManyInstances() {
        WdlScript script = engine().compile("""
                counter = 0
                def bump() { counter = counter + 1; return counter; }
                bump()
                """);

        try (WdlInstance first = script.instance(); WdlInstance second = script.instance()) {
            assertEquals(1L, Values.toJava(first.execute()));
            assertEquals(1L, Values.toJava(second.execute()),
                    "второй запуск обязан начать с нуля, иначе изоляция мнимая");
            assertNotSame(first.context(), second.context());
        }
    }

    @Test
    @DisplayName("Функция скрипта вызывается приложением и помнит своё окружение")
    void callsScriptFunction() {
        try (WdlInstance script = engine().compile("""
                greeting = "привет"
                def greet(who) => greeting + ", " + who
                """).instance()) {
            script.execute();

            assertEquals("привет, мир", script.function("greet").invoke("мир"));
        }
    }

    @Test
    @DisplayName("Функция видит изменения, случившиеся в скрипте после её объявления")
    void functionSeesLiveScope() {
        try (WdlInstance script = engine().compile("""
                total = 0
                def add(n) { total = total + n; return total; }
                """).instance()) {
            script.execute();
            WdlCallable add = script.function("add");

            assertEquals(5L, add.invoke(5));
            assertEquals(12L, add.invoke(7));
            assertEquals(12L, script.get("total"), "переменная скрипта менялась по-настоящему");
        }
    }

    @Test
    @DisplayName("Функция подставляется под интерфейс приложения")
    void fitsApplicationInterface() {
        try (WdlInstance script = engine().compile("""
                def onMessage(text) => "эхо: " + text
                """).instance()) {
            script.execute();

            // Скрипт про этот интерфейс ничего не знает: он написал функцию,
            // а совместить её с чужой подписью — работа границы.
            Handler handler = script.function("onMessage").as(Handler.class);

            assertEquals("эхо: ага", handler.handle("ага"));
        }
    }

    /** Интерфейс приложения — такой же, как любой другой в чужом коде. */
    private interface Handler {
        String handle(String message);
    }

    @Test
    @DisplayName("Метод объекта скрипта тоже обычная функция")
    void methodIsAFunction() {
        try (WdlInstance script = engine().compile("""
                class Counter(value) {
                    def bump(by) { this.value = value + by; return value; }
                }
                counter = new Counter(10)
                def bump(by) => counter.bump(by)
                """).instance()) {
            script.execute();

            assertEquals(15L, script.function("bump").invoke(5));
            assertEquals(Map.of("value", 15L), script.get("counter"));
        }
    }

    @Test
    @DisplayName("Функция, вызванная снаружи, сохраняет доступ к модулям своего запуска")
    void keepsModulesWhenCalledOutside() {
        WdlEngine engine = WdlEngine.builder()
                .sources(ModuleSource.ofMap(Map.of("lib", "def double(x) => x * 2")))
                .build();

        try (WdlInstance script = engine.compile("""
                import "./lib" as lib
                def compute(x) => lib.double(x)
                """).instance()) {
            script.execute();

            assertEquals(42L, script.function("compute").invoke(21));
        }
    }

    @Test
    @DisplayName("Ошибка выполнения приходит с местом в тексте скрипта")
    void reportsRuntimeError() {
        WdlException failure = assertThrows(WdlException.class,
                () -> engine().run("x = 1\nprintln(total)"));

        assertTrue(failure.getMessage().contains("total"), failure.getMessage());
        // Не стек Java, а строка скрипта с подчёркиванием: это читает автор скрипта.
        assertTrue(failure.getMessage().contains("println(total)"), failure.getMessage());
    }

    @Test
    @DisplayName("Ошибка разбора приходит со всеми диагностиками сразу")
    void reportsAllSyntaxErrors() {
        WdlException failure = assertThrows(WdlException.class, () -> engine().compile("x = ("));

        assertFalse(failure.diagnostics().isEmpty());
    }

    @Test
    @DisplayName("Выражение вычисляется в области выполненного скрипта")
    void evaluatesInScriptScope() {
        try (WdlInstance script = engine().compile("player = { name: \"Аня\" }").instance()) {
            script.execute();

            assertEquals("Аня", Values.toJava(script.eval("player.name")));
        }
    }

    @Test
    @DisplayName("Повторное выполнение одного экземпляра — ошибка, а не тихий второй проход")
    void refusesSecondExecute() {
        try (WdlInstance script = engine().compile("x = 1").instance()) {
            script.execute();

            assertThrows(IllegalStateException.class, script::execute);
        }
    }

    @Test
    @DisplayName("Отсутствующая функция называется прямо, а не даёт null")
    void namesMissingFunction() {
        try (WdlInstance script = engine().compile("x = 1").instance()) {
            script.execute();

            IllegalStateException missing = assertThrows(IllegalStateException.class,
                    () -> script.function("onMessage"));
            assertTrue(missing.getMessage().contains("onMessage"), missing.getMessage());
            assertFalse(script.hasFunction("onMessage"));
        }
    }

    @Test
    @DisplayName("Закрывать экземпляр дважды безопасно")
    void closeIsIdempotent() {
        WdlInstance script = engine().compile("x = 1").instance();
        script.execute();

        script.close();
        script.close();
    }

    @Test
    @DisplayName("Вызовы одного экземпляра из разных потоков не портят его состояние")
    void survivesConcurrentCalls() throws Exception {
        try (WdlInstance script = enginePrintln().compile("""
                calls = 0
                def bump() { calls = calls + 1; println(calls); return calls; }
                """).instance()) {
            script.execute();
            hammer(script.function("bump"), 8, 150);

            // Точного числа здесь больше не спрашивают, и это правильный сигнал:
            // 'calls = calls + 1' — два обращения, а атомарно в языке одно. Раньше
            // ответ был точным не потому, что скрипт синхронизировался, а потому,
            // что параллельности не было вовсе; молчаливо полагаться на такое нельзя.
            // Проверяется то, за что отвечает движок: состояние цело, счётчик — число
            // в разумных границах.
            long calls = (Long) script.get("calls");
            assertTrue(calls > 0 && calls <= 8L * 150, "счётчик вне границ: " + calls);
        }
    }

    @Test
    @DisplayName("'synchronized def' возвращает точный счёт без всякого режима")
    void synchronizedFunctionKeepsEveryIncrement() throws Exception {
        try (WdlInstance script = engine().compile("""
                calls = 0
                synchronized def bump() { calls = calls + 1; return calls; }
                """).instance()) {
            script.execute();
            hammer(script.function("bump"), 8, 150);

            // Это правильный ответ на потерянные инкременты: не флаг движка, а одно
            // слово в скрипте — там, где автор знает, что склеивает.
            assertEquals(8L * 150, script.get("calls"));
        }
    }

    @Test
    @DisplayName("close() останавливает потоки скрипта, а вызов после него — ошибка")
    void closeStopsScriptThreads() {
        WdlEngine withThreads = WdlEngine.builder().stdlib(Stdlib.STANDARD).build();
        WdlInstance script = withThreads.compile("""
                import sys.thread as th

                def spin() {
                    while (true) th.sleep(20)
                }

                first = th.spawn("worker-1", spin)
                second = th.spawn("worker-2", spin)
                def ping() => "жив"
                """).instance();
        WdlCallable ping;
        try {
            script.execute();
            ping = script.function("ping");
            assertEquals("жив", ping.invoke());
        } finally {
            // Закрытие обязано уложиться в свой таймаут: два вечных цикла внутри
            // не должны превращать close() в «приложение не завершается».
            assertTimeoutPreemptively(Duration.ofSeconds(10), script::close);
        }

        // Вход закрыт — и это остановка выполнения, а не ошибка скрипта: работать
        // по закрытым модулям нечестно, а ловить такое обработчиком незачем.
        WdlException failed = assertThrows(WdlException.class, ping::call);
        assertTrue(failed.getMessage().contains("запуск закрыт"), failed.getMessage());
    }

    /**
     * Библиотека, которая зовёт скрипт во время собственного закрытия.
     * <p>
     * Не выдумка ради теста: ровно так устроен {@code sys.gui} — его {@code close()}
     * ждёт, пока пользователь закроет окна, и всё это время обработчики кнопок
     * работают. Закрой движок вход раньше библиотек — первое же нажатие давало бы
     * «запуск закрыт».
     */
    private static final class Farewell implements Library {

        private final String title;
        private final List<String> said = new ArrayList<>();
        private FunctionValue handler;

        Farewell(String title) {
            this.title = title;
        }

        @Override
        public String name() {
            return title;
        }

        @Override
        public Environment installTo(Environment scope) {
            scope.define("onClose", BuiltinFunction.of("onClose", Arity.exactly(1),
                    (context, arguments, span) -> {
                        handler = arguments.function(0, "обработчик");
                        return NullValue.NULL;
                    }));
            return scope;
        }

        @Override
        public void close() {
            if (handler != null) {
                said.add(handler.call(text -> { }, List.of(), Span.point(0)).display());
            }
        }
    }

    @Test
    @DisplayName("Библиотека и модуль вправе позвать скрипт во время своего закрытия")
    void libraryMayCallScriptWhileClosing() {
        Farewell root = new Farewell("root");
        Farewell module = new Farewell("goodbye");
        WdlEngine engine = WdlEngine.builder()
                .library("root", () -> root)
                .module("goodbye", () -> module)
                .build();

        try (WdlInstance script = engine.compile("""
                import goodbye as g

                onClose(def () => "из корня")
                g.onClose(def () => "из модуля")
                """).instance()) {
            script.execute();
        }

        // Не «запуск закрыт»: вход закрывается последним, после того как всё, что имело
        // право позвать скрипт, отработало. Оба пути закрытия — модули и библиотеки
        // корня — проверяются вместе: закрываются они в разные моменты, и сломать
        // можно каждый по отдельности.
        assertEquals(List.of("из корня"), root.said);
        assertEquals(List.of("из модуля"), module.said);
    }

    /** Зовёт функцию из нескольких потоков и дожидается всех. */
    private static void hammer(WdlCallable function, int threads, int perThread) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                work.add(() -> {
                    for (int call = 0; call < perThread; call++) {
                        function.call();
                    }
                    return null;
                });
            }
            for (Future<Void> done : pool.invokeAll(work)) {
                done.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Разные экземпляры считают независимо и параллельно")
    void instancesAreIndependent() throws Exception {
        WdlScript script = engine().compile("""
                calls = 0
                def bump() { calls = calls + 1; return calls; }
                """);
        int threads = 4;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Object>> work = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                work.add(() -> {
                    // Настоящая параллельность даётся так: у каждого потока свой запуск,
                    // и общего у них нет ничего.
                    try (WdlInstance own = script.instance()) {
                        own.execute();
                        for (int call = 0; call < 100; call++) {
                            own.function("bump").call();
                        }
                        return own.get("calls");
                    }
                });
            }
            for (Future<Object> done : pool.invokeAll(work)) {
                assertEquals(100L, done.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Файл, выполняемый одной строкой, находит свои модули рядом с собой")
    void resolvesModulesNextToScript() {
        WdlEngine engine = WdlEngine.builder()
                .stdlib(Stdlib.SAFE)
                .sources(ModuleSource.ofMap(Map.of("util", "def twice(x) => x + x")))
                .build();

        assertEquals("абаб", result(engine, """
                import "./util" as util
                util.twice("аб")
                """));
    }

    @Test
    @DisplayName("Output.standard() и лямбда подходят без обёрток")
    void outputIsFunctional() {
        StringBuilder log = new StringBuilder();
        WdlEngine engine = WdlEngine.builder().output((Output) log::append).build();

        engine.run("print(\"а\")");

        assertEquals("а", log.toString());
    }

    // --- метрики -------------------------------------------------------------

    @Test
    @DisplayName("по умолчанию метрики выключены и отчёт пуст")
    void metricsOffByDefault() {
        WdlScript script = engine().compile("x = 40 + 2");

        try (WdlInstance instance = script.instance()) {
            instance.execute();
            assertTrue(script.metrics().isEmpty());
            assertTrue(instance.metrics().isEmpty());
            assertEquals("", instance.metrics().render());
        }
    }

    @Test
    @DisplayName("включённые метрики считают разбор, выполнение и закрытие")
    void metricsCollected() {
        WdlEngine engine = WdlEngine.builder().stdlib(Stdlib.SAFE).metrics(true).build();
        WdlScript script = engine.compile("x = 40 + 2");

        assertEquals(1, script.metrics().count(Stage.LEX));
        assertEquals(1, script.metrics().count(Stage.PARSE));
        assertEquals(0, script.metrics().count(Stage.EXECUTE), "разбор не выполняет");

        MetricsReport report;
        try (WdlInstance instance = script.instance()) {
            instance.execute();
            report = instance.metrics();
            // Замеры разбора втянуты в отчёт запуска: приложению нужен один ответ.
            assertEquals(1, report.count(Stage.LEX));
            assertEquals(1, report.count(Stage.EXECUTE));
            assertEquals(0, report.count(Stage.SHUTDOWN), "закрытие ещё не случилось");
        }
        assertEquals(1, report.count(Stage.SHUTDOWN));
        assertTrue(report.render().contains("выполнение"), report.render());
    }

    @Test
    @DisplayName("у каждого экземпляра свой отчёт")
    void metricsPerInstance() {
        WdlEngine engine = WdlEngine.builder().stdlib(Stdlib.SAFE).metrics(true).build();
        WdlScript script = engine.compile("x = 1");

        try (WdlInstance first = script.instance(); WdlInstance second = script.instance()) {
            first.execute();

            assertEquals(1, first.metrics().count(Stage.EXECUTE));
            assertEquals(0, second.metrics().count(Stage.EXECUTE), "второй ещё не выполнялся");
            assertNotSame(first.metrics(), second.metrics());
        }
    }

    @Test
    @DisplayName("слушатель получает стадии по мере их завершения")
    void metricsListener() {
        List<Stage> stages = new ArrayList<>();
        WdlEngine engine = WdlEngine.builder()
                .stdlib(Stdlib.SAFE)
                .metrics(measurement -> stages.add(measurement.stage()))
                .build();

        try (WdlInstance instance = engine.compile("x = 1").instance()) {
            instance.execute();
        }

        assertEquals(List.of(Stage.LEX, Stage.PARSE, Stage.EXECUTE, Stage.SHUTDOWN), stages);
    }

    @Test
    @DisplayName("SAFE ставит пределы сам, STANDARD и minimal — нет")
    void safePresetBringsLimits() {
        assertEquals(Limits.safeDefaults(),
                WdlEngine.builder().stdlib(Stdlib.SAFE).build().limits());
        assertEquals(Limits.none(), WdlEngine.standard(Output.discarding()).limits());
        assertEquals(Limits.none(), WdlEngine.minimal().limits());
    }

    @Test
    @DisplayName("явные пределы сильнее умолчания набора — в обе стороны")
    void explicitLimitsWin() {
        assertEquals(Limits.none(),
                WdlEngine.builder().stdlib(Stdlib.SAFE).limits(Limits.none()).build().limits());
        // Короткая форма меняет одно поле, а не обнуляет остальные: таймаут и квота
        // безопасного набора обязаны остаться при себе.
        Limits tightened = WdlEngine.builder().stdlib(Stdlib.SAFE).maxSteps(1000).build().limits();
        assertEquals(1000, tightened.maxSteps());
        assertEquals(Limits.SAFE_TIMEOUT, tightened.timeout());
        assertEquals(Limits.SAFE_THREADS, tightened.maxThreads());
    }

    @Test
    @DisplayName("зациклившийся скрипт останавливается пределом, а не висит")
    void loopingScriptIsStopped() {
        WdlEngine engine = WdlEngine.builder().stdlib(Stdlib.SAFE).maxSteps(10_000).build();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            WdlException failed = assertThrows(WdlException.class,
                    () -> engine.run("while (true) { }"));
            assertTrue(failed.getMessage().contains("исчерпал отведённые"), failed.getMessage());
        });
    }

    @Test
    @DisplayName("разбор и выполнение модуля видны отдельными замерами")
    void metricsForModules() {
        WdlEngine engine = WdlEngine.builder()
                .stdlib(Stdlib.SAFE)
                .metrics(true)
                .sources(ModuleSource.ofMap(Map.of("util", "def twice(x) => x + x")))
                .build();

        try (WdlInstance instance = engine.compile("""
                import "./util" as util
                util.twice("аб")
                """).instance()) {
            instance.execute();

            assertEquals(1, instance.metrics().count(Stage.PARSE, true));
            assertEquals(1, instance.metrics().count(Stage.EXECUTE, true));
            assertTrue(instance.metrics().render().contains("модули: разбор"),
                    instance.metrics().render());
        }
    }
}
