package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.profile.CallEdge;
import ru.wds.wdl.profile.CallKind;
import ru.wds.wdl.profile.CallProfile;
import ru.wds.wdl.profile.CallProfiler;
import ru.wds.wdl.profile.ProfileReport;
import ru.wds.wdl.profile.Profiler;
import ru.wds.wdl.source.Source;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Профиль на живом запуске: что попадает в него из скрипта и откуда.
 * <p>
 * Здесь проверяются точки съёма — вызов функции, создание экземпляра, чужой код
 * и верхний уровень файла, — а не правила счёта: их проверяет {@code ProfilerTest}.
 */
class ProfileIntegrationTest {

    @Test
    @DisplayName("вызовы функции скрипта считаются поимённо и знают место объявления")
    void functionCalls() {
        CallProfiler profile = Profiler.collecting();

        run("""
                def total(price, count) => price * count
                for (i in 1..5) {
                    total(120, i)
                }
                """, profile);

        CallProfile total = profileOf(profile, "total");
        assertEquals(5, total.calls());
        assertEquals(CallKind.FUNCTION, total.site().kind());
        assertEquals("script.wdl", total.site().file());
        assertEquals(1, total.site().line(), "функция объявлена в первой строке");
    }

    @Test
    @DisplayName("верхний уровень файла — своя запись и корень графа вызовов")
    void scriptIsRoot() {
        CallProfiler profile = Profiler.collecting();

        run("""
                def twice(x) => x * 2
                twice(21)
                """, profile);

        CallProfile script = profileOf(profile, "script.wdl");
        assertEquals(CallKind.SCRIPT, script.site().kind());
        assertEquals(1, script.calls());
        assertTrue(script.totalNanos() >= profileOf(profile, "twice").totalNanos(),
                "время файла включает время того, что файл позвал");

        CallEdge edge = profile.edges().stream()
                .filter(candidate -> candidate.callee().name().equals("twice"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет ребра к twice: " + profile.edges()));
        assertEquals("script.wdl", edge.caller().name());
        assertEquals(1, edge.calls());
    }

    @Test
    @DisplayName("встроенная функция попадает в профиль отдельным видом")
    void builtins() {
        CallProfiler profile = Profiler.collecting();

        run("""
                x = len("привет")
                y = typeof(x)
                """, profile);

        CallProfile len = profileOf(profile, "len");
        assertEquals(CallKind.NATIVE, len.site().kind());
        assertEquals(1, len.calls());
        assertEquals("", len.site().file(), "у встроенной функции нет места в тексте скрипта");
        assertEquals(1, profileOf(profile, "typeof").calls());
    }

    @Test
    @DisplayName("метод значения считается там же, где встроенная функция")
    void valueMembers() {
        CallProfiler profile = Profiler.collecting();

        run("""
                a = [3, 1, 2]
                a.sort()
                a.reverse()
                """, profile);

        assertEquals(1, profileOf(profile, "sort").calls());
        assertEquals(1, profileOf(profile, "reverse").calls());
    }

    @Test
    @DisplayName("создание экземпляра — своя запись, метод класса — обычная функция")
    void classes() {
        CallProfiler profile = Profiler.collecting();

        run("""
                class Point(x, y) {
                    def length() => this.x * this.x + this.y * this.y
                }
                p = new Point(3, 4)
                q = new Point(6, 8)
                p.length()
                """, profile);

        CallProfile point = profileOf(profile, "Point");
        assertEquals(CallKind.CONSTRUCTOR, point.site().kind());
        assertEquals(2, point.calls());
        assertEquals(1, point.site().line());

        CallProfile length = profileOf(profile, "length");
        assertEquals(CallKind.FUNCTION, length.site().kind(), "метод — обычная функция на wdl");
        assertEquals(1, length.calls());
    }

    @Test
    @DisplayName("рекурсия считается вызовами, а её время — один раз")
    void recursion() {
        CallProfiler profile = Profiler.collecting();

        run("""
                def fib(n) {
                    if (n < 2) {
                        return n;
                    }
                    return fib(n - 1) + fib(n - 2);
                }
                fib(10)
                """, profile).finish();

        CallProfile fib = profileOf(profile, "fib");
        assertEquals(177, fib.calls(), "столько входов делает fib(10)");
        assertTrue(fib.totalNanos() <= profile.wall().toNanos(),
                "вложенные входы своё «всего» второй раз не добавляют");
        assertTrue(fib.selfNanos() > 0);
    }

    @Test
    @DisplayName("функция из модуля попадает в профиль со своим файлом")
    void modules() {
        CallProfiler profile = Profiler.collecting();

        run("""
                import lib.math
                add(1, 2)
                add(3, 4)
                """, Map.of("lib/math", "def add(a, b) => a + b\n"), profile);

        CallProfile add = profileOf(profile, "add");
        assertEquals(2, add.calls());
        assertEquals("lib/math.wdl", add.site().file(), "место объявления — файл модуля");
        assertEquals("lib/math", profileOf(profile, "lib/math").site().name(),
                "верхний уровень модуля — тоже запись");
    }

    @Test
    @DisplayName("упавший вызов всё равно попадает в профиль")
    void failedCall() {
        CallProfiler profile = Profiler.collecting();

        assertThrows(WdlError.class, () -> run("""
                def boom() => 1 / 0
                boom()
                """, profile));

        assertEquals(1, profileOf(profile, "boom").calls());
    }

    @Test
    @DisplayName("без приёмника профиля нет и скрипт работает как раньше")
    void offByDefault() {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append);
        Source source = new Source("script.wdl", """
                def twice(x) => x * 2
                println(twice(21))
                """);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);

        new Interpreter().run(Unit.of(source, program), context);

        assertEquals("42" + System.lineSeparator(), printed.toString());
        assertSame(Profiler.off(), context.profiler(), "по умолчанию профиль выключен");
    }

    private static CallProfiler run(String code, CallProfiler profile) {
        return run(code, Map.of(), profile);
    }

    /** Выполняет скрипт с включённым профилем — так же, как это делает консольный запуск. */
    private static CallProfiler run(String code, Map<String, String> modules, CallProfiler profile) {
        ExecutionContext context = ExecutionContext.fresh()
                .withProfiler(profile)
                .withModules(new ModuleUnits(ModuleSource.ofMap(modules)));
        Source source = new Source("script.wdl", code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(Unit.of(source, program), context);
        return profile;
    }

    private static CallProfile profileOf(ProfileReport report, String name) {
        return report.all().stream()
                .filter(profile -> profile.site().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет записи '" + name + "' в " + report.all()));
    }
}
