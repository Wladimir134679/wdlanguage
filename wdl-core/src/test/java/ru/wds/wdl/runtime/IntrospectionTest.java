package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интроспекция без рефлексии: что значение рассказывает о себе самому скрипту.
 * <p>
 * Здесь же зафиксирована форма ответов — то, что потом нельзя будет поменять:
 * параметр объектом, «имён нет» отдельно от «параметров нет», класс и трейт
 * значениями, а не именами.
 */
class IntrospectionTest {

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(printed::append));
        return printed.toString().strip();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    // --- функция -------------------------------------------------------------

    @Test
    @DisplayName("функция называет своё имя и свои параметры")
    void functionParams() {
        assertEquals("total", run("def total(price, count) => price * count\nprintln(total.name)"));
        assertEquals("[\"price\", \"count\"]", run("""
                def total(price, count) => price * count
                names = []
                for (p in total.params) {
                    names.push(p.name)
                }
                println(names)
                """));
    }

    @Test
    @DisplayName("параметр — объект, а не строка: имя, обязательность, значение")
    void paramIsAnObject() {
        assertEquals("count false", run("""
                def total(price, count = 1) => price * count
                p = total.params[1]
                println(p.name, " ", p.required)
                """));
        // У параметра с отложенным значением по умолчанию default — честный null:
        // за ним стоит выражение, которое считается в области вызова.
        assertEquals("null", run("def f(x = 1 + 1) => x\nprintln(f.params[0].default)"));
    }

    @Test
    @DisplayName("«имён нет» и «параметров нет» — разные ответы")
    void namesUnknownIsNotEmpty() {
        assertEquals("null", run("println(println.params)"));
        assertEquals("[]", run("def nothing() => 1\nprintln(nothing.params)"));
    }

    @Test
    @DisplayName("арность объектом; у функции с остатком верхней границы нет")
    void arity() {
        assertEquals("1 2", run("""
                def total(price, count = 1) => price * count
                println(total.arity.min, " ", total.arity.max)
                """));
        assertEquals("null args", run("""
                def sum(*args) => 0
                println(sum.arity.max, " ", sum.rest)
                """));
    }

    @Test
    @DisplayName("анонимность видна отдельно от имени")
    void anonymous() {
        assertEquals("true", run("f = def(x) => x\nprintln(f.anonymous)"));
        assertEquals("false", run("def f(x) => x\nprintln(f.anonymous)"));
    }

    @Test
    @DisplayName("декоратор подменяет значение — и это видно; спасает like()")
    void decoratorReplacesSignature() {
        // Обёртка без like() честно рассказывает о себе, а не о цели: спрашивают
        // значение, а значение теперь другое. Параметров у неё нет вовсе — только
        // остаток, — и именно это скрипт и увидит вместо price и count.
        assertEquals("[]", run("""
                def log(meta) => def(*args) => meta.target(*args)
                @[log]
                def total(price, count) => price * count
                println(total.params)
                """));
        // like() делегирует сигнатуру цели — и имена параметров переживают декоратор.
        assertEquals("[\"price\", \"count\"]", run("""
                def log(meta) => like(meta.target, def(*args, **named) => meta.target(*args, **named))
                @[log]
                def total(price, count) => price * count
                names = []
                for (p in total.params) {
                    names.push(p.name)
                }
                println(names)
                """));
    }

    // --- класс и трейт -------------------------------------------------------

    @Test
    @DisplayName("класс отдаёт родителя значением, а не именем")
    void parentIsAValue() {
        assertEquals("Shape true", run("""
                class Shape(name)
                class Circle(name, r) : Shape(name)
                println(Circle.parent.name, " ", Circle.parent is Class)
                """));
        assertEquals("null", run("class Shape(name)\nprintln(Shape.parent)"));
    }

    @Test
    @DisplayName("трейты — тоже значения: их можно вернуть в 'is'")
    void traitsAreValues() {
        assertEquals("true", run("""
                trait Printable { def print() }
                class Note(text) with Printable { def print() { println(text) } }
                mixin = Note.traits[0]
                println(new Note("x") is mixin)
                """));
    }

    @Test
    @DisplayName("состав класса — ключи плоских таблиц, вместе с унаследованным")
    void classComposition() {
        assertEquals("[\"area\", \"title\"]", run("""
                class Shape(name) { def area() => 0 }
                class Circle(name, r) : Shape(name) { def title() => name }
                println(Circle.methods)
                """));
        assertEquals("[\"square\"]", run("""
                class Box(w) { property square => w * w }
                println(Box.properties)
                """));
    }

    @Test
    @DisplayName("заголовок класса — это его поля, и он же список параметров создания")
    void classParams() {
        assertEquals("[\"x\", \"y\"] 1 2", run("""
                class Point(x, y = 0)
                names = []
                for (p in Point.params) {
                    names.push(p.name)
                }
                println(names, " ", Point.arity.min, " ", Point.arity.max)
                """));
    }

    @Test
    @DisplayName("трейт рассказывает и про требования — то, чего у класса нет")
    void traitRequirements() {
        assertEquals("Printable [\"print\"] [\"describe\"]", run("""
                trait Printable {
                    def print()
                    def describe() => "печатаемое"
                }
                println(Printable.name, " ", Printable.requirements, " ", Printable.methods)
                """));
    }

    // --- путь в обход данных -------------------------------------------------

    @Test
    @DisplayName("статика перекрывает член класса, а дескриптор даёт надёжный путь")
    void staticsShadowMembers() {
        assertEquals("своё [\"area\"]", run("""
                class Shape(name) { def area() => 0 }
                Shape.methods = "своё"
                println(Shape.methods, " ", Class.methods(Shape))
                """));
    }

    @Test
    @DisplayName("модуль называет себя и своё содержимое")
    void moduleMembers() {
        // Модуля здесь нет, но правило то же, что у объекта: имя модуля перекрыло бы
        // одноимённый член. Проверяется на встроенном дескрипторе Module.
        assertTrue(errorOf("println(Module.nope)").getMessage().contains("нет члена 'nope'"));
    }
}
