package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.metrics.Measurement;
import ru.wds.wdl.metrics.Metrics;
import ru.wds.wdl.metrics.MetricsCollector;
import ru.wds.wdl.metrics.Stage;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Метрики на живом запуске: что засчитывается стадиями и что при этом видно про модули.
 * <p>
 * Модули задаются картой, а не файлами: проверяется состав замеров, а не файловая система.
 */
class MetricsIntegrationTest {

    private static final Map<String, String> MATH = Map.of("lib/math", """
            const PI = 3
            def add(a, b) => a + b
            """);

    @Test
    @DisplayName("выполнение главного файла засчитывается один раз")
    void executeCounted() {
        MetricsCollector metrics = Metrics.collecting();

        run("""
                def total(price, count) => price * count
                total(120, 3)
                """, Map.of(), metrics);

        assertEquals(1, metrics.count(Stage.EXECUTE));
        assertEquals(1, metrics.count(Stage.EXECUTE, false));
        assertEquals(0, metrics.count(Stage.EXECUTE, true));
        assertEquals("script.wdl", metrics.all().get(0).subject());
    }

    @Test
    @DisplayName("импорт добавляет разбор и выполнение модуля отдельными замерами")
    void moduleStages() {
        MetricsCollector metrics = Metrics.collecting();

        run("""
                import lib.math
                add(PI, 4)
                """, MATH, metrics);

        assertEquals(1, metrics.count(Stage.LEX, true), "модуль разобран лексером один раз");
        assertEquals(1, metrics.count(Stage.PARSE, true));
        assertEquals(0, metrics.count(Stage.LEX, false), "главный файл разбирал вызывающий");
        assertEquals(1, metrics.count(Stage.EXECUTE, true));
        assertEquals(1, metrics.count(Stage.EXECUTE, false));

        List<Measurement> executions = metrics.all().stream()
                .filter(measurement -> measurement.stage() == Stage.EXECUTE)
                .toList();
        Measurement module = executions.get(0);
        Measurement script = executions.get(1);
        assertEquals("lib/math", module.subject());
        assertTrue(module.insideOf(script), "время модуля входит во время скрипта");
    }

    @Test
    @DisplayName("модуль, импортированный дважды, меряется один раз")
    void moduleExecutedOnce() {
        MetricsCollector metrics = Metrics.collecting();

        run("""
                import lib.math
                import lib.math as m
                add(PI, m.PI)
                """, MATH, metrics);

        assertEquals(1, metrics.count(Stage.PARSE, true));
        assertEquals(1, metrics.count(Stage.EXECUTE, true));
    }

    @Test
    @DisplayName("упавший скрипт всё равно отдаёт время до падения")
    void failedScriptStillMeasured() {
        MetricsCollector metrics = Metrics.collecting();

        WdlError error = org.junit.jupiter.api.Assertions.assertThrows(WdlError.class,
                () -> run("""
                        x = 1 / 0
                        """, Map.of(), metrics));

        assertFalse(error.getMessage().isBlank());
        assertEquals(1, metrics.count(Stage.EXECUTE));
        assertTrue(metrics.total(Stage.EXECUTE).compareTo(Duration.ZERO) >= 0);
    }

    @Test
    @DisplayName("без приёмника ничего не считается и скрипт работает как раньше")
    void offByDefault() {
        StringBuilder printed = new StringBuilder();
        ModuleUnits units = new ModuleUnits(ModuleSource.ofMap(MATH));
        ExecutionContext context = ExecutionContext.fresh(printed::append).withModules(units);
        Source source = Source.ofString("""
                import lib.math
                println(add(PI, 4))
                """);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);

        new Interpreter().run(Unit.of(source, program), context);

        assertEquals("7" + System.lineSeparator(), printed.toString());
        assertEquals(Metrics.off(), context.metrics(), "по умолчанию метрики выключены");
    }

    /** Выполняет скрипт с включёнными метриками — так же, как это делает консольный запуск. */
    private static void run(String code, Map<String, String> modules, MetricsCollector metrics) {
        ModuleUnits units = new ModuleUnits(ModuleSource.ofMap(modules), metrics);
        ExecutionContext context = ExecutionContext.fresh()
                .withMetrics(metrics)
                .withModules(units);
        Source source = new Source("script.wdl", code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(Unit.of(source, program), context);
    }
}
