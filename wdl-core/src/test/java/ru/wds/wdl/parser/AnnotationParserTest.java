package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Annotations;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.DecoratedStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор аннотаций {@code @{...}}: куда их можно повесить, как сливаются блоки
 * и что говорится о промахах.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class AnnotationParserTest {

    private static Stmt single(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        assertEquals(1, program.statements().size(), "ожидалась одна инструкция");
        return program.statements().get(0);
    }

    /** Форма аннотаций объявления функции — то, что видит выполнение. */
    private static String annotations(String code) {
        return SExprPrinter.print(
                assertInstanceOf(DefDeclStmt.class, single(code)).function().annotations());
    }

    private static String errorOf(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- где они бывают ------------------------------------------------------

    @Test
    @DisplayName("аннотация лежит в самом объявлении, а не в обёртке над инструкцией")
    void annotationsLiveInDeclaration() {
        DefDeclStmt declared = assertInstanceOf(DefDeclStmt.class,
                single("@{route: \"/users\"} def listUsers() {}"));
        assertEquals("(@ (\"route\" \"/users\"))",
                SExprPrinter.print(declared.function().annotations()));
    }

    @Test
    @DisplayName("аннотации есть у класса, трейта и параметра заголовка")
    void annotationsOnTypes() {
        ClassDeclStmt klass = assertInstanceOf(ClassDeclStmt.class, single("""
                @{table: "users"}
                class User(@{column: "id"} id, name) {}
                """));
        assertEquals("(@ (\"table\" \"users\"))", SExprPrinter.print(klass.annotations()));
        assertEquals("(@ (\"column\" \"id\"))",
                SExprPrinter.print(klass.params().get(0).annotations()));
        assertFalse(klass.params().get(1).annotations().written());

        TraitDeclStmt trait = assertInstanceOf(TraitDeclStmt.class,
                single("@{since: \"0.4\"} trait Printable {}"));
        assertEquals("(@ (\"since\" \"0.4\"))", SExprPrinter.print(trait.annotations()));
    }

    @Test
    @DisplayName("метод, фабрика и свойство класса принимают аннотации")
    void annotationsOnMembers() {
        ClassDeclStmt klass = assertInstanceOf(ClassDeclStmt.class, single("""
                class User(id) {
                    @{transactional: true}
                    def save() {}

                    @{computed: true}
                    property label => id

                    @{factory: true}
                    def User.of(row) => new User(row)
                }
                """));
        assertEquals("(@ (\"transactional\" true))",
                SExprPrinter.print(klass.methods().get(0).annotations()));
        assertEquals("(@ (\"computed\" true))",
                SExprPrinter.print(klass.properties().get(0).annotations()));
        assertEquals("(@ (\"factory\" true))",
                SExprPrinter.print(klass.factories().get(0).function().annotations()));
    }

    @Test
    @DisplayName("аннотация параметра видна в форме заголовка")
    void annotationsOnParams() {
        DefDeclStmt declared = assertInstanceOf(DefDeclStmt.class,
                single("def total(@{min: 0} price, @{min: 1} count = 1) => price * count"));
        assertEquals("(def total ((@ (\"min\" 0)) price) ((@ (\"min\" 1)) (count 1)))",
                SExprPrinter.print(declared.function()));
    }

    // --- слияние блоков ------------------------------------------------------

    @Test
    @DisplayName("несколько блоков подряд сливаются в один список записей")
    void blocksMerge() {
        assertEquals("(@ (\"route\" \"/users\") (\"method\" \"GET\") (\"auth\" true))",
                annotations("""
                        @{route: "/users"}
                        @{method: "GET", auth: true}
                        def listUsers() {}
                        """));
    }

    @Test
    @DisplayName("порядок вперемешку с декораторами роли не играет")
    void mixedWithDecorators() {
        DecoratedStmt first = assertInstanceOf(DecoratedStmt.class, single("""
                @{route: "/users"}
                @[traced]
                @{auth: true}
                synchronized def listUsers() {}
                """));
        DecoratedStmt second = assertInstanceOf(DecoratedStmt.class, single("""
                @[traced]
                @{route: "/users"}
                @{auth: true}
                synchronized def listUsers() {}
                """));
        assertEquals(1, first.decorators().size());
        assertEquals(1, second.decorators().size());
        // Аннотации ушли в объявление, декоратор остался обёрткой — и обе записи
        // собрались в одно и то же дерево.
        Annotations left = assertInstanceOf(DefDeclStmt.class, first.declaration())
                .function().annotations();
        Annotations right = assertInstanceOf(DefDeclStmt.class, second.declaration())
                .function().annotations();
        assertEquals("(@ (\"route\" \"/users\") (\"auth\" true))", SExprPrinter.print(left));
        assertEquals(SExprPrinter.print(left), SExprPrinter.print(right));
    }

    @Test
    @DisplayName("аннотации без декораторов не заводят обёртку")
    void annotationsAloneAreNotDecorated() {
        assertInstanceOf(DefDeclStmt.class, single("@{a: 1} def f() {}"));
        assertInstanceOf(ClassDeclStmt.class, single("@{a: 1} class C() {}"));
        assertInstanceOf(TraitDeclStmt.class, single("@{a: 1} trait T {}"));
    }

    @Test
    @DisplayName("пустая @{} разрешена и даёт пустой список записей")
    void emptyBlockIsAllowed() {
        DefDeclStmt declared = assertInstanceOf(DefDeclStmt.class, single("@{} def f() {}"));
        assertTrue(declared.function().annotations().written());
        assertTrue(declared.function().annotations().entries().isEmpty());
    }

    @Test
    @DisplayName("внутри блока — обычный литерал объекта: вычисляемый ключ и раскрытие")
    void blockIsAnObjectLiteral() {
        assertEquals("(@ (KEY 1) (** base))", annotations("""
                @{(KEY): 1, **base}
                def f() {}
                """));
    }

    // --- ошибки --------------------------------------------------------------

    @Test
    @DisplayName("аннотация вешается на объявление, и сообщение говорит именно о ней")
    void annotationNeedsDeclaration() {
        assertTrue(errorOf("@{max: 100} x = 1").contains("аннотация вешается на объявление"));
        assertTrue(errorOf("@{a: 1} import sys.io").contains("аннотация вешается на объявление"));
        // С декоратором рядом названы оба: советовать «уберите декоратор» тому,
        // кто написал и то и другое, — половина совета.
        assertTrue(errorOf("@{a: 1} @[deco] x = 1")
                .contains("декоратор и аннотация вешаются на объявление"));
    }

    @Test
    @DisplayName("у анонимной функции аннотаций не бывает")
    void anonymousHasNoAnnotations() {
        assertTrue(errorOf("@{a: 1} f = def (x) => x").contains("аннотация вешается на объявление"));
    }

    @Test
    @DisplayName("одинаковый литеральный ключ ловится при разборе, на втором вхождении")
    void duplicateLiteralKey() {
        assertTrue(errorOf("""
                @{route: "/users"}
                @{route: "/other"}
                def f() {}
                """).contains("ключ аннотации \"route\" указан дважды"));
        assertTrue(errorOf("@{a: 1, \"a\": 2} def f() {}")
                .contains("ключ аннотации \"a\" указан дважды"));
        assertTrue(errorOf("@{(1): \"x\", (1): \"y\"} def f() {}")
                .contains("ключ аннотации 1 указан дважды"));
    }

    @Test
    @DisplayName("вычисляемый ключ разбор не ловит: до выполнения он неизвестен")
    void computedKeyPassesParsing() {
        assertEquals("(@ (KEY 1) (KEY 2))", annotations("""
                @{(KEY): 1}
                @{(KEY): 2}
                def f() {}
                """));
    }

    @Test
    @DisplayName("после '@' называются обе формы: и декоратор, и аннотация")
    void atExpectsBothForms() {
        assertTrue(errorOf("@deco def f() {}").contains("после '@' ожидалась '[' или '{'"));
    }

    @Test
    @DisplayName("у остаточного параметра аннотаций не бывает")
    void restTakesNoAnnotations() {
        assertTrue(errorOf("def f(@{a: 1} *args) {}")
                .contains("у остаточного параметра не бывает аннотаций"));
        assertTrue(errorOf("class C(@{a: 1} **named) {}")
                .contains("у остаточного параметра не бывает аннотаций"));
    }

    @Test
    @DisplayName("декоратор на члене типа назван прямо, а не общим «допустимы только функции»")
    void decoratorOnMemberIsNamed() {
        String message = errorOf("""
                class C(x) {
                    @[deco]
                    def m() {}
                }
                """);
        assertTrue(message.contains("декораторы на членах типа пока не поддержаны"), message);
    }
}
