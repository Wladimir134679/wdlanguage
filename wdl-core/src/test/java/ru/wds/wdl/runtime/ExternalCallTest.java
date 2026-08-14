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
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Вызов функции скрипта <b>снаружи</b>: из Java, из чужого потока, после того как
 * файл дочитан.
 * <p>
 * Это проверка обещания {@link Run}: функция — самодостаточное значение. Приложение
 * достаёт её из области видимости, уносит куда угодно и зовёт с собственным
 * {@link CallContext} — а она продолжает видеть свой файл, свои модули, свои формы
 * классов и свои классы ошибок. Всё, что вызывающий решает, — куда печатать.
 */
class ExternalCallTest {

    /** Вызывающий, у которого нет ничего, кроме вывода: так выглядит чужое приложение. */
    private record Host(StringBuilder printed) implements CallContext {
        @Override
        public void write(String text) {
            printed.append(text);
        }
    }

    /** Выполненный скрипт: его собственная область, результат и то, что он напечатал. */
    private record Script(Execution done, StringBuilder printed) {

        Value result() {
            return done.value();
        }

        Value name(String name) {
            Value value = done.scope().scope().lookup(name);
            assertFalse(value == null, () -> "имя '" + name + "' не объявлено скриптом");
            return value;
        }

        FunctionValue function(String name) {
            return assertInstanceOf(FunctionValue.class, name(name));
        }
    }

    private static Script run(String code) {
        return run(code, Map.of());
    }

    private static Script run(String code, Map<String, String> modules) {
        StringBuilder printed = new StringBuilder();
        ModuleUnits units = new ModuleUnits(ModuleSource.ofMap(modules));
        ExecutionContext context = ExecutionContext.fresh(printed::append).withModules(units);
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        Unit unit = Unit.of(source, program, Resolver.resolve(program, diagnostics));
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        // Область файла — вложенная относительно корневой, и приходит она вместе
        // с результатом: только через неё видно то, что скрипт объявил.
        return new Script(new Interpreter().run(unit, context), printed);
    }

    /** Вызов «снаружи»: контекст свой, о запуске ничего не знающий. */
    private static Value call(Script script, String name, Value... arguments) {
        return script.function(name)
                .call(new Host(script.printed()), List.of(arguments), Span.point(0));
    }

    @Test
    @DisplayName("Функция, вызванная снаружи, видит переменную своего файла")
    void seesEnclosingVariable() {
        Script script = run("""
                greeting = "привет"
                def greet(who) => greeting + ", " + who
                """);

        assertEquals("привет, мир", call(script, "greet", StringValue.of("мир")).display());
    }

    @Test
    @DisplayName("Функция видит изменения переменной, случившиеся после её объявления")
    void seesLaterChanges() {
        Script script = run("""
                counter = 0
                def bump() { counter = counter + 1; return counter; }
                bump()
                """);

        // Замыкание — ссылка на область, а не снимок: снаружи считается то же, что внутри.
        assertEquals("2", call(script, "bump").display());
        assertEquals("3", call(script, "bump").display());
    }

    @Test
    @DisplayName("Метод, вызванный снаружи, видит this и поля своего экземпляра")
    void methodKeepsInstance() {
        Script script = run("""
                class Counter(value) {
                    def bump(by) { this.value = value + by; return value; }
                }
                counter = new Counter(10)
                """);

        InstanceObjectValue counter = assertInstanceOf(InstanceObjectValue.class,
                script.name("counter"));
        // Ровно то, что делает библиотека с реализованным трейтом: достаёт метод
        // у класса и зовёт его как обычное значение-функцию.
        FunctionValue bump = counter.owner().method(counter, "bump");

        assertEquals("15", bump.call(new Host(script.printed()),
                List.of(ru.wds.wdl.value.types.IntValue.of(5)), Span.point(0)).display());
        assertEquals("15", counter.get("value").display());
    }

