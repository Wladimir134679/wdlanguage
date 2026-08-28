package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выполнение программ целиком: инструкции, присваивание, встроенные функции.
 * Вывод собирается в строку — тому и нужен {@link Output} как отдельная зависимость.
 */
class ProgramTest {

    /** Запускает скрипт и возвращает всё, что тот напечатал. */
    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        run(code, ExecutionContext.fresh(printed::append));
        return printed.toString();
    }

    private static void run(String code, ExecutionContext context) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, context);
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    /** Строки вывода без завершающих переводов — сравнивать удобнее. */
    private static String[] lines(String code) {
        return run(code).split(System.lineSeparator(), -1);
    }

    // --- println -------------------------------------------------------------

    @Test
    @DisplayName("println печатает аргументы подряд и переводит строку")
    void printlnBasics() {
        assertEquals("итого = 5" + System.lineSeparator(), run("println(\"итого = \", 5)"));
        assertEquals(System.lineSeparator(), run("println()"));
        assertEquals("aб1" + System.lineSeparator(), run("println(\"a\", \"б\", 1)"));
    }

    @Test
    @DisplayName("println печатает значения в пользовательском виде, без кавычек")
    void printlnUsesDisplayForm() {
        assertEquals("привет" + System.lineSeparator(), run("println(\"привет\")"));
        assertEquals("[1, \"два\"]" + System.lineSeparator(), run("println([1, \"два\"])"));
        assertEquals("null" + System.lineSeparator(), run("println(null)"));
    }

    @Test
    @DisplayName("print не переводит строку")
    void printWithoutNewline() {
        assertEquals("12", run("print(1)\nprint(2)"));
    }

    @Test
    @DisplayName("вывод — зависимость, а не System.out: движок по умолчанию молчит")
    void defaultOutputIsSilent() {
        // Ничего не печатается и ничего не падает: вывод по умолчанию уходит в никуда.
        run("println(\"этого никто не увидит\")", ExecutionContext.fresh());
    }

    @Test
    @DisplayName("typeof и len отвечают о значении")
    void introspection() {
        assertEquals("number", lines("println(typeof(1))")[0]);
        assertEquals("string", lines("println(typeof(\"a\"))")[0]);
        assertEquals("function", lines("println(typeof(println))")[0]);
        assertEquals("3", lines("println(len([1, 2, 3]))")[0]);
        assertEquals("6", lines("println(len(\"привет\"))")[0]);
    }

    // --- дескрипторы типов -----------------------------------------------------

    @Test
    @DisplayName("typeof возвращает дескриптор, а не строку: печать при этом не меняется")
    void typeofReturnsDescriptorNotString() {
        // Тот же вывод, что был у строки: display() дескриптора равен id() типа.
        assertEquals("number", lines("println(typeof(1))")[0]);
        assertEquals("string", lines("println(typeof(\"a\"))")[0]);
        assertEquals("function", lines("println(typeof(println))")[0]);
    }

    @Test
    @DisplayName("typeof(x) == Тип — то же самое, что typeof(x) == typeof(y) для другого y")
    void typeofComparesByDescriptor() {
        assertEquals("true", lines("println(typeof(1) == Number)")[0]);
        assertEquals("true", lines("println(typeof(1) == typeof(2))")[0]);
        assertEquals("true", lines("println(typeof(1) != String)")[0]);
    }

    @Test
    @DisplayName("дескриптором нельзя создать экземпляр")
    void typeDescriptorCannotBeInstantiated() {
        assertTrue(errorOf("new Number()").getMessage()
                .contains("'Number' — тип, а не класс: типом нельзя создать экземпляр"));
    }

    @Test
    @DisplayName("в дескриптор типа нельзя писать: он общий на весь процесс")
    void typeDescriptorRejectsWrite() {
        assertTrue(errorOf("Number.x = 1").getMessage()
                .contains("в тип 'Number' нельзя записать: дескриптор типа неизменяем"));
    }

    @Test
    @DisplayName("дескриптор — обычное имя корневой области: перекрыть его можно")
    void typeDescriptorCanBeShadowed() {
        // 'Number = 5' ломает только свой скрипт — оператор typeof и is других имён
        // не касается, ровно как перекрытие println не портит print.
        assertEquals("5 string", lines("""
                Number = 5
                println(Number, " ", typeof("a"))
                """)[0]);
    }

    @Test
    @DisplayName("typeof(x) == Класс и x is Класс — разные вопросы: у типов нет иерархии, у классов есть")
    void typeofAndIsDivergeForClasses() {
        assertEquals("false true", lines("""
                class Point(x, y)
                p = new Point(1, 2)
                println(typeof(p) == Point, " ", p is Point)
                """)[0]);
    }

    // --- переменные ----------------------------------------------------------

    @Test
    @DisplayName("присваивание заводит переменную, следующая инструкция её видит")
    void variablesLiveAcrossStatements() {
        assertEquals("6", lines("x = 2\ny = x * 3\nprintln(y)")[0]);
    }

    @Test
    @DisplayName("переменную можно перезаписать, в том числе значением другого типа")
    void reassignment() {
        assertEquals("строка", lines("x = 1\nx = \"строка\"\nprintln(x)")[0]);
    }

    @Test
    @DisplayName("составное присваивание применяет операцию к старому значению")
    void compoundAssignment() {
        assertEquals("7", lines("x = 5\nx += 2\nprintln(x)")[0]);
        assertEquals("10", lines("x = 5\nx *= 2\nprintln(x)")[0]);
        assertEquals("итого: 5", lines("s = \"итого: \"\ns += 5\nprintln(s)")[0]);
        assertEquals("3", lines("m = 0b0110\nm >>= 1\nprintln(m)")[0]);
    }

    @Test
    @DisplayName("составное присваивание требует, чтобы переменная уже существовала")
    void compoundAssignmentNeedsValue() {
        assertTrue(errorOf("новая += 1").getMessage().contains("не определена"));
    }

    // --- константы -----------------------------------------------------------

    @Test
    @DisplayName("константа читается как обычная переменная")
    void constantIsReadLikeAVariable() {
        assertEquals("30", lines("const LIMIT = 10\nprintln(LIMIT * 3)")[0]);
    }

    @Test
    @DisplayName("константе нельзя присвоить заново")
    void constantCannotBeReassigned() {
        assertTrue(errorOf("const LIMIT = 10\nLIMIT = 20").getMessage().contains("это константа"));
    }

    @Test
    @DisplayName("составное присваивание константе запрещено так же, как простое")
    void constantRejectsCompoundAssignment() {
        assertTrue(errorOf("const LIMIT = 10\nLIMIT += 1").getMessage().contains("это константа"));
    }

    @Test
    @DisplayName("константа замораживает имя, а не содержимое")
    void constantFreezesNameNotValue() {
        assertEquals("[5, 2]", lines("const items = [1, 2]\nitems[0] = 5\nprintln(items)")[0]);
        assertTrue(errorOf("const items = [1, 2]\nitems = [3]").getMessage().contains("это константа"));
    }

    @Test
    @DisplayName("присваивание из вложенной области до константы тоже не доходит")
    void constantIsProtectedFromNestedScope() {
        assertTrue(errorOf("const LIMIT = 1\n{ LIMIT = 2 }").getMessage().contains("это константа"));
        assertTrue(errorOf("const LIMIT = 1\ndef bump() { LIMIT = 2 }\nbump()")
                .getMessage().contains("это константа"));
    }

    @Test
    @DisplayName("вложенная область заводит свою константу, внешняя не страдает")
    void constantCanBeShadowed() {
        String[] printed = lines("""
                const rate = 1
                {
                    const rate = 2
                    println(rate)
                }
                println(rate)
                """);

        assertEquals("2", printed[0]);
        assertEquals("1", printed[1]);
    }

    @Test
    @DisplayName("две константы с одним именем в одной области — ошибка")
    void constantCannotBeDeclaredTwiceHere() {
        assertTrue(errorOf("const LIMIT = 1\nconst LIMIT = 2")
                .getMessage().contains("уже есть константа"));
    }

    @Test
    @DisplayName("объявление функции не перекрывает константу, даже помеченное до выполнения")
    void functionDeclarationDoesNotOverrideConstant() {
        assertTrue(errorOf("const total = 1\ndef total() => 2")
                .getMessage().contains("уже есть константа"));
    }

    @Test
    @DisplayName("константа не помечается до выполнения: выше объявления её ещё нет")
    void constantIsNotHoisted() {
        assertTrue(errorOf("def show() => LIMIT\nprintln(show())\nconst LIMIT = 7")
                .getMessage().contains("не определена"));
    }

    @Test
    @DisplayName("функция видит константу ниже по тексту, если вызвана после объявления")
    void functionSeesConstantDeclaredBelow() {
        assertEquals("7", lines("def show() => LIMIT\nconst LIMIT = 7\nprintln(show())")[0]);
    }

    @Test
    @DisplayName("константа живёт в области, а не в запуске: второй скрипт её не перепишет")
    void constantSurvivesBetweenRuns() {
        ExecutionContext context = ExecutionContext.of((Scope) Builtins.installTo(Scope.root()));
        run("const LIMIT = 1", context);

        WdlRuntimeError error = assertThrows(WdlRuntimeError.class, () -> run("LIMIT = 2", context));
        assertTrue(error.getMessage().contains("это константа"), error.getMessage());
    }

    // --- запись в контейнеры -------------------------------------------------

    @Test
    @DisplayName("запись в массив и объект — то же обращение, что и чтение")
    void writeIntoContainers() {
        assertEquals("9", lines("a = [1, 2]\na[0] = 9\nprintln(a[0])")[0]);
        assertEquals("синий", lines("o = {}\no.цвет = \"синий\"\nprintln(o.цвет)")[0]);
        assertEquals("синий", lines("o = {}\no[\"цвет\"] = \"синий\"\nprintln(o.цвет)")[0]);
        assertEquals("{\"x\": 15}", lines("т = {x: 10}\nт.x += 5\nprintln(т)")[0]);
    }

    @Test
    @DisplayName("запись по цепочке доходит до нужного места")
    void writeIntoNestedContainers() {
        assertEquals("болт", lines("""
                данные = {строки: [{имя: "гайка"}]}
                данные.строки[0].имя = "болт"
                println(данные.строки[0].имя)
                """)[0]);
    }

    @Test
    @DisplayName("место записи вычисляется один раз, даже у составного присваивания")
    void placeIsEvaluatedOnce() {
        // Счётчик вызовов встроенной функции: 'ключ()' обязан вызваться ровно однажды.
        int[] calls = {0};
        Scope scope = (Scope) Builtins.installTo(Scope.root());
        scope.define("ключ", BuiltinFunction.of("ключ", Arity.exactly(0), (context, arguments, span) -> {
            calls[0]++;
            return IntValue.ZERO;
        }));

        run("a = [10]\na[ключ()] += 5", ExecutionContext.of(scope));

        assertEquals(1, calls[0], "функция в индексе вызвана лишний раз");
    }

    @Test
    @DisplayName("строку по индексу изменить нельзя")
    void stringsAreImmutable() {
        assertTrue(errorOf("s = \"абв\"\ns[0] = \"я\"").getMessage().contains("неизменяемы"));
    }

    // --- функции как значения ------------------------------------------------

    @Test
    @DisplayName("функция — обычное значение: её можно положить в переменную и вызвать")
    void functionIsAValue() {
        assertEquals("привет", lines("напечатать = println\nнапечатать(\"привет\")")[0]);
        assertEquals("2", lines("вызовы = {печать: println, счёт: 2}\nвызовы.печать(вызовы.счёт)")[0]);
    }

    @Test
    @DisplayName("пространство имён одно: println перекрывается обычным присваиванием")
    void oneNamespace() {
        assertTrue(errorOf("println = 5\nprintln(\"уже не функция\")")
                .getMessage().contains("вызвать можно только функцию"));
    }

    @Test
    @DisplayName("функция печатается так, чтобы её было видно в отладке")
    void functionDisplay() {
        assertTrue(lines("println(println)")[0].startsWith("def println"));
    }

    // --- ошибки --------------------------------------------------------------

    @Test
    @DisplayName("вызвать не функцию нельзя")
    void callingNonFunction() {
        assertTrue(errorOf("x = 5\nx()").getMessage().contains("вызвать можно только функцию"));
        assertTrue(errorOf("\"строка\"()").getMessage().contains("строка"));
    }

    @Test
    @DisplayName("число аргументов проверяется до входа в функцию")
    void arityIsChecked() {
        String message = errorOf("typeof()").getMessage();
        assertTrue(message.contains("'typeof'"), message);
        assertTrue(message.contains("ровно 1"), message);
        assertTrue(message.contains("передано 0"), message);
    }

    @Test
    @DisplayName("ошибка выполнения знает место в скрипте")
    void runtimeErrorHasPlace() {
        WdlRuntimeError error = errorOf("x = 1\ny = x / 0");

        assertFalse(error.span().isNone());
        assertEquals(NullValue.NULL.type(), NullValue.NULL.type()); // тип null существует и стабилен
        assertTrue(error.span().start() > 5, "ошибка должна указывать на вторую строку");
    }

    @Test
    @DisplayName("выполнение останавливается на первой ошибке")
    void executionStopsAtFirstError() {
        StringBuilder printed = new StringBuilder();
        ExecutionContext context = ExecutionContext.fresh(printed::append);

        assertThrows(WdlRuntimeError.class, () ->
                run("println(\"до\")\nx = 1 / 0\nprintln(\"после\")", context));

        assertEquals("до" + System.lineSeparator(), printed.toString());
    }
}
