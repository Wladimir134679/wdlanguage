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
 * Аннотации во время выполнения: что лежит в значении, как это читается и чего
 * читатель сделать не может.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AnnotationTest {

    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(output::append));
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    // --- что читается --------------------------------------------------------

    @Test
    @DisplayName("аннотации функции читаются членом annotations")
    void functionAnnotations() {
        assertEquals("/users {\"route\": \"/users\", \"method\": \"GET\"}", printed("""
                @{route: "/users", method: "GET"}
                def listUsers(page = 1) => page
                println(listUsers.annotations["route"], " ", listUsers.annotations)
                """));
    }

    @Test
    @DisplayName("блоки сливаются в один объект в порядке записи")
    void blocksMerge() {
        assertEquals("{\"route\": \"/users\", \"method\": \"GET\", \"auth\": true}", printed("""
                @{route: "/users"}
                @{method: "GET", auth: true}
                def listUsers() {}
                println(listUsers.annotations)
                """));
    }

    @Test
    @DisplayName("значение аннотации — обычное выражение и считается один раз")
    void valuesAreExpressions() {
        assertEquals("1 {\"schema\": \"user\", \"since\": 2}", printed("""
                calls = 0
                def build() { calls += 1; return "user"; }
                @{schema: build(), since: 1 + 1}
                def handler() {}
                println(calls, " ", handler.annotations)
                """));
    }

    @Test
    @DisplayName("аннотации есть у класса, трейта, метода и поля заголовка")
    void annotationsOnTypes() {
        assertEquals("{\"table\": \"users\"} {\"column\": \"id\", \"primary\": true} "
                + "{\"transactional\": true} {\"since\": \"0.4\"}", printed("""
                @{table: "users"}
                class User(@{column: "id", primary: true} id, name) {
                    @{transactional: true}
                    def save() {}
                }
                @{since: "0.4"}
                trait Printable {}
                u = new User(1, "Ann")
                println(User.annotations, " ", User.params[0].annotations, " ",
                        u.save.annotations, " ", Printable.annotations)
                """));
    }

    @Test
    @DisplayName("аннотации параметра функции лежат в его описании")
    void annotationsOnParams() {
        assertEquals("{\"min\": 0} {\"min\": 1} {}", printed("""
                def total(@{min: 0} price, @{min: 1} count = 1, other = 2) => price * count
                println(total.params[0].annotations, " ", total.params[1].annotations, " ",
                        total.params[2].annotations)
                """));
    }

    @Test
    @DisplayName("метод, доставшийся от родителя и от трейта, сохраняет свои аннотации")
    void inheritedMembersKeepAnnotations() {
        assertEquals("{\"base\": true} {\"mixed\": true}", printed("""
                class Base(x) {
                    @{base: true}
                    def report() {}
                }
                trait Mixed {
                    @{mixed: true}
                    def note() {}
                }
                class Child(x) : Base(x) with Mixed {}
                c = new Child(1)
                println(c.report.annotations, " ", c.note.annotations)
                """));
    }

    @Test
    @DisplayName("аннотации переживают декоратор через like()")
    void likeKeepsAnnotations() {
        assertEquals("{\"tag\": \"x\"} 5", printed("""
                def traced(meta) {
                    return like(meta.target, def (*args, **named) => meta.target(*args, **named));
                }
                @{tag: "x"}
                @[traced]
                def wrapped(a) => a
                println(wrapped.annotations, " ", wrapped(5))
                """));
    }

    @Test
    @DisplayName("декоратор видит аннотации своей цели: они посчитаны раньше него")
    void decoratorSeesAnnotations() {
        assertEquals("/users ok", printed("""
                ROUTES = {}
                def route(meta) {
                    ROUTES[meta.target.annotations["path"]] = meta.target
                    return meta.target;
                }
                @{path: "/users"}
                @[route]
                def listUsers() => "ok"
                for (key in ROUTES) println(key, " ", ROUTES[key]())
                """));
    }

    // --- чего нет ------------------------------------------------------------

    @Test
    @DisplayName("у встроенной функции и у ненаписанных annotations — пустой объект, а не null")
    void builtinsHaveEmptyAnnotations() {
        assertEquals("{} {} {} {}", printed("""
                class Plain(x) {}
                trait Bare {}
                println(println.annotations, " ", Plain.annotations, " ", Bare.annotations,
                        " ", Plain.params[0].annotations)
                """));
    }

    @Test
    @DisplayName("annotations — снимок: запись в него объявление не меняет")
    void annotationsAreASnapshot() {
        assertEquals("/users", printed("""
                @{route: "/users"}
                def f() {}
                f.annotations["route"] = "/other"
                println(f.annotations["route"])
                """));
    }

    @Test
    @DisplayName("проверка ключа и перебор — обычные члены объекта, своих не заводим")
    void objectMembersAreEnough() {
        assertEquals("true false route = /users method = GET", printed("""
                @{route: "/users", method: "GET"}
                def f() {}
                println(f.annotations.has("route"), " ", f.annotations.has("nope"))
                for (key in f.annotations) println(key, " = ", f.annotations[key])
                """));
    }

    @Test
    @DisplayName("вычисленный дубликат ключа ловится при выполнении")
    void computedDuplicateKey() {
        assertTrue(errorOf("""
                KEY = "route"
                @{(KEY): 1}
                @{(KEY): 2}
                def f() {}
                """).getMessage().contains("ключ аннотации \"route\" указан дважды"));
    }

    @Test
    @DisplayName("раскрыть в аннотацию можно только объект")
    void spreadNeedsObject() {
        assertEquals("{\"a\": 1, \"b\": 2}", printed("""
                base = {a: 1}
                @{**base, b: 2}
                def f() {}
                println(f.annotations)
                """));
        assertTrue(errorOf("""
                @{**[1, 2]}
                def f() {}
                """).getMessage().contains("раскрыть в аннотации можно только объект"));
    }

    @Test
    @DisplayName("класс в теле функции строится заново — вместе с аннотациями")
    void classInFunctionIsRebuilt() {
        assertEquals("1 2", printed("""
                counter = 0
                def make() {
                    counter += 1
                    @{n: counter}
                    class Local(x) {}
                    return Local;
                }
                println(make().annotations["n"], " ", make().annotations["n"])
                """));
    }
}
