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
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.source.Source;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Наследование и подмешивание через файлы: {@code class Circle : Shape} с родителем
 * из модуля.
 * <p>
 * Главное правило проверяется здесь же — <b>последовательность</b>: тип из модуля виден
 * только ниже своего {@code import}. Из неё следует и то, что порядок выполнения скрипта
 * не меняется: модулю незачем выполняться раньше своей инструкции.
 */
class ModuleInheritanceTest {

    private static final String NL = System.lineSeparator();

    private static final Map<String, String> SHAPES = Map.of("lib/shapes", """
            println("[lib/shapes загружается]")

            trait Countable {
                def count()
            }

            class Shape(title) {
                def text() => "фигура " + title

                def describe() => text() + ", площадь " + area()

                def area() => 0
            }
            """);

    private static String run(String code, Map<String, String> modules) {
        StringBuilder printed = new StringBuilder();
        ModuleUnits units = new ModuleUnits(ModuleSource.ofMap(modules));
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Unit unit = unitOf(source, diagnostics, units);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());

        new Interpreter().run(unit, ExecutionContext.fresh(printed::append).withModules(units));
        return printed.toString();
    }

    private static Unit unitOf(Source source, Diagnostics diagnostics, ModuleUnits units) {
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        if (diagnostics.hasErrors()) {
            return Unit.of(source, program, Resolution.none());
        }
        return Unit.of(source, program, Resolver.resolve(program, diagnostics));
    }

    /** Первое сообщение резолвера: то, что движок знает о скрипте до первой инструкции. */
    private static String problem(String code, Map<String, String> modules) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        unitOf(source, diagnostics, new ModuleUnits(ModuleSource.ofMap(modules)));
        assertTrue(diagnostics.hasErrors(), "ожидалась ошибка разбора");
        return diagnostics.all().get(0).message();
    }

    /** Сообщение выполнения: связывание класса — работа рантайма, а не разбора. */
    private static String failure(String code, Map<String, String> modules) {
        return org.junit.jupiter.api.Assertions
                .assertThrows(WdlRuntimeError.class, () -> run(code, modules))
                .getMessage();
    }

    // --- наследование --------------------------------------------------------

    @Test
    @DisplayName("развёрнутый импорт: наследование от класса модуля")
    void inheritThroughPlainImport() {
        assertEquals("[lib/shapes загружается]" + NL + "фигура круг, площадь 12" + NL, run("""
                import lib.shapes

                class Circle(radius) : Shape("круг") {
                    def area() => 3 * radius * radius
                }

                println(new Circle(2).describe())
                """, SHAPES));
    }

    @Test
    @DisplayName("именованный импорт: class Circle : m.Shape")
    void inheritThroughAliasedImport() {
        assertEquals("[lib/shapes загружается]" + NL + "фигура круг, площадь 12" + NL, run("""
                import lib.shapes as m

                class Circle(radius) : m.Shape("круг") {
                    def area() => 3 * radius * radius
                }

                println(new Circle(2).describe())
                """, SHAPES));
    }

    @Test
    @DisplayName("трейт из модуля подмешивается через with")
    void mixInTraitFromModule() {
        assertEquals("[lib/shapes загружается]" + NL + "3" + NL, run("""
                import lib.shapes as m

                class Bag(items) with m.Countable {
                    def count() => len(items)
                }

                println(new Bag([1, 2, 3]).count())
                """, SHAPES));
    }

    @Test
    @DisplayName("super уходит в родителя из модуля")
    void superReachesModuleParent() {
        assertEquals("[lib/shapes загружается]" + NL + "фигура круг (уточнённая)" + NL, run("""
                import lib.shapes

                class Circle(radius) : Shape("круг") {
                    def text() => super.text() + " (уточнённая)"
                }

                println(new Circle(2).text())
                """, SHAPES));
    }

    @Test
    @DisplayName("метод родителя из модуля видит переопределение потомка")
    void parentMethodCallsChildOverride() {
        // describe() написан в модуле и зовёт area(); area() переопределён здесь.
        assertEquals("[lib/shapes загружается]" + NL + "фигура квадрат, площадь 25" + NL, run("""
                import lib.shapes

                class Square(side) : Shape("квадрат") {
                    def area() => side * side
                }

                println(new Square(5).describe())
                """, SHAPES));
    }

    @Test
    @DisplayName("is работает через файлы: и по классу, и по трейту")
    void isCrossesFiles() {
        assertEquals("true|true|true" + NL, run("""
                import lib.shapes as m

                class Circle(radius) : m.Shape("круг") with m.Countable {
                    def count() => 1
                }

                c = new Circle(2)
                println(c is Circle, "|", c is m.Shape, "|", c is m.Countable)
                """, SHAPES).lines().skip(1).findFirst().orElseThrow() + NL);
    }

    @Test
    @DisplayName("форма одна: наследники от двух импортов одного модуля — родня")
    void oneShapePerModule() {
        assertEquals("true" + NL, run("""
                import lib.shapes
                import lib.shapes as m

                class Circle(radius) : Shape("круг")

                println(new Circle(1) is m.Shape)
                """, SHAPES).lines().skip(1).findFirst().orElseThrow() + NL);
    }

    // --- последовательность --------------------------------------------------

    @Test
    @DisplayName("класс выше импорта о модуле не знает")
    void typeIsInvisibleAboveItsImport() {
        String message = failure("""
                class Circle(radius) : Shape("круг")
                import lib.shapes
                """, SHAPES);

        assertTrue(message.startsWith("неизвестный класс 'Shape'"), message);
    }

    @Test
    @DisplayName("импорт и наследник в одной функции — работают")
    void inheritInsideFunction() {
        assertEquals("[lib/shapes загружается]" + NL + "фигура точка" + NL, run("""
                def describe() {
                    import lib.shapes
                    class Dot : Shape("точка")
                    return new Dot().text();
                }

                println(describe())
                """, SHAPES));
    }

    @Test
    @DisplayName("импорт в одной функции, наследник в другой — не видно")
    void importDoesNotLeakToAnotherFunction() {
        String message = failure("""
                def load() { import lib.shapes }
                def make() { class Dot : Shape("точка") }
                load()
                make()
                """, SHAPES);

        assertTrue(message.startsWith("неизвестный класс 'Shape'"), message);
    }

    @Test
    @DisplayName("модуль выполняется на своём месте, а не раньше первой строки")
    void moduleRunsWhereItIsWritten() {
        assertEquals("до" + NL + "[lib/shapes загружается]" + NL + "фигура круг" + NL, run("""
                println("до")
                import lib.shapes
                class Circle : Shape("круг")
                println(new Circle().text())
                """, SHAPES));
    }

    @Test
    @DisplayName("наследник от импортированного класса появляется только на своей строке")
    void inheritingClassIsNotHoisted() {
        WdlRuntimeError error = org.junit.jupiter.api.Assertions.assertThrows(WdlRuntimeError.class,
                () -> run("""
                        import lib.shapes
                        println(new Circle().text())
                        class Circle : Shape("круг")
                        """, SHAPES));

        assertEquals("переменная 'Circle' не определена", error.getMessage());
    }

    @Test
    @DisplayName("свой класс по-прежнему помечается заранее: порядок в файле свободен")
    void localClassesKeepFreeOrder() {
        assertEquals("фигура круг" + NL, run("""
                println(new Circle().text())
                class Circle : Shape("круг")
                class Shape(title) { def text() => "фигура " + title }
                """, Map.of()));
    }

    // --- ошибки --------------------------------------------------------------

    @Test
    @DisplayName("требование трейта из модуля проверяется на строке class, а не при new")
    void unmetRequirementFromModule() {
        String message = failure("""
                import lib.shapes as m
                class Bag(items) with m.Countable { }
                """, SHAPES);

        assertTrue(message.contains("не выполняет требование трейта 'Countable'"), message);
        assertTrue(message.contains("нет метода 'count'"), message);
    }

    @Test
    @DisplayName("класс модуля в with — ошибка, как и свой класс на этом месте")
    void classCannotBeMixedIn() {
        String message = failure("""
                import lib.shapes as m
                class Bag(items) with m.Shape { }
                """, SHAPES);

        assertTrue(message.startsWith("'m.Shape' — класс, а не трейт"), message);
    }

    @Test
    @DisplayName("трейт модуля в позиции родителя — ошибка")
    void traitCannotBeParent() {
        String message = failure("""
                import lib.shapes as m
                class Bag(items) : m.Countable { }
                """, SHAPES);

        assertTrue(message.startsWith("'m.Countable' — трейт, а не класс"), message);
    }

    @Test
    @DisplayName("неизвестный алиас: совет проверить сам import")
    void unknownAlias() {
        String message = failure("""
                import lib.shapes as m
                class Circle : other.Shape("круг")
                """, SHAPES);

        assertTrue(message.contains("'import ... as other'"), message);
    }

    @Test
    @DisplayName("развёрнутый импорт не перекрывает свой тип молча")
    void collisionWithLocalType() {
        String message = failure("""
                class Shape(title) { }
                import lib.shapes
                """, SHAPES);

        assertTrue(message.startsWith("модуль приносит тип 'Shape'"), message);
        assertTrue(message.contains("import ... as"), message);
    }

    @Test
    @DisplayName("импорт внутри функции затеняет свой класс, а не спорит с ним")
    void importInsideFunctionShadowsOuterType() {
        // Своя область — свои имена: то же самое сделали бы 'def' или 'const' здесь же.
        assertEquals("[lib/shapes загружается]" + NL + "фигура из модуля|свой" + NL, run("""
                class Shape(title) { def text() => "свой" }

                def fromModule() {
                    import lib.shapes
                    return new Shape("из модуля").text();
                }

                println(fromModule(), "|", new Shape("свой").text())
                """, SHAPES));
    }

    @Test
    @DisplayName("круг в импортах не мешает разбору")
    void circularImportsStillParse() {
        Map<String, String> ring = Map.of(
                "a", "import b\nclass FromA { }",
                "b", "import a\nclass FromB { }");

        // Разбор проходит: круг при разборе ошибкой не считается, иначе два модуля,
        // пользующиеся функциями друг друга, не собрались бы вовсе. Ошибкой он станет
        // при выполнении — там значения без выполнения не бывает (см. ModuleTest).
        Source source = Source.ofString("import a");
        Diagnostics diagnostics = new Diagnostics(source);
        unitOf(source, diagnostics, new ModuleUnits(ModuleSource.ofMap(ring)));

        assertFalse(diagnostics.hasErrors(), () -> diagnostics.renderAll());
    }

    @Test
    @DisplayName("наследоваться от модуля, который ещё выполняется, нельзя")
    void circularImportBreaksInheritance() {
        Map<String, String> ring = Map.of(
                "a", "import b\nclass FromA { }",
                // Классов a здесь ещё нет: a дошёл только до своей первой строки.
                "b", "import a\nclass Child : FromA { }");

        String message = failure("import a", ring);

        assertTrue(message.startsWith("циклический импорт: a → b → a"), message);
    }

    @Test
    @DisplayName("развёрнутый импорт передаётся дальше: модуль отдаёт и то, что развернул сам")
    void spreadImportIsPassedThrough() {
        assertEquals("фигура круг" + NL, run("""
                import lib.re as r
                class Circle : r.Shape("круг")
                println(new Circle().text())
                """, Map.of(
                "lib/re", "import base",
                "lib/base", "class Shape(title) { def text() => \"фигура \" + title }")));
    }
}
