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
            return Unit.of(source, program);
        }
        return Unit.of(source, program);
    }

    private static WdlRuntimeError errorOf(String code, Map<String, String> modules) {
        return assertThrows(WdlRuntimeError.class, () -> run(code, modules));
    }

    private static final Map<String, String> MATH = Map.of("lib/math", """
            const PI = 3
            def add(a, b) => a + b
            class Point(x, y) {
                def sum() => x + y
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
                def total() {
                    import lib.math as m
                    return m.add(3, 4);
                }
                println(total())
                """, MATH));

        WdlRuntimeError error = errorOf("""
                def total() {
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
                """, Map.of("lib/peek", "def show() => secret"));

        assertEquals("переменная 'secret' не определена", error.getMessage());
    }

    // --- однократность -------------------------------------------------------

    @Test
    @DisplayName("модуль выполняется один раз за запуск, сколько бы его ни импортировали")
    void moduleRunsOnce() {
        assertEquals("загрузка" + NL + "готово" + NL + "готово" + NL, run("""
                def once() {
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
                def bump() { count = count + 1 }
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
        Unit unit = Unit.of(source, program);

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
                """, Map.of("lib/bad", "def boom() => 1 / 0"));

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

    // --- изменение модуля ----------------------------------------------------

    /** Переменная, константа и функция, которая переменной пользуется. */
    private static final Map<String, String> CONFIG = Map.of("lib/config", """
            prefix = ">"
            const LIMIT = 10
            def label(text) => prefix + text
            """);

    @Test
    @DisplayName("присваивание через модуль видят его функции")
    void assignmentThroughModuleReachesItsFunctions() {
        assertEquals("!ok" + NL, run("""
                import lib.config as c
                c.prefix = "!"
                println(c.label("ok"))
                """, CONFIG));
    }

    @Test
    @DisplayName("присваивание после развёрнутого импорта тоже доходит до модуля")
    void assignmentAfterPlainImportReachesTheModule() {
        assertEquals("!ok" + NL, run("""
                import lib.config
                prefix = "!"
                println(label("ok"))
                """, CONFIG));
    }

    @Test
    @DisplayName("обе формы импорта — одна ячейка: изменение видно в обе стороны")
    void bothFormsShareOneCell() {
        assertEquals("A|B" + NL, run("""
                import lib.config
                import lib.config as c
                c.prefix = "A"
                first = prefix
                prefix = "B"
                println(first, "|", c.prefix)
                """, CONFIG));
    }

    @Test
    @DisplayName("константа модуля не поддаётся ни через модуль, ни после развёрнутого импорта")
    void constantResistsBothForms() {
        assertEquals("'LIMIT' нельзя присвоить: это константа,"
                        + " её значение задаётся один раз при объявлении",
                errorOf("""
                        import lib.config as c
                        c.LIMIT = 1
                        """, CONFIG).getMessage());

        assertTrue(errorOf("""
                import lib.config
                LIMIT = 1
                """, CONFIG).getMessage().contains("это константа"));
    }

    @Test
    @DisplayName("присваивание несуществующему имени модуля — ошибка, а не новое имя")
    void assignmentDoesNotCreateNames() {
        assertEquals("в модуле 'lib/config' нет имени 'prefx'", errorOf("""
                import lib.config as c
                c.prefx = "!"
                """, CONFIG).getMessage());
    }

    @Test
    @DisplayName("менять можно и функцию, и класс: снаружи разрешено то же, что внутри")
    void functionsAndClassesAreAssignableToo() {
        assertEquals("12|5" + NL, run("""
                import lib.math as m
                m.add = def(a, b) => a * b
                m.Point = 5
                println(m.add(3, 4), "|", m.Point)
                """, MATH));
    }

    @Test
    @DisplayName("составное присваивание через модуль")
    void compoundAssignmentThroughModule() {
        assertEquals("5" + NL, run("""
                import counter as c
                c.count += 5
                println(c.count)
                """, Map.of("counter", "count = 0")));
    }

    // --- реэкспорт -----------------------------------------------------------

    /** Модуль, который сам развернул у себя другой модуль. */
    private static final Map<String, String> REEXPORT = Map.of(
            "lib/base", """
                    x = 1
                    def show() => x
                    """,
            "lib/re", "import base");

    @Test
    @DisplayName("реэкспорт живой: имя, пришедшее в модуль импортом, — та же ячейка")
    void reexportedNameIsTheSameCell() {
        assertEquals("9|9|9" + NL, run("""
                import lib.re as r
                import lib.base as b
                r.x = 9
                println(b.x, "|", b.show(), "|", r.show())
                """, REEXPORT));
    }

    @Test
    @DisplayName("реэкспорт проходит и через развёрнутый импорт")
    void reexportSurvivesPlainImport() {
        assertEquals("7|7" + NL, run("""
                import lib.re
                import lib.base as b
                x = 7
                println(b.x, "|", show())
                """, REEXPORT));
    }

    @Test
    @DisplayName("два модуля, импортировавших третий, видят изменения друг друга")
    void importersSeeEachOther() {
        assertEquals("5" + NL, run("""
                import lib.writer as w
                import lib.reader as r
                w.store(5)
                println(r.load())
                """, Map.of(
                "counter", "count = 0",
                "lib/writer", """
                        import "/counter"
                        def store(value) { count = value }
                        """,
                "lib/reader", """
                        import "/counter"
                        def load() => count
                        """)));
    }

    // --- приоритет имён ------------------------------------------------------

    @Test
    @DisplayName("своё объявление после импорта затеняет имя модуля")
    void ownDeclarationShadowsTheModule() {
        // 'const' не поднимается до выполнения, поэтому объявлен он именно здесь —
        // ниже импорта, и с этой строки имя означает свою константу, а не ячейку модуля.
        assertEquals("own|>" + NL, run("""
                import lib.config
                import lib.config as c
                const prefix = "own"
                println(prefix, "|", c.prefix)
                """, CONFIG));
    }

    @Test
    @DisplayName("импорт вытесняет объявление, поднятое над ним")
    void importDisplacesHoistedDeclaration() {
        // 'def' верхнего уровня помечается до первой строки скрипта, поэтому импорт
        // ниже его — это объявление поверх объявления, и побеждает то, что выполнилось
        // позже. Присваивание после него уходит в модуль.
        assertEquals("!" + NL, run("""
                def prefix() => "own"
                import lib.config
                import lib.config as c
                prefix = "!"
                println(c.prefix)
                """, CONFIG));
    }

    @Test
    @DisplayName("импорт внутри функции: имя исчезает, а изменение модуля остаётся")
    void assignmentInsideFunctionOutlivesTheScope() {
        assertEquals("!" + NL, run("""
                def tune() {
                    import lib.config
                    prefix = "!"
                }
                tune()
                import lib.config as c
                println(c.prefix)
                """, CONFIG));
    }
}
