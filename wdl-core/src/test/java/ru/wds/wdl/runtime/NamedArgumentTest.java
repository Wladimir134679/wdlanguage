package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Именованные аргументы: {@code f(count: 2)}.
 * <p>
 * Здесь проверяется то, что видно из скрипта, — раскладка по именам, пропуск в середине
 * и тексты ошибок. Форма дерева живёт в тестах парсера, как и у остальных конструкций.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NamedArgumentTest {

    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        Resolution resolution = Resolver.resolve(program, diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки резолвера:\n" + diagnostics.renderAll());
        new Interpreter().run(program, resolution, ExecutionContext.fresh(output::append));
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    private static final String GREET =
            "def greet(name, greeting = \"привет\", punct = \"!\") => greeting + \", \" + name + punct\n";

    // --- раскладка -----------------------------------------------------------

    @Test
    @DisplayName("имя ставит аргумент на своё место независимо от порядка")
    void orderDoesNotMatter() {
        assertEquals("здравствуй, мир!",
                printed(GREET + "println(greet(greeting: \"здравствуй\", name: \"мир\"))"));
        assertEquals("здравствуй, мир!",
                printed(GREET + "println(greet(name: \"мир\", greeting: \"здравствуй\"))"));
    }

    @Test
    @DisplayName("позиционные и именованные смешиваются: позиционные идут первыми")
    void positionalThenNamed() {
        assertEquals("привет, мир?", printed(GREET + "println(greet(\"мир\", punct: \"?\"))"));
    }

    @Test
    @DisplayName("пропущенный в середине параметр берёт своё значение по умолчанию")
    void gapUsesDefault() {
        assertEquals("привет, мир?", printed(GREET + "println(greet(name: \"мир\", punct: \"?\"))"));
    }

    @Test
    @DisplayName("значение по умолчанию по-прежнему видит параметры слева")
    void defaultSeesLeftNeighbours() {
        assertEquals("240.0 200", printed("""
                def total(price, count = 1, tax = price * count * 0.2) => price * count + tax
                println(total(100, count: 2), " ", total(price: 100, count: 2, tax: 0))
                """));
    }

    @Test
    @DisplayName("аргументы вычисляются в порядке записи, а не в порядке параметров")
    void evaluatedInWrittenOrder() {
        assertEquals("b a ab", printed("""
                def mark(what) { print(what, " "); return what; }
                def join(a, b) => a + b
                println(join(b: mark("b"), a: mark("a")))
                """));
    }

    @Test
    @DisplayName("именованные работают у анонимной функции и у метода")
    void anonymousAndMethods() {
        // 3 — от анонимной, 12 — от метода: 10 + 1 * 2.
        assertEquals("312", printed("""
                add = def(a, b = 0) => a + b
                class Box(value) {
                    def plus(x, times = 1) => value + x * times
                }
                println(add(b: 2, a: 1), new Box(10).plus(times: 2, x: 1))
                """));
    }

    // --- классы --------------------------------------------------------------

    @Test
    @DisplayName("new принимает имена полей заголовка")
    void newAcceptsNames() {
        assertEquals("(1, 3)", printed("""
                class Point(x, y = 0) {
                    def text() => "(" + x + ", " + y + ")"
                }
                println(new Point(y: 3, x: 1).text())
                """));
    }

    @Test
    @DisplayName("заголовок родителя принимает имена")
    void parentHeaderAcceptsNames() {
        assertEquals("круг: round", printed("""
                class Shape(kind, title = "фигура") {
                    def text() => title + ": " + kind
                }
                class Circle(r) : Shape(title: "круг", kind: "round") {
                }
                println(new Circle(5).text())
                """));
    }

    @Test
    @DisplayName("опечатка в имени параметра родителя — ошибка объявления, а не создания")
    void parentNamesCheckedAtDeclaration() {
        // Скрипт не доходит до 'new': форма родителя известна на связывании класса.
        assertTrue(errorOf("""
                class Shape(kind) {}
                class Circle(r) : Shape(knid: 1) {}
                println("сюда не дойдёт")
                """).getMessage().contains("класс 'Shape' не принимает параметра 'knid'"));
    }

    // --- ошибки --------------------------------------------------------------

    @Test
    @DisplayName("неизвестное имя параметра называет и функцию, и имя")
    void unknownName() {
        assertTrue(errorOf(GREET + "greet(greetng: \"эй\", name: \"мир\")")
                .getMessage().contains("функция 'greet' не принимает параметра 'greetng'"));
    }

    @Test
    @DisplayName("параметр, заданный позиционно, нельзя задать ещё и по имени")
    void positionAlreadyTaken() {
        assertTrue(errorOf(GREET + "greet(\"мир\", name: \"два\")")
                .getMessage().contains("параметр 'name' функции 'greet' уже задан позиционно"));
    }

    @Test
    @DisplayName("непереданный обязательный параметр называется по имени")
    void requiredMissing() {
        assertTrue(errorOf(GREET + "greet(greeting: \"эй\")")
                .getMessage().contains("обязательный параметр 'name' функции 'greet' не передан"));
    }

    @Test
    @DisplayName("встроенная функция без объявленных имён отвечает честным сообщением")
    void builtinWithoutNames() {
        assertTrue(errorOf("println(len(value: [1, 2]))").getMessage()
                .contains("функция 'len' принимает аргументы только по позиции"));
    }

    @Test
    @DisplayName("лишний позиционный аргумент даёт прежнее сообщение о числе")
    void tooManyArguments() {
        assertTrue(errorOf("def f(a) => a\nf(1, 2, b: 3)")
                .getMessage().contains("функция 'f' принимает ровно 1 аргумент"));
    }
}
