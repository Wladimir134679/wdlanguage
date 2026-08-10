package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.ModuleKey;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.module.ModuleUnits;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.source.Source;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модули: обе формы импорта, область их действия, однократность выполнения
 * и сообщения об ошибках.
 * <p>
 * Исходники модулей задаются картой, а не файлами: тесту нужен состав модулей,
 * а не файловая система, и {@link ModuleSource} для того и вынесен в интерфейс.
 */
class ModuleTest {

    private static final String NL = System.lineSeparator();

    /** Запускает главный скрипт при заданном наборе модулей и возвращает вывод. */
    private static String run(String code, Map<String, String> modules) {
        StringBuilder printed = new StringBuilder();
        // Реестр один на запуск и достаётся сначала резолверу, потом выполнению —
        // как это делает консольный запуск.
        ModuleUnits units = new ModuleUnits(ModuleSource.ofMap(modules));
        ExecutionContext context = ExecutionContext.fresh(printed::append).withModules(units);
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Unit unit = unitOf(source, diagnostics, units);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(unit, context);
        return printed.toString();
    }

    private static Unit unitOf(Source source, Diagnostics diagnostics, ModuleUnits units) {
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        if (diagnostics.hasErrors()) {
            return Unit.of(source, program, Resolution.none());
        }
        return Unit.of(source, program, Resolver.resolve(program, diagnostics));
    }

    private static WdlRuntimeError errorOf(String code, Map<String, String> modules) {
        return assertThrows(WdlRuntimeError.class, () -> run(code, modules));
    }

    private static final Map<String, String> MATH = Map.of("lib/math", """
            const PI = 3
            fun add(a, b) => a + b
            class Point(x, y) {
                fun sum() => x + y
            }
            """);

    // --- две формы импорта ---------------------------------------------------

    @Test
    @DisplayName("развёрнутый импорт: имена модуля становятся своими")
    void plainImportBringsNames() {
        assertEquals("3|7|30" + NL, run("""
                import lib.math
                println(PI, "|", add(3, 4), "|", new Point(10, 20).sum())
                """, MATH));
    }

    @Test
    @DisplayName("именованный импорт: до имён модуля добираются только через него")
    void aliasedImportKeepsNamesInside() {
        assertEquals("3|7|30" + NL, run("""
                import lib.math as m
                println(m.PI, "|", m.add(3, 4), "|", new m.Point(10, 20).sum())
                """, MATH));

        WdlRuntimeError error = errorOf("""
                import lib.math as m
                println(PI)
                """, MATH);
        assertEquals("переменная 'PI' не определена", error.getMessage());
    }

    @Test
    @DisplayName("модуль — отдельный тип значения")
    void moduleIsItsOwnType() {
        assertEquals("module|module lib/math" + NL, run("""
                import lib.math as m
                println(typeof(m), "|", m)
                """, MATH));
    }

    @Test
    @DisplayName("константа модуля остаётся константой и после развёрнутого импорта")
    void constStaysConst() {
        WdlRuntimeError error = errorOf("""
                import lib.math
                PI = 4
                """, MATH);
        assertTrue(error.getMessage().contains("это константа"), error.getMessage());
    }

    // --- область действия ----------------------------------------------------

    @Test
    @DisplayName("импорт внутри функции живёт только в ней")
    void importInsideFunctionIsLocal() {
        assertEquals("7" + NL, run("""
                fun total() {
                    import lib.math as m
                    return m.add(3, 4);
                }
                println(total())
                """, MATH));

        WdlRuntimeError error = errorOf("""
                fun total() {
                    import lib.math
                    return add(3, 4);
                }
                total()
                println(PI)
                """, MATH);
        assertEquals("переменная 'PI' не определена", error.getMessage());
    }

    @Test
    @DisplayName("модуль не видит переменных того, кто его импортирует")
    void moduleDoesNotSeeImporter() {
        WdlRuntimeError error = errorOf("""
                secret = 42
                import lib.peek as p
                p.show()
                """, Map.of("lib/peek", "fun show() => secret"));

        assertEquals("переменная 'secret' не определена", error.getMessage());
    }

    // --- однократность -------------------------------------------------------

    @Test
    @DisplayName("модуль выполняется один раз за запуск, сколько бы его ни импортировали")
    void moduleRunsOnce() {
        assertEquals("загрузка" + NL + "готово" + NL + "готово" + NL, run("""
                fun once() {
                    import counter as c
                    return "готово";
                }
                println(once())
                println(once())
                """, Map.of("counter", "println(\"загрузка\")")));
    }

    @Test
    @DisplayName("класс из модуля один и тот же при любом числе импортов")
    void classIdentitySurvivesReimport() {
        assertEquals("true" + NL, run("""
                import lib.math
                import lib.math as m
                println(new Point(1, 2) is m.Point)
                """, MATH));
    }

    @Test
    @DisplayName("состояние модуля общее: m.имя видит работу его функций")
    void moduleStateIsLive() {
        assertEquals("0|2" + NL, run("""
                import counter as c
                first = c.count
                c.bump()
                c.bump()
                println(first, "|", c.count)
                """, Map.of("counter", """
                count = 0
                fun bump() { count = count + 1 }
                """)));
    }

