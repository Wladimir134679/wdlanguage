package ru.wds.wdl.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.NativeModules;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Встроенные модули: библиотека на Java, которую скрипт получает через {@code import}.
 * <p>
 * Главное, что здесь проверяется, — что для языка такой модуль неотличим от файла:
 * те же две формы импорта, та же однократность, та же ошибка на опечатке и тот же
 * запрет присваивания. Отличие ровно одно, и оно тоже проверяется: встроенный модуль
 * ищется по имени, а не по пути, поэтому не зависит от каталога импортирующего файла.
 */
class NativeModuleTest {

    private static final String NL = System.lineSeparator();

    /** Библиотека-пример: функция, константа и класс — всё, что кладут в модуль. */
    private static Library math() {
        return new Library() {
            @Override
            public String name() {
                return "sys/math";
            }

            @Override
            public Environment installTo(Environment scope) {
                scope.defineConstant("LIMIT", IntValue.of(10));
                scope.define("twice", BuiltinFunction.of("twice", Arity.exactly(1),
                        (context, arguments, span) ->
                                IntValue.of(((IntValue) arguments.get(0)).value() * 2)));
                scope.define("Counter", NativeClass.named("Counter")
                        .field("start", IntValue.of(0))
                        .method("value", Arity.exactly(0), (self, context, arguments, span) ->
                                self.get("start"))
                        .build());
                return scope;
            }
        };
    }

    private static String run(String code, NativeModules natives) {
        return run(code, natives, Map.of());
    }

    private static String run(String code, NativeModules natives, Map<String, String> files) {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = context(printed, natives, files);
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        Unit unit = Unit.of(source, program);
        new Interpreter().run(unit, context);
        return printed.toString();
    }

    private static ExecutionContext context(StringBuilder printed, NativeModules natives,
                                            Map<String, String> files) {
        return ExecutionContext.fresh(printed::append)
                .withModules(new ModuleUnits(ModuleSource.ofMap(files)))
                .withNativeModules(natives);
    }

    private static WdlRuntimeError errorOf(String code, NativeModules natives) {
        return errorOf(code, natives, Map.of());
    }

    private static WdlRuntimeError errorOf(String code, NativeModules natives,
                                           Map<String, String> files) {
        return assertThrows(WdlRuntimeError.class, () -> run(code, natives, files));
    }

    private static NativeModules registry() {
        return NativeModules.of(Map.of("sys/math", NativeModuleTest::math));
    }

    // --- то же самое, что у модуля-файла -------------------------------------

    @Test
    @DisplayName("именованный импорт: имена библиотеки видны через одно имя")
    void namedImport() {
        assertEquals("10|14|7" + NL, run("""
                import sys.math as m
                println(m.LIMIT, "|", m.twice(7), "|", new m.Counter(7).value())
                """, registry()));
    }

    @Test
    @DisplayName("развёрнутый импорт: имена библиотеки становятся своими")
    void plainImport() {
        assertEquals("10|14" + NL, run("""
                import sys.math
                println(LIMIT, "|", twice(7))
                """, registry()));
    }

    @Test
    @DisplayName("встроенный модуль — это модуль: typeof и печать заголовком")
    void looksLikeModule() {
        assertEquals("module|module sys/math" + NL, run("""
                import sys.math as m
                println(typeof(m), "|", m)
                """, registry()));
    }

    @Test
    @DisplayName("имени, которого в модуле нет, не существует — это ошибка, а не null")
    void unknownMember() {
        assertTrue(errorOf("""
                import sys.math as m
                println(m.twise(2))
                """, registry()).getMessage().contains("нет имени 'twise'"));
    }

    @Test
    @DisplayName("встроенный модуль изменяем так же, как файл, а его константа — нет")
    void assignmentGoesThroughExceptConstants() {
        // Библиотека сама решает, что заморозить: 'const' в installTo работает так же,
        // как в файле модуля, и отдельного «final для встроенных» не понадобилось.
        assertEquals("30" + NL, run("""
                import sys.math as m
                m.twice = def(x) => x * 3
                println(m.twice(10))
                """, registry()));

        assertTrue(errorOf("""
                import sys.math as m
                m.LIMIT = 1
                """, registry()).getMessage().contains("это константа"));
    }

    @Test
    @DisplayName("константа модуля остаётся константой и после развёрнутого импорта")
    void constantStaysConstant() {
        assertTrue(errorOf("""
                import sys.math
                LIMIT = 1
                """, registry()).getMessage().contains("это константа"));
    }

    // --- имя, а не путь -------------------------------------------------------

    @Test
    @DisplayName("имя встроенного модуля не зависит от каталога импортирующего файла")
    void nameDoesNotDependOnDirectory() {
        // Будь это путь, из lib/tools.wdl получился бы ключ lib/sys/math.
        assertEquals("10" + NL, run("import lib.tools", registry(), Map.of("lib/tools", """
                import sys.math as m
                println(m.LIMIT)
                """)));
    }

