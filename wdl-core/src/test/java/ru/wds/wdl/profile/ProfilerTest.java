package ru.wds.wdl.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Накопитель профиля сам по себе: что он считает, получая вызовы напрямую.
 * <p>
 * Без интерпретатора — здесь проверяются правила счёта («сам», рекурсия, рёбра),
 * а не то, откуда вызовы приходят. Откуда — проверяет {@code ProfileIntegrationTest}.
 */
class ProfilerTest {

    private static final CallSite OUTER = CallSite.of(CallKind.FUNCTION, "outer");
    private static final CallSite INNER = CallSite.of(CallKind.FUNCTION, "inner");

    @Test
    @DisplayName("выключенный профиль ничего не помнит и не создаёт объектов")
    void off() {
        Profiler profiler = Profiler.off();

        Probe first = profiler.enter(OUTER);
        Probe second = profiler.enter(INNER);
        first.close();
        second.close();

        assertFalse(profiler.recording(), "точка съёма спрашивает это до постройки записи");
        assertSame(first, second, "заглушка одна на всех — аллокации на выключенном пути нет");
        assertSame(Profiler.off(), profiler);
        assertSame(Profiler.off(), Probe.off());
    }

    @Test
    @DisplayName("вызовы считаются, а время вложенного вычитается из времени внешнего")
    void selfExcludesNested() {
        CallProfiler profiler = Profiler.collecting();

        Probe outer = profiler.enter(OUTER);
        Probe inner = profiler.enter(INNER);
        burn();
        inner.close();
        outer.close();

        CallProfile outerProfile = profileOf(profiler, "outer");
        CallProfile innerProfile = profileOf(profiler, "inner");
        assertEquals(1, outerProfile.calls());
        assertEquals(1, innerProfile.calls());
        assertTrue(outerProfile.totalNanos() >= innerProfile.totalNanos(),
                "время внешнего включает время вложенного");
        assertTrue(outerProfile.selfNanos() < outerProfile.totalNanos(),
                "собственное время внешнего меньше полного: работал вложенный");
        assertEquals(2, profiler.calls());
    }

    @Test
    @DisplayName("рекурсия считается вызовами, но время не складывается дважды")
    void recursionCountedOnce() {
        CallProfiler profiler = Profiler.collecting();

        Probe first = profiler.enter(OUTER);
        Probe second = profiler.enter(OUTER);
        Probe third = profiler.enter(OUTER);
        burn();
        third.close();
        second.close();
        first.close();

        CallProfile profile = profileOf(profiler, "outer");
        assertEquals(3, profile.calls(), "каждый вход — это вызов");
        assertTrue(profile.totalNanos() <= profiler.wall().toNanos(),
                "«всего» у рекурсии не больше времени по часам: вложенные входы не складываются");
    }

    @Test
    @DisplayName("граф вызовов помнит, кто кого звал")
    void edges() {
        CallProfiler profiler = Profiler.collecting();

        for (int i = 0; i < 3; i++) {
            Probe outer = profiler.enter(OUTER);
            Probe inner = profiler.enter(INNER);
            inner.close();
            outer.close();
        }

        List<CallEdge> edges = profiler.edges();
        assertEquals(1, edges.size(), "пара «вызывающий — вызываемый» одна на все три вызова");
        assertEquals(OUTER, edges.get(0).caller());
        assertEquals(INNER, edges.get(0).callee());
        assertEquals(3, edges.get(0).calls());
    }

    @Test
    @DisplayName("вызовы из разных потоков считаются вместе, а вложенность — у каждого своя")
    void threads() throws InterruptedException {
        CallProfiler profiler = Profiler.collecting();
        CountDownLatch done = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            Thread worker = new Thread(() -> {
                Probe probe = profiler.enter(OUTER);
                burn();
                probe.close();
                done.countDown();
            }, "worker-" + i);
            worker.start();
        }
        assertTrue(done.await(5, TimeUnit.SECONDS), "потоки должны успеть");

        assertEquals(2, profileOf(profiler, "outer").calls());
        assertEquals(2, profiler.threads());
        assertTrue(profiler.edges().isEmpty(), "у корневого вызова вызывающего нет");
    }

    @Test
    @DisplayName("повторное закрытие счёта ничего не добавляет")
    void closedOnce() {
        CallProfiler profiler = Profiler.collecting();

        Probe probe = profiler.enter(OUTER);
        probe.close();
        probe.close();

        assertEquals(1, profileOf(profiler, "outer").calls());
    }

    @Test
    @DisplayName("запись знает файл и строку объявления, а файл сам себя не подписывает")
    void titles() {
        Source source = new Source("script.wdl", "x = 1\ndef total(a) => a\n");
        CallSite function = CallSite.of(CallKind.FUNCTION, "total", source, new Span(6, 24));
        CallSite script = CallSite.of(CallKind.SCRIPT, "script.wdl", source, Span.point(0));

        assertEquals(2, function.line());
        assertEquals("total (script.wdl:2)", function.title());
        assertEquals("script.wdl", script.title(), "имя файла и есть место — второй раз не пишем");
        assertEquals("", CallSite.of(CallKind.NATIVE, "println").file());
        assertEquals(0, CallSite.of(CallKind.NATIVE, "println").line());
    }

    @Test
    @DisplayName("таблица показывает горячее место первым, а пустой профиль — пустую строку")
    void render() {
        assertEquals("", Profiler.collecting().render());

        CallProfiler profiler = Profiler.collecting();
        // Холостой заход первым: он оплачивает разогрев карт и стека потока, который
        // иначе достался бы внешнему вызову и попал бы в его собственное время.
        profiler.enter(INNER).close();
        Probe outer = profiler.enter(OUTER);
        Probe inner = profiler.enter(INNER);
        burn();
        inner.close();
        outer.close();
        profiler.finish();

        String table = profiler.render();
        assertTrue(table.startsWith("── профиль"), () -> table);
        assertTrue(table.indexOf("inner") < table.indexOf("outer"),
                () -> "первым идёт тот, кто работал сам:\n" + table);
        assertTrue(table.contains("вызовов 3"), () -> table);
    }

    /** Немного настоящей работы: профиль меряет время, и оно должно быть заметным. */
    private static void burn() {
        long sum = 0;
        for (int i = 0; i < 2_000_000; i++) {
            sum += i;
        }
        if (sum < 0) {
            throw new AssertionError("недостижимо: " + sum);
        }
    }

    private static CallProfile profileOf(ProfileReport report, String name) {
        return report.all().stream()
                .filter(profile -> profile.site().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет записи '" + name + "' в " + report.all()));
    }
}
