package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Вариативные параметры {@code *args}/{@code **named} и раскрытие {@code f(*array)},
 * {@code f(**object)}.
 * <p>
 * Здесь проверяется то, что видно из скрипта: что попадает в остаток, что получается
 * из раскрытия и какими словами язык отказывает. Форма дерева живёт в тестах парсера.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class VariadicArgumentTest {

    private static ExecutionContext run(String code, StringBuilder output) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        ExecutionContext context = ExecutionContext.fresh(output::append);
        new Interpreter().run(program, context);
        return context;
    }

    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        run(code, output);
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    private static final String INSPECT = """
            def inspect(a, b = 10, *args, **named) => println(a, " ", b, " ", args, " ", named)
            """;

    private static final String TARGET = """
            def target(a, b, defC = 10) => println(a, " ", b, " ", defC)
            """;

    // --- что попадает в остаток ----------------------------------------------

    @Test
    @DisplayName("лишние позиционные идут в *args, неизвестные имена — в **named")
    void restCollectsExtras() {
        assertEquals("1 2 [3, 4] {\"mode\": \"fast\"}",
                printed(INSPECT + "inspect(1, 2, 3, 4, mode: \"fast\")"));
    }

    @Test
    @DisplayName("без остатка контейнеры пусты, а значения по умолчанию работают как раньше")
    void emptyRestIsEmptyContainer() {
        assertEquals("1 10 [] {}", printed(INSPECT + "inspect(1)"));
        assertEquals("1 2 [] {}", printed(INSPECT + "inspect(1, 2)"));
    }

    @Test
    @DisplayName("известное имя достаётся параметру, а не остатку")
    void knownNameGoesToParameter() {
        assertEquals("1 2 [] {\"mode\": \"fast\"}",
                printed(INSPECT + "inspect(1, b: 2, mode: \"fast\")"));
    }

    @Test
    @DisplayName("позиционные сначала занимают обычные параметры, и только хвост — остаток")
    void extrasOnlyAfterParameters() {
        assertEquals("[] [] [3, 4]", printed("""
                def collect(a, b = 10, *args) => args
                println(collect(1), " ", collect(1, 2), " ", collect(1, 2, 3, 4))
                """));
    }

    @Test
    @DisplayName("контейнер остатка свой у каждого вызова")
    void containerIsFreshEachCall() {
        assertEquals("[99] [1]", printed("""
                def collect(*args) => args
                one = collect(1)
                one[0] = 99
                println(one, " ", collect(1))
                """));
        assertEquals("{\"a\": 99} {\"a\": 1}", printed("""
                def collect(**named) => named
                one = collect(a: 1)
                one["a"] = 99
                println(one, " ", collect(a: 1))
                """));
    }

    @Test
    @DisplayName("имя остатка — переменная, а не имя параметра")
    void restNameIsNotParameterName() {
        // Имени 'args' в контракте нет, поэтому оно уходит в **named, а не заменяет *args.
        assertEquals("[] {\"args\": 1}", printed("""
                def collect(*args, **named) => println(args, " ", named)
                collect(args: 1)
                """));
        assertTrue(errorOf("def collect(*args) => args\ncollect(args: 1)").getMessage()
                .contains("функция 'collect' не принимает параметра 'args'"));
    }

    // --- раскрытие -----------------------------------------------------------

    @Test
    @DisplayName("массив раскрывается позиционно, объект — по именам")
    void spreadsExpand() {
        assertEquals("1 2 10 1 2 3", printed(TARGET + """
                values = [1, 2]
                target(*values)
                target(*values, **{defC: 3})
                """));
    }

    @Test
    @DisplayName("контейнеров может быть несколько, и они смешиваются с обычными аргументами")
    void severalContainers() {
        assertEquals("[0, 1, 2] {\"mode\": \"fast\", \"trace\": true}", printed("""
                def collectAll(*args, **named) => println(args, " ", named)
                collectAll(0, *[1], *[2], mode: "fast", **{trace: true})
                """));
    }

    @Test
    @DisplayName("раскрытие работает и у встроенной функции: имён у неё нет, а число есть")
    void spreadIntoBuiltin() {
        assertEquals("12", printed("println(*[1, 2])"));
        assertTrue(errorOf("println(**{a: 1})").getMessage()
                .contains("функция 'println' принимает аргументы только по позиции"));
    }

    @Test
    @DisplayName("раскрытие работает в 'new' и в заголовке родителя")
    void spreadInClasses() {
        assertEquals("(3, 4) круг: round", printed("""
                class Point(x, y) {
                    def text() => "(" + x + ", " + y + ")"
                }
                class Shape(kind, title = "фигура") {
                    def text() => title + ": " + kind
                }
                class Circle(parts) : Shape(*parts) {
                }
                println(new Point(*[3, 4]).text(), " ", new Circle(["round", "круг"]).text())
                """));
    }

    @Test
    @DisplayName("выражение контейнера вычисляется один раз и на своём месте по тексту")
    void evaluatedOnceInWrittenOrder() {
        assertEquals("b a 1 2", printed("""
                def mark(what, value) { print(what, " "); return value; }
                def collect(*args) => println(args[0], " ", args[1])
                collect(*[mark("b", 1)], *[mark("a", 2)])
                """));
    }

    @Test
    @DisplayName("прозрачный проброс: значение по умолчанию считает конечная функция")
    void transparentProxy() {
        assertEquals("1 2 10 1 2 3", printed(TARGET + """
                def proxy(fun, *args, **named) => fun(*args, **named)
                proxy(target, *[1, 2])
                proxy(target, *[1, 2], **{defC: 3})
                """));
    }

    // --- коллизии ------------------------------------------------------------

    @Test
    @DisplayName("раскрытие не отменяет правила: одно имя — одно значение")
    void collisionAfterSpread() {
        assertTrue(errorOf(TARGET + "target(*[1, 2], **{a: 3})").getMessage()
                .contains("параметр 'a' функции 'target' уже задан позиционно"));
        assertTrue(errorOf(TARGET + "target(a: 1, **{a: 2})").getMessage()
                .contains("параметр 'a' функции 'target' уже задан по имени"));
    }

    @Test
    @DisplayName("два раскрытия с общим ключом сталкиваются и в остатке")
    void collisionInsideNamedRest() {
        assertTrue(errorOf("""
                def collect(**named) => named
                collect(**{x: 1}, **{x: 2})
                """).getMessage().contains("аргумент 'x' функции 'collect' передан дважды"));
    }

    @Test
    @DisplayName("сначала коллизия, потом недостача")
    void collisionBeforeMissing() {
        assertTrue(errorOf(TARGET + "target(*[1], **{a: 2})").getMessage()
                .contains("параметр 'a' функции 'target' уже задан позиционно"));
        assertTrue(errorOf(TARGET + "target(*[1], **{defC: 2})").getMessage()
                .contains("обязательный параметр 'b' функции 'target' не передан"));
    }

    // --- что раскрывать нельзя -----------------------------------------------

    @Test
    @DisplayName("раскрыть в позиции можно массив или диапазон, но не строку")
    void spreadNeedsArray() {
        assertTrue(errorOf(TARGET + "target(*\"строка\")").getMessage()
                .contains("раскрыть в аргументы можно массив или диапазон, а здесь строка"));
        // Диапазон раскрывается той же звёздочкой, что и массив: пара символов
        // значит одно и то же и в вызове, и в литерале.
        assertEquals("3", printed("""
                def sum3(a, b, c) => a + b + c
                println(sum3(*0..2))"""));
        assertTrue(errorOf(TARGET + "target(*0.5..2.5)").getMessage()
                .contains("раскрыть можно диапазон с целыми границами"));
    }

    @Test
    @DisplayName("раскрыть по именам можно только объект, и экземпляр класса им не считается")
    void namedSpreadNeedsObject() {
        assertTrue(errorOf(TARGET + "target(**[1, 2])").getMessage()
                .contains("раскрыть по именам можно только объект, а здесь массив"));
        assertTrue(errorOf("""
                class Point(x, y) {}
                def f(x, y) => x + y
                f(**new Point(1, 2))
                """).getMessage().contains("раскрыть по именам можно только объект,"
                + " а здесь экземпляр класса 'Point'"));
    }

    @Test
    @DisplayName("ключ объекта при раскрытии по именам обязан быть строкой")
    void namedSpreadNeedsStringKeys() {
        assertTrue(errorOf(TARGET + "target(**{1: 2})").getMessage()
                .contains("ключ объекта при раскрытии по именам должен быть строкой"));
    }

    // --- без остатка ничего не меняется --------------------------------------

    @Test
    @DisplayName("без остатка лишний аргумент и неизвестное имя остаются ошибками")
    void withoutRestNothingChanged() {
        assertTrue(errorOf("def f(a) => a\nf(*[1, 2])").getMessage()
                .contains("функция 'f' принимает ровно 1 аргумент, а передано 2"));
        assertTrue(errorOf("def f(a) => a\nf(**{b: 1})").getMessage()
                .contains("функция 'f' не принимает параметра 'b'"));
    }

    @Test
    @DisplayName("остаток есть у метода, у фабрики и у анонимной функции")
    void restInMembersAndLambdas() {
        assertEquals("[1, 2] {\"tag\": \"t\"} [3]", printed("""
                class Box(value) {
                    def all(*args) => args
                    def Box.of(*args, **named) => println(args, " ", named)
                }
                Box.of(1, 2, tag: "t")
                print(new Box(0).all(3))
                """));
        assertEquals("[1, 2]", printed("""
                collect = def(*args) => args
                println(collect(1, 2))
                """));
    }

    @Test
    @DisplayName("вызов снаружи, из приложения, тоже раскладывает остаток")
    void externalCallFillsRest() {
        StringBuilder output = new StringBuilder();
        ExecutionContext context = run("""
                def collect(a, *args) => println(a, " ", args)
                """, output);
        Value collect = context.scope().lookup("collect");
        assertInstanceOf(FunctionValue.class, collect);
        // Плотный список от приложения: хвост обязан попасть в остаток, а не пропасть.
        ((FunctionValue) collect).call(context,
                List.of(IntValue.of(1), IntValue.of(2), IntValue.of(3)), Span.point(0));
        assertEquals("1 [2, 3]", output.toString().replace(System.lineSeparator(), " ").trim());
    }
}