    @Test
    @DisplayName("явный путь './имя' берёт файл, даже если такое имя занято встроенным")
    void explicitPathTakesFile() {
        assertEquals("файл" + NL, run("""
                import "./sys/math" as m
                println(m.WHO)
                """, registry(), Map.of("sys/math", "const WHO = \"файл\"")));
    }

    @Test
    @DisplayName("нет ни файла, ни встроенного — в ошибке сказано про оба места")
    void missingSaysBoth() {
        String message = errorOf("import sys.nope", registry()).getMessage();
        assertTrue(message.contains("модуль 'sys/nope' не найден"), message);
        assertTrue(message.contains("встроенного модуля 'sys/nope' тоже нет"), message);
    }

    // --- когда зовут фабрику --------------------------------------------------

    @Test
    @DisplayName("фабрика зовётся один раз на запуск, сколько бы импортов ни было")
    void factoryCalledOnce() {
        AtomicInteger calls = new AtomicInteger();
        NativeModules natives = counting(calls);
        assertEquals("10|10" + NL, run("""
                import sys.math as first
                import sys.math as second
                println(first.LIMIT, "|", second.LIMIT)
                """, natives));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("до своей строки import встроенный модуль не готовится вовсе")
    void factoryNotCalledWithoutImport() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals("работаем" + NL, run("""
                def unused() {
                    import sys.math as m
                    return m.LIMIT;
                }
                println("работаем")
                """, counting(calls)));
        assertEquals(0, calls.get());
    }

    private static NativeModules counting(AtomicInteger calls) {
        Supplier<Library> factory = () -> {
            calls.incrementAndGet();
            return math();
        };
        return NativeModules.of(Map.of("sys/math", factory));
    }

    @Test
    @DisplayName("библиотеки нет в classpath — ошибка на строке import, а не падение процесса")
    void missingLibraryReportedAtImport() {
        NativeModules broken = NativeModules.of(Map.of("sys/broken", () -> {
            throw new NoClassDefFoundError("org/example/Json");
        }));
        String message = errorOf("import sys.broken", broken).getMessage();
        assertTrue(message.contains("встроенный модуль 'sys/broken'"), message);
        assertTrue(message.contains("org/example/Json"), message);
    }

    // --- порядок наборов и закрытие ------------------------------------------

    @Test
    @DisplayName("свой набор ставится перед готовым и подменяет модуль")
    void firstWins() {
        NativeModules own = NativeModules.of(Map.of("sys/math", () -> new Library() {
            @Override
            public String name() {
                return "sys/math";
            }

            @Override
            public Environment installTo(Environment scope) {
                scope.defineConstant("LIMIT", IntValue.of(1));
                return scope;
            }
        }));
        assertEquals("1" + NL, run("""
                import sys.math as m
                println(m.LIMIT)
                """, NativeModules.first(own, registry())));
    }

    @Test
    @DisplayName("shutdown закрывает библиотеки в порядке, обратном созданию")
    void shutdownClosesInReverseOrder() {
        List<String> closed = new ArrayList<>();
        NativeModules natives = NativeModules.of(Map.of(
                "sys/first", () -> closing("sys/first", closed),
                "sys/second", () -> closing("sys/second", closed)));

        StringBuilder printed = new StringBuilder();
        ExecutionContext context = context(printed, natives, Map.of());
        Source source = Source.ofString("""
                import sys.first
                import sys.second
                """);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        new Interpreter().run(Unit.of(source, program), context);

        assertEquals(List.of(), closed);
        context.shutdownModules();
        assertEquals(List.of("sys/second", "sys/first"), closed);
    }

    @Test
    @DisplayName("ошибка в close не мешает закрыть остальные")
    void shutdownSurvivesFailure() {
        List<String> closed = new ArrayList<>();
        NativeModules natives = NativeModules.of(Map.of(
                "sys/good", () -> closing("sys/good", closed),
                "sys/bad", () -> new Library() {
                    @Override
                    public String name() {
                        return "sys/bad";
                    }

                    @Override
                    public Environment installTo(Environment scope) {
                        scope.define("MARK", StringValue.of("bad"));
                        return scope;
                    }

                    @Override
                    public void close() {
                        throw new IllegalStateException("не закрылось");
                    }
                }));

        StringBuilder printed = new StringBuilder();
        ExecutionContext context = context(printed, natives, Map.of());
        Source source = Source.ofString("""
                import sys.good
                import sys.bad
                """);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        new Interpreter().run(Unit.of(source, program), context);

        context.shutdownModules();
        assertEquals(List.of("sys/good"), closed);
    }

    private static Library closing(String name, List<String> closed) {
        return new Library() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Environment installTo(Environment scope) {
                scope.define("NAME", StringValue.of(name));
                return scope;
            }

            @Override
            public void close() {
                closed.add(name);
            }
        };
    }
}
