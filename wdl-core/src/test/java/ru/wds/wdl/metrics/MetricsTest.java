package ru.wds.wdl.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsTest {

    @Test
    @DisplayName("замеры складываются по стадиям")
    void totals() {
        MetricsCollector metrics = Metrics.collecting();

        measure(metrics, Stage.LEX, "script.wdl", false);
        measure(metrics, Stage.PARSE, "script.wdl", false);
        measure(metrics, Stage.PARSE, "lib/math", true);

        assertEquals(1, metrics.count(Stage.LEX));
        assertEquals(2, metrics.count(Stage.PARSE));
        assertEquals(1, metrics.count(Stage.PARSE, false));
        assertEquals(1, metrics.count(Stage.PARSE, true));
        assertEquals(0, metrics.count(Stage.EXECUTE));
        assertTrue(metrics.total(Stage.PARSE).compareTo(metrics.total(Stage.PARSE, true)) > 0,
                "сумма стадии включает и модули, и главный файл");
        assertEquals(metrics.total(Stage.LEX).plus(metrics.total(Stage.PARSE)), metrics.sum());
    }

    @Test
    @DisplayName("выключенные метрики ничего не помнят и не создают объектов")
    void off() {
        Metrics metrics = Metrics.off();

        Measure first = metrics.begin(Stage.LEX, "script.wdl");
        Measure second = metrics.begin(Stage.EXECUTE, "script.wdl");
        first.close();
        second.close();

        assertSame(first, second, "заглушка одна на всех — аллокации на выключенном пути нет");
        assertSame(Metrics.off(), metrics);
    }

    @Test
    @DisplayName("пустой отчёт печатается пустой строкой")
    void emptyReport() {
        MetricsCollector metrics = Metrics.collecting();

        assertTrue(metrics.isEmpty());
        assertEquals("", metrics.render());
        assertEquals(Duration.ZERO, metrics.sum());
        assertEquals(0, metrics.threads());
    }

    @Test
    @DisplayName("слушатель получает замер сразу по завершении стадии")
    void listener() {
        List<Measurement> logged = new ArrayList<>();
        MetricsCollector metrics = Metrics.collecting(logged::add);

        Measure measure = metrics.begin(Stage.EXECUTE, "script.wdl");
        assertTrue(logged.isEmpty(), "пока стадия идёт, сообщать не о чем");
        spend();
        measure.close();

        assertEquals(1, logged.size());
        assertEquals(Stage.EXECUTE, logged.get(0).stage());
        assertEquals("script.wdl", logged.get(0).subject());
        assertEquals(Thread.currentThread().getName(), logged.get(0).thread());
    }

    @Test
    @DisplayName("повторное закрытие замера ничего не добавляет")
    void closedOnce() {
        MetricsCollector metrics = Metrics.collecting();

        Measure measure = metrics.begin(Stage.LEX, "script.wdl");
        measure.close();
        measure.close();

        assertEquals(1, metrics.count(Stage.LEX));
    }

    @Test
    @DisplayName("замеры из нескольких потоков не теряются")
    void concurrent() throws InterruptedException {
        MetricsCollector metrics = Metrics.collecting();
        int threads = 8;
        int perThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        measure(metrics, Stage.EXECUTE, "worker", true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "measure-" + i);
            worker.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "потоки замеров должны успеть");

        assertEquals(threads * perThread, metrics.count(Stage.EXECUTE));
        assertEquals(threads * perThread, metrics.all().size());
        assertEquals(threads, metrics.threads());
    }

    @Test
    @DisplayName("время по часам не меньше самого долгого замера и не растёт после finish()")
    void wall() {
        MetricsCollector metrics = Metrics.collecting();

        measure(metrics, Stage.EXECUTE, "script.wdl", false);
        Duration longest = metrics.all().stream()
                .map(Measurement::duration)
                .max(Duration::compareTo)
                .orElseThrow();

        assertTrue(metrics.wall().compareTo(longest) >= 0);
        Duration fixed = metrics.finish().wall();
        spend();
        assertEquals(fixed, metrics.wall(), "после finish() время по часам не меняется");
    }

    @Test
    @DisplayName("вложенный замер виден как вложенный")
    void nesting() {
        MetricsCollector metrics = Metrics.collecting();

        Measure outer = metrics.begin(Stage.EXECUTE, "script.wdl");
        measure(metrics, Stage.EXECUTE, "lib/math", true);
        spend();
        outer.close();

        List<Measurement> all = metrics.all();
        Measurement module = all.get(0);
        Measurement script = all.get(1);
        assertTrue(module.insideOf(script), "время модуля входит во время скрипта");
        assertFalse(script.insideOf(module));
    }

    @Test
    @DisplayName("чужой отчёт втягивается целиком, но слушателю о нём не сообщают")
    void adopt() {
        MetricsCollector compile = Metrics.collecting();
        measure(compile, Stage.PARSE, "script.wdl", false);
        List<Measurement> logged = new ArrayList<>();
        MetricsCollector run = Metrics.collecting(logged::add);

        run.adopt(compile);

        assertEquals(1, run.count(Stage.PARSE));
        assertEquals(compile.total(Stage.PARSE), run.total(Stage.PARSE));
        assertTrue(logged.isEmpty(), "втянутый замер случился раньше — он не «только что завершился»");
    }

    @Test
    @DisplayName("таблица показывает стадии, модули и оба итога")
    void render() {
        MetricsCollector metrics = Metrics.collecting();
        measure(metrics, Stage.LEX, "script.wdl", false);
        measure(metrics, Stage.PARSE, "lib/math", true);
        Measure outer = metrics.begin(Stage.EXECUTE, "script.wdl");
        measure(metrics, Stage.EXECUTE, "lib/math", true);
        outer.close();

        String table = metrics.finish().render();

        assertTrue(table.contains("лексер"), table);
        assertTrue(table.contains("выполнение"), table);
        assertTrue(table.contains("в т.ч. модули"), table);
        assertTrue(table.contains("модули: разбор"), table);
        assertTrue(table.contains("сумма замеров"), table);
        assertTrue(table.contains("по часам"), table);
        assertFalse(table.contains("закрытие"), "стадии без замеров строки не получают: " + table);
    }

    /** Один законченный замер с заметной длительностью. */
    private static void measure(Metrics metrics, Stage stage, String subject, boolean module) {
        Measure measure = metrics.begin(stage, subject, module);
        try {
            spend();
        } finally {
            measure.close();
        }
    }

    /** Немного работы, чтобы замер был заведомо больше нуля. */
    private static void spend() {
        long until = System.nanoTime() + 200_000L;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }
}