    @Test
    @DisplayName("Функция, вызванная снаружи, печатает туда, куда просит вызывающий")
    void printsWhereCalled() {
        Script script = run("""
                def announce(text) => println("из скрипта: ", text)
                """);
        StringBuilder elsewhere = new StringBuilder();

        script.function("announce")
                .call(new Host(elsewhere), List.of(StringValue.of("ага")), Span.point(0));

        assertEquals("", script.printed().toString(), "вывод запуска трогать не должны");
        assertEquals("из скрипта: ага" + System.lineSeparator(), elsewhere.toString());
    }

    @Test
    @DisplayName("Функция, вызванная снаружи, сохраняет доступ к своим модулям")
    void keepsModules() {
        Script script = run("""
                import "./lib" as lib
                def compute(x) => lib.double(x)
                """, Map.of("lib", "def double(x) => x * 2"));

        // Это и есть та дыра, которую закрыл Run: раньше вызванная снаружи функция
        // начинала выполнение без модулей запуска, и 'lib' здесь бы не нашёлся.
        assertEquals("42", call(script, "compute", ru.wds.wdl.value.types.IntValue.of(21)).display());
    }

    @Test
    @DisplayName("Функция, вызванная снаружи, ловит ошибки движка своими классами")
    void keepsErrorClasses() {
        Script script = run("""
                def safe(items) {
                    try {
                        return items[10];
                    } catch (e is IndexError) {
                        return "поймал: " + e.kind;
                    }
                }
                """);

        Value caught = call(script, "safe", ru.wds.wdl.value.types.ArrayValue.of());
        assertEquals("поймал: IndexError", caught.display());
    }

    @Test
    @DisplayName("Класс из вызванной снаружи функции — тот же класс, что объявлен в файле")
    void keepsClassShapes() {
        Script script = run("""
                class Point(x, y)
                def make() => new Point(1, 2)
                def check(p) => p is Point
                """);

        Value made = call(script, "make");
        // Формы классов принадлежат запуску: сделанное снаружи должно узнаваться
        // тем же 'is', что и сделанное внутри.
        assertEquals("true", call(script, "check", made).display());
    }

    @Test
    @DisplayName("Результат файла — значение его последней инструкции-выражения")
    void fileReturnsLastExpression() {
        assertEquals("360", run("""
                def total(price, count) => price * count
                total(120, 3)
                """).result().display());
    }

    @Test
    @DisplayName("Файл без выражения в конце возвращает null")
    void fileWithoutExpressionReturnsNull() {
        assertEquals("null", run("""
                def total(price, count) => price * count
                """).result().display());
    }

    @Test
    @DisplayName("Один и тот же запуск виден из вызовов как одно состояние")
    void oneRunOneState() {
        Script script = run("""
                import "./lib" as lib
                def touch() => lib.mark()
                """, Map.of("lib", """
                calls = 0
                def mark() { calls = calls + 1; return calls; }
                """));

        // Модуль выполняется один раз за запуск — и внешний вызов это правило не обходит.
        assertEquals("1", call(script, "touch").display());
        assertEquals("2", call(script, "touch").display());
    }

    @Test
    @DisplayName("Вызовы из нескольких потоков не мешают друг другу")
    void survivesConcurrentCallers() throws Exception {
        Script script = run("""
                calls = 0
                def bump() { calls = calls + 1; return calls; }
                """);
        int threads = 8;
        int perThread = 200;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                work.add(() -> {
                    for (int call = 0; call < perThread; call++) {
                        call(script, "bump");
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

        // Замок запуска сериализует внешние входы, поэтому ни один инкремент не потерян:
        // после 1600 вызовов следующий обязан дать ровно 1601. Без замка счётчик
        // оказался бы меньше — а HashMap области мог бы и испортиться.
        assertEquals(String.valueOf(threads * perThread + 1), call(script, "bump").display(),
                "потерянный инкремент означает гонку внутри запуска");
    }

    @Test
    @DisplayName("Значение-функция остаётся тем же значением, куда бы его ни передали")
    void functionIsAPlainValue() {
        Script script = run("""
                def handler() => "ок"
                """);

        Value first = script.name("handler");
        Value second = script.name("handler");
        // Никакой обёртки, никакого «экспорта»: из области достаётся то же значение,
        // и сравнивается оно по ссылке, как любая функция в языке.
        assertSame(first, second);
    }
}