    // --- пути ----------------------------------------------------------------

    @Test
    @DisplayName("путь модуля считается от каталога того файла, где написан import")
    void relativePathsStartAtImportingFile() {
        assertEquals("рядом" + NL, run("""
                import lib.outer as o
                println(o.value)
                """, Map.of(
                "lib/outer", "import inner\nvalue = mark",
                "lib/inner", "mark = \"рядом\"")));
    }

    @Test
    @DisplayName("ведущий слэш — путь от корня запуска")
    void leadingSlashMeansRoot() {
        assertEquals("сверху" + NL, run("""
                import lib.outer as o
                println(o.value)
                """, Map.of(
                "lib/outer", "import \"/top\"\nvalue = mark",
                "top", "mark = \"сверху\"")));
    }

    @Test
    @DisplayName("разные записи одного пути дают один ключ")
    void spellingsAgree() {
        // Точку в путь через слэш превратил парсер: 'import lib.math' сюда приходит
        // уже как "lib/math", иначе точка в имени файла была бы неотличима от каталога.
        assertEquals("lib/math", ModuleKey.resolve("lib/math", ""));
        assertEquals("lib/math", ModuleKey.resolve("lib/math.wdl", ""));
        assertEquals("lib/math", ModuleKey.resolve("/lib/math.wdl", ""));
        assertEquals("lib/math", ModuleKey.resolve("math", "lib"));
        assertEquals("lib/math", ModuleKey.resolve("./math.wdl", "lib"));
        assertEquals("math", ModuleKey.resolve("/math", "lib"));
        assertEquals("shared/math", ModuleKey.resolve("../shared/math", "lib"));
    }

    // --- ошибки --------------------------------------------------------------

    @Test
    @DisplayName("модуль не найден — ошибка на строке import, с ключом, по которому искали")
    void missingModule() {
        assertEquals("модуль 'lib/nope' не найден",
                errorOf("import lib.nope", MATH).getMessage());
    }

    @Test
    @DisplayName("без источника модулей import отвечает ошибкой, а не падением")
    void importWithoutModuleSource() {
        // Резолвер без реестра импортов молчит: он не знает, есть ли модуль.
        // Отвечает выполнение — и отвечает ошибкой скрипта, а не NullPointerException.
        Source source = Source.ofString("import lib.math");
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        Unit unit = Unit.of(source, program, Resolver.resolve(program, diagnostics));

        WdlRuntimeError error = assertThrows(WdlRuntimeError.class,
                () -> new Interpreter().run(unit, ExecutionContext.fresh()));
        assertEquals("модуль 'lib/math' не найден", error.getMessage());
    }

    @Test
    @DisplayName("циклический импорт останавливается и показывает цепочку")
    void cyclicImport() {
        WdlRuntimeError error = errorOf("import a", Map.of(
                "a", "import b",
                "b", "import a"));

        assertTrue(error.getMessage().startsWith("циклический импорт: a → b → a"), error.getMessage());
    }

    @Test
    @DisplayName("ошибки разбора модуля показываются его собственными строками")
    void brokenModule() {
        String message = errorOf("import lib.broken", Map.of("lib/broken", "x = = 1")).getMessage();

        assertTrue(message.startsWith("в модуле 'lib/broken' есть ошибки:"), message);
        assertTrue(message.contains("lib/broken.wdl:1:"), message);
    }

    @Test
    @DisplayName("ошибка выполнения внутри модуля знает файл модуля, а не главного скрипта")
    void runtimeErrorKnowsItsFile() {
        WdlRuntimeError error = errorOf("""
                import lib.bad as b
                println("до")
                b.boom()
                """, Map.of("lib/bad", "fun boom() => 1 / 0"));

        assertEquals("деление на ноль", error.getMessage());
        assertNotNull(error.source(), "ошибке нужен файл, иначе её отрисуют по чужому исходнику");
        assertEquals("lib/bad.wdl", error.source().name());
        assertTrue(new Diagnostics(error.source()).render(error.toDiagnostic()).contains("1 / 0"));
    }

    @Test
    @DisplayName("ошибка на верхнем уровне модуля тоже знает свой файл")
    void topLevelErrorKnowsItsFile() {
        WdlRuntimeError error = errorOf("import lib.bad", Map.of("lib/bad", "x = 1 / 0"));

        assertEquals("деление на ноль", error.getMessage());
        assertEquals("lib/bad.wdl", error.source().name());
    }

    @Test
    @DisplayName("несуществующее имя модуля — ошибка сразу, а не null")
    void unknownMemberIsAnError() {
        WdlRuntimeError error = errorOf("""
                import lib.math as m
                println(m.substract(1, 2))
                """, MATH);

        assertEquals("в модуле 'lib/math' нет имени 'substract'", error.getMessage());
    }

    @Test
    @DisplayName("модуль изменять нельзя")
    void moduleIsReadOnly() {
        WdlRuntimeError error = errorOf("""
                import lib.math as m
                m.PI = 4
                """, MATH);

        assertTrue(error.getMessage().startsWith("модуль 'lib/math' изменять нельзя"),
                error.getMessage());
    }
}
