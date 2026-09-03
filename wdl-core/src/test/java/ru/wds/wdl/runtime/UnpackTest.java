package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выполнение распаковки: обе формы, дырки, остатки, порядок вычисления и все ошибки
 * времени выполнения.
 * <p>
 * Ошибки формы сюда не попадают — их ловит разбор, и проверены они в
 * {@code parser.UnpackParserTest}. Здесь остаётся то, что видно только со значениями
 * на руках: длина, наличие ключа и тип источника.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class UnpackTest {

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(printed::append));
        return printed.toString();
    }

    /** Вывод одной строкой: переводы строк заменены пробелами — так ожидания читаются целиком. */
    private static String printed(String code) {
        return run(code).replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    /** Ошибки разбора — для того немногого, о чём язык успевает сказать до выполнения. */
    private static String errorsOf(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- по позициям ---------------------------------------------------------

    @Test
    @DisplayName("массив раскладывается по позициям")
    void positionalArray() {
        assertEquals("10 20", printed("x, y = *[10, 20]\nprintln(x, \" \", y)"));
    }

    @Test
    @DisplayName("экземпляр раскладывается в порядке заголовка класса")
    void positionalInstance() {
        assertEquals("1 2 3", printed("""
                class Vec3(x, y, z) { }
                a, b, c = *new Vec3(1, 2, 3)
                println(a, " ", b, " ", c)
                """));
    }

    @Test
    @DisplayName("наследованные поля идут перед своими — тем же порядком, что и в форме класса")
    void positionalInheritedFields() {
        assertEquals("движ 4", printed("""
                class Shape(kind) { }
                class Square(kind, side) : Shape(kind) { }
                k, s = *new Square("движ", 4)
                println(k, " ", s)
                """));
    }

    @Test
    @DisplayName("дырка занимает позицию, значения не хранит и повторяется сколько угодно")
    void holesTakePositions() {
        assertEquals("1 2 6", printed("""
                n1, n2, _, _, _, n6, *_ = *[1, 2, 3, 4, 5, 6, 7]
                println(n1, " ", n2, " ", n6)
                """));
    }

    @Test
    @DisplayName("остаток '*rest' — новый массив, а не вид на источник")
    void restIsACopy() {
        assertEquals("[2, 3] [1, 2, 3]", printed("""
                items = [1, 2, 3]
                head, *tail = *items
                tail[0] = 99
                tail[0] = 2
                println(tail, " ", items)
                """));
        assertEquals("[]", printed("head, *tail = *[1]\nprintln(tail)"));
    }

    @Test
    @DisplayName("одна цель со звёздочкой — тоже проверка длины")
    void singleTargetChecksLength() {
        assertEquals("42", printed("only = *[42]\nprintln(only)"));
        assertTrue(errorOf("only = *[1, 2]").getMessage().contains("распаковка берёт 1 значение"));
    }

    @Test
    @DisplayName("недостача и излишек — ошибка в обе стороны")
    void lengthIsAContract() {
        assertEquals("распаковка ждёт 3 значения, а массив даёт 2",
                errorOf("x, y, z = *[1, 2]").getMessage());
        assertEquals("распаковка берёт 2 значения, а массив даёт 3;"
                        + " остаток пишется как 'x, y, *rest' или 'x, y, *_'",
                errorOf("x, y = *[1, 2, 3]").getMessage());
        // С остатком лишнее перестаёт быть лишним, а недостача остаётся ошибкой.
        assertEquals("[3]", printed("x, y, *rest = *[1, 2, 3]\nprintln(rest)"));
        assertTrue(errorOf("x, y, *rest = *[1]").getMessage().contains("распаковка ждёт 2 значения"));
    }

    @Test
    @DisplayName("экземпляр в сообщении о длине называется своим классом")
    void lengthMessageNamesTheClass() {
        assertTrue(errorOf("""
                class Vec2(x, y) { }
                a, b, c = *new Vec2(1, 2)
                """).getMessage().contains("а экземпляр класса 'Vec2' даёт 2"));
    }

    @Test
    @DisplayName("обычный объект по позициям не распаковывается — и подсказка называет '**'")
    void objectIsNotPositional() {
        assertEquals("по позициям распаковывается массив или экземпляр класса, а здесь объект;"
                        + " по именам это пишется так: 'x, y = **config'",
                errorOf("config = {x: 1, y: 2}\nx, y = *config").getMessage());
    }

    @Test
    @DisplayName("строка по позициям не распаковывается — символ берётся по номеру")
    void stringIsNotPositional() {
        assertEquals("по позициям распаковывается массив или экземпляр класса, а здесь строка;"
                        + " символ берётся по номеру: text[0]",
                errorOf("text = \"hi\"\nx, y = *text").getMessage());
    }

    @Test
    @DisplayName("диапазон и модуль по позициям тоже не распаковываются")
    void otherSourcesAreRejected() {
        assertTrue(errorOf("x, y = *(1..5)").getMessage()
                .contains("по позициям распаковывается массив или экземпляр класса, а здесь диапазон"));
    }

    // --- по именам -----------------------------------------------------------

    @Test
    @DisplayName("объект раскладывается по именам, а неназванные ключи просто не берутся")
    void namedObject() {
        assertEquals("1 3", printed("""
                x, z = **{x: 1, y: 2, z: 3}
                println(x, " ", z)
                """));
    }

    @Test
    @DisplayName("экземпляр читается и по именам полей")
    void namedInstance() {
        assertEquals("1 3", printed("""
                class Vec3(x, y, z) { }
                p = new Vec3(1, 2, 3)
                x, z = **p
                println(x, " ", z)
                """));
    }

    @Test
    @DisplayName("остаток '**rest' — новый объект из всего неназванного")
    void namedRest() {
        assertEquals("h 80 {\"tls\": true, \"retries\": 3}", printed("""
                config = {host: "h", port: 80, tls: true, retries: 3}
                host, port, **options = **config
                println(host, " ", port, " ", options)
                """));
        assertEquals("{}", printed("x, **rest = **{x: 1}\nprintln(rest)"));
    }

    @Test
    @DisplayName("остаток — копия: запись в него источник не трогает")
    void namedRestIsACopy() {
        assertEquals("2 1", printed("""
                config = {x: 1, y: 2}
                x, **rest = **config
                rest.y = 5
                println(rest.y - 3, " ", config.x)
                """));
    }

    @Test
    @DisplayName("ключ ищется наличием, а не значением: {x: null} распаковывается")
    void keyIsFoundByPresence() {
        assertEquals("null", printed("x = **{x: null}\nprintln(x)"));
    }

    @Test
    @DisplayName("недостающий ключ — ошибка, а лишние — нет")
    void missingKeyIsAnError() {
        assertEquals("в объекте нет ключа 'port'",
                errorOf("config = {host: \"h\"}\nhost, port = **config").getMessage());
        assertTrue(errorOf("""
                class Vec2(x, y) { }
                x, z = **new Vec2(1, 2)
                """).getMessage().contains("у экземпляра класса 'Vec2' нет поля 'z'"));
    }

    @Test
    @DisplayName("по именам распаковывается только объект или экземпляр")
    void namedSourceMustBeAnObject() {
        assertTrue(errorOf("x, y = **[1, 2]").getMessage()
                .contains("по именам распаковывается объект или экземпляр класса, а здесь массив"));
    }

    // --- цели ----------------------------------------------------------------

    @Test
    @DisplayName("цель-обращение работает в обеих формах")
    void accessTargets() {
        assertEquals("7 8", printed("""
                p = {}
                p.x, p.y = *[7, 8]
                println(p.x, " ", p.y)
                """));
        assertEquals("1 2", printed("""
                p = {}
                p.x, p.y = **{x: 1, y: 2, z: 3}
                println(p.x, " ", p.y)
                """));
        assertEquals("[1, 2]", printed("grid = [0, 0]\ngrid[0], grid[1] = *[1, 2]\nprintln(grid)"));
    }

    @Test
    @DisplayName("в '**'-форме ключом служит хвостовое имя, и скобки от точки не отличаются")
    void bracketTargetKeepsItsKey() {
        assertEquals("1", printed("p = {}\np[\"x\"] = **{x: 1}\nprintln(p.x)"));
    }

    // --- порядок вычисления --------------------------------------------------

    @Test
    @DisplayName("правая часть вычисляется целиком до первой записи: 'a, b = b, a' меняет местами")
    void swap() {
        assertEquals("2 1", printed("a = 1\nb = 2\na, b = b, a\nprintln(a, \" \", b)"));
    }

    @Test
    @DisplayName("источник вычисляется ровно один раз, сколько бы имён ни стояло слева")
    void sourceIsEvaluatedOnce() {
        assertEquals("1 раз, 1 2 3", printed("""
                calls = 0
                def make() {
                    calls += 1
                    return [1, 2, 3];
                }
                x, y, z = *make()
                println(calls, " раз, ", x, " ", y, " ", z)
                """));
    }

    @Test
    @DisplayName("источником бывает длинная цепочка вызовов — маркер относится ко всей")
    void sourceIsAWholeChain() {
        assertEquals("4 5", printed("""
                class Point(x, y) { }
                reacts = [{ center: def() => new Point(4, 5) }]
                x, y = *reacts[0].center()
                println(x, " ", y)
                """));
    }

    @Test
    @DisplayName("ошибка на середине не оставляет ни одной записи")
    void nothingIsWrittenOnFailure() {
        assertEquals("0 0", printed("""
                x = 0
                y = 0
                try { x, y = *[1] } catch (e) { }
                println(x, " ", y)
                """));
    }

    // --- попарный список -----------------------------------------------------

    @Test
    @DisplayName("список справа расходится попарно, а раскрытие в нём разворачивается")
    void pairwise() {
        assertEquals("1 2 3", printed("a, b, c = 1, *[2, 3]\nprintln(a, \" \", b, \" \", c)"));
    }

    @Test
    @DisplayName("после раскрытия длина сверяется при выполнении")
    void pairwiseLengthAtRuntime() {
        assertEquals("слева 2 имени, а справа 3 значения",
                errorOf("a, b = 1, *[2, 3]").getMessage());
        assertTrue(errorOf("a, b = *[1], 2, 3").getMessage()
                .contains("слева 2 имени, а справа 3 значения"));
    }

    @Test
    @DisplayName("раскрыть в список значений можно только массив")
    void pairwiseSpreadNeedsAnArray() {
        assertTrue(errorOf("a, b = 1, *{x: 1}").getMessage()
                .contains("раскрыть в список значений можно только массив, а здесь объект"));
    }

    @Test
    @DisplayName("'_ = f()' вызывает и выбрасывает")
    void discardingCall() {
        assertEquals("сделано", printed("""
                def work() {
                    println("сделано")
                    return 1;
                }
                _ = work()
                """));
    }

    // --- дырка в параметрах --------------------------------------------------

    @Test
    @DisplayName("параметр-дырка позицию занимает, но телу не виден")
    void holeParameterIsInvisible() {
        assertEquals("click", printed("""
                def onClick(_, event) => event
                println(onClick("btn", "click"))
                """));
        // Прочитать дырку из тела нельзя вовсе: об этом говорит разбор, а не выполнение.
        assertTrue(errorsOf("def onClick(_, event) => _")
                .contains("'_' — это пропуск, а не переменная"));
    }

    @Test
    @DisplayName("дырке нельзя передать аргумент по имени — даже раскрытием объекта")
    void holeTakesNoName() {
        assertTrue(errorOf("""
                def f(_, b) => b
                f(**{"_": 1, "b": 2})
                """).getMessage().contains("не принимает параметра '_'"));
    }

    @Test
    @DisplayName("дырка в заголовке класса поля не заводит, но аргумент требует")
    void holeInClassHeader() {
        assertEquals("Slot{\"y\": 2}", printed("""
                class Slot(_, y) { }
                println(new Slot(1, 2))
                """));
        assertTrue(errorOf("""
                class Slot(_, y) { }
                new Slot(1)
                """).getMessage().contains("аргумент"));
    }
}
