package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интервалы имён: то, к чему ведёт «перейти к объявлению» и что меняет переименование.
 * <p>
 * Проверка везде одна и та же — <b>подстрока исходника по интервалу равна имени</b>.
 * Интервал, покрывающий заодно параметры или значение по умолчанию, такую проверку
 * не пройдёт, а глазами в дампе дерева он неотличим от правильного.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class NameSpanTest {

    private static String code;

    private static Program parse(String source) {
        code = source;
        Source text = Source.ofString(source);
        Diagnostics diagnostics = new Diagnostics(text);
        Program program = Parser.parseProgram(Lexer.tokenize(text, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        return program;
    }

    private static void assertNamed(String expected, Span span) {
        assertNamed(expected, span, "интервал обязан покрывать ровно имя");
    }

    private static void assertNamed(String expected, Span span, String message) {
        assertEquals(expected, code.substring(span.start(), span.end()), message);
    }

    @Test
    @DisplayName("у объявленной функции интервал покрывает ровно её имя")
    void declaredFunction() {
        Program program = parse("def total(price, count = 1) => price * count");

        FunctionExpr function = assertInstanceOf(DefDeclStmt.class, program.statements().get(0)).function();
        assertNamed("total", function.nameSpan());
    }

    @Test
    @DisplayName("у анонимной функции места имени нет, даже когда имя подставлено")
    void anonymousFunction() {
        Program program = parse("handler = def(event) => event");

        AssignStmt assignment = assertInstanceOf(AssignStmt.class, program.statements().get(0));
        FunctionExpr function = assertInstanceOf(FunctionExpr.class, assignment.value());

        assertEquals("handler", function.name(), "имя подставлено для сообщений");
        assertTrue(function.anonymous());
        assertEquals(Span.NONE, function.nameSpan(), "написано оно у переменной, а не у функции");
    }

    @Test
    @DisplayName("у метода, конструктора и фабрики интервалы ведут к написанным именам")
    void classMembers() {
        Program program = parse("""
                class Rect(width, height) {
                    def Rect() { this.area = 0 }
                    def grow(factor) => width * factor
                    def Rect.square(side) => new Rect(side, side)
                }""");

        ClassDeclStmt declaration = assertInstanceOf(ClassDeclStmt.class, program.statements().get(0));
        assertNamed("Rect", declaration.nameSpan());
        assertNamed("Rect", declaration.constructor().nameSpan());
        assertNamed("grow", declaration.methods().get(0).nameSpan());

        ClassDeclStmt.Factory factory = declaration.factories().get(0);
        assertNamed("square", factory.function().nameSpan());
        assertEquals("Rect.square", factory.function().name(), "имя для трассировки — полное");
    }

    @Test
    @DisplayName("у аксессоров свойства интервал указывает на написанное слово")
    void propertyAccessors() {
        Program program = parse("""
                class Rect(width, height) {
                    property area => width * height
                    property scale = 1 {
                        def get() => field
                        def set(value) { field = value }
                    }
                }""");

        ClassDeclStmt declaration = assertInstanceOf(ClassDeclStmt.class, program.statements().get(0));
        PropertyDecl shorthand = declaration.properties().get(0);
        assertNamed("area", shorthand.nameSpan());
        assertNamed("area", shorthand.getter().function().nameSpan());

        PropertyDecl full = declaration.properties().get(1);
        assertNamed("scale", full.nameSpan());
        assertNamed("get", full.getter().function().nameSpan());
        assertNamed("set", full.setter().function().nameSpan());
    }

    @Test
    @DisplayName("у требования трейта своё место имени: функции у него нет")
    void traitRequirement() {
        Program program = parse("""
                trait Printable {
                    def print()
                    def title() => "без имени"
                }""");

        TraitDeclStmt declaration = assertInstanceOf(TraitDeclStmt.class, program.statements().get(0));
        assertNamed("print", declaration.requirements().get(0).nameSpan());
        assertNamed("title", declaration.methods().get(0).nameSpan());
    }

    @Test
    @DisplayName("интервал параметра покрывает имя, а не значение по умолчанию")
    void parameterNames() {
        Program program = parse("def send(message, retries = 3, *rest, **named) => message");

        FunctionExpr function = assertInstanceOf(DefDeclStmt.class, program.statements().get(0)).function();
        assertNamed("message", function.params().get(0).nameSpan());
        assertNamed("retries", function.params().get(1).nameSpan());
        assertNamed("retries = 3", function.params().get(1).span(),
                "а интервал самого параметра покрывает и значение по умолчанию");
        assertNamed("rest", function.rest().span());
        assertNamed("named", function.namedRest().span());
    }

    @Test
    @DisplayName("метка 'extend' адресуется интервалом самой цели")
    void extendLabel() {
        Program program = parse("extend Array { def second() => this[1] }");

        ru.wds.wdl.ast.stmt.ExtendStmt declaration =
                assertInstanceOf(ru.wds.wdl.ast.stmt.ExtendStmt.class, program.statements().get(0));
        assertEquals(declaration.label(),
                code.substring(declaration.target().span().start(), declaration.target().span().end()));
    }
}
