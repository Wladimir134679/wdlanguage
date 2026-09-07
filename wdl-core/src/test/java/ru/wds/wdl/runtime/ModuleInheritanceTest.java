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
        return Unit.of(source, program);
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

        assertTrue(error.getMessage().startsWith("переменная 'Circle' не определена"),
                error.getMessage());
    }

    @Test
    @DisplayName("свой класс живёт по тому же правилу: объявление стоит выше использования")
    void localClassesFollowTheSameRule() {
        // Правило одно на все классы — и на свои, и на пришедшие из модуля:
        // имя существует с той строки, где его завели.
        assertEquals("фигура круг" + NL, run("""
                class Shape(title) { def text() => "фигура " + title }
                class Circle : Shape("круг")
                println(new Circle().text())
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
    @DisplayName("развёрнутый импорт молча затеняет дескриптор типа из корневой области — как и класс прелюдии")
    void collisionWithTypeDescriptorIsSilentAtTopLevel() {
        // Десять дескрипторов (Number, String, ...) занимают имена в корневой области
        // тем же способом, что и классы прелюдии (Exception, ValueError) — но и защита
        // от collisionWithLocalType распространяется на них ровно так же, как на прелюдию:
        // 'checkNotShadowingType' смотрит только СВОЮ область (см. его javadoc), а файл
        // выполняется в области, вложенной под корневую (Interpreter.run(Unit, ...)) —
        // там же, где раньше сидели println и классы прелюдии, теперь сидят дескрипторы.
        // Поэтому безымянный import на верхнем уровне файла их не видит и молча
        // перекрывает — то же самое уже было верно для 'import m', приносящего класс
        // с именем 'Exception'. Это не новая дыра, а точная копия старого поведения.
        assertEquals("5", run("""
                import lib.number
                println(new Number(5).value)
                """, Map.of("lib/number", "class Number(value) { }")).trim());
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
    @DisplayName("именованный импорт передаётся дальше: наследование через два имени")
    void aliasIsPassedThrough() {
        // Модуль модуля бывает: 'import ... as m' внутри файла кладёт значение-модуль
        // в его же члены, и наружу оно уходит вместе со всем остальным. Поэтому
        // в заголовке класса и стоит выражение — цепочке тут неоткуда взять предел.
        assertEquals("фигура круг" + NL, run("""
                import lib.re as r
                class Circle : r.s.Shape("круг")
                println(new Circle().text())
                """, Map.of(
                "lib/re", "import lib.base as s",
                "lib/base", "class Shape(title) { def text() => \"фигура \" + title }")));
    }

    @Test
    @DisplayName("класс модуля достаётся из его же реестра — обычным обращением по ключу")
    void typeFromModuleRegistry() {
        assertEquals("фигура круг|true" + NL, run("""
                import lib.re as r
                class Circle : r.classes["Shape"]("круг")
                println(new Circle().text(), "|", new Circle() is r.classes["Shape"])
                """, Map.of("lib/re", """
                class Shape(title) { def text() => "фигура " + title }
                classes = {"Shape": Shape}
                """)));
    }

    @Test
    @DisplayName("развёрнутый импорт передаётся дальше: модуль отдаёт и то, что развернул сам")
    void spreadImportIsPassedThrough() {
        assertEquals("фигура круг" + NL, run("""
                import lib.re as r
                class Circle : r.Shape("круг")
                println(new Circle().text())
                """, Map.of(
                "lib/re", "import lib.base",
                "lib/base", "class Shape(title) { def text() => \"фигура \" + title }")));
    }
}
