package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.BodyStyle;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор классов и типажей: форма заголовка, что допустимо в теле и где допустимы
 * {@code this} и {@code super}.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ClassParserTest {

    private static Program parse(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        return program;
    }

    private static Stmt single(String code) {
        Program program = parse(code);
        assertEquals(1, program.statements().size(), "ожидалась одна инструкция");
        return program.statements().get(0);
    }

    private static ClassDeclStmt classOf(String code) {
        return assertInstanceOf(ClassDeclStmt.class, single(code));
    }

    private static TraitDeclStmt traitOf(String code) {
        return assertInstanceOf(TraitDeclStmt.class, single(code));
    }

    private static String errorOf(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- заголовок -----------------------------------------------------------

    @Test
    @DisplayName("заголовок — это поля: тот же список параметров, что у функции")
    void headerIsFields() {
        ClassDeclStmt item = classOf("class Item(name, price, count = 0)");

        assertEquals("Item", item.name());
        assertEquals(3, item.params().size());
        assertEquals("count", item.params().get(2).name());
        assertTrue(item.params().get(2).hasDefault());
        assertFalse(item.hasParent());
        assertFalse(item.hasConstructor());
        assertTrue(item.methods().isEmpty());
    }

    @Test
    @DisplayName("тела может не быть, и заголовка тоже")
    void bodyAndHeaderAreOptional() {
        assertTrue(classOf("class Marker").params().isEmpty());
        assertEquals(1, traitOf("trait Printable { fun print() => 1 }").methods().size());
        assertTrue(traitOf("trait Printable { fun print() => 1 }").params().isEmpty());
    }

    @Test
    @DisplayName("родитель и типажи: сначала ':', потом 'with'")
    void parentThenTraits() {
        ClassDeclStmt basket = classOf(
                "class Basket(items, limit = 10) : Store(\"склад\") with Printable, Counted");

        assertEquals("Store", basket.parent().name());
        assertEquals(1, basket.parent().arguments().size());
        assertEquals(2, basket.traits().size());
        assertEquals("Printable", basket.traits().get(0).name());
        assertEquals("Counted", basket.traits().get(1).name());
    }

    @Test
    @DisplayName("скобки у родителя необязательны")
    void parentWithoutArguments() {
        assertTrue(classOf("class Circle(r) : Shape").parent().arguments().isEmpty());
    }

    @Test
    @DisplayName("аргументы родителя видят параметры потомка: это обычные выражения")
    void parentArgumentsSeeOwnParams() {
        ClassDeclStmt square = classOf("class Square(side) : Shape(\"сторона \" + side)");
        assertEquals("(+ \"сторона \" side)",
                SExprPrinter.print(square.parent().arguments().get(0)));
    }

    @Test
    @DisplayName("порядок частей заголовка фиксирован")
    void headerOrderIsFixed() {
        assertTrue(errorOf("class A(x) with Loud : Shape()").contains("родитель указывается перед типажами"));
    }

    @Test
    @DisplayName("один типаж дважды — ошибка, а не молчаливое ничего")
    void traitTwice() {
        assertTrue(errorOf("class A(x) with Loud, Loud").contains("подмешан дважды"));
    }

    // --- заголовок типажа ----------------------------------------------------

    @Test
    @DisplayName("в заголовке типажа поле без значения — требование, а не обязательный параметр")
    void traitRequirementAfterDefault() {
        TraitDeclStmt counted = traitOf("trait Counted(count = 0, limit)");

        assertEquals(2, counted.params().size());
        assertTrue(counted.params().get(0).hasDefault());
        assertFalse(counted.params().get(1).hasDefault());
    }

    @Test
    @DisplayName("тот же заголовок у класса — ошибка: там порядок задаёт число аргументов")
    void classHeaderKeepsOrderRule() {
        assertTrue(errorOf("class Basket(count = 0, limit)")
                .contains("не может идти после параметра со значением по умолчанию"));
    }

    @Test
    @DisplayName("значения полей типажа друг друга не видят: они считаются в области типажа")
    void traitDefaultsSeeNothing() {
        assertTrue(errorOf("trait Counted(count = 0, limit = count)")
                .contains("вычисляются в области объявления типажа"));
    }

    @Test
    @DisplayName("у типажа нет ни родителя, ни типажей")
    void traitHasNoDependencies() {
        assertTrue(errorOf("trait Loud : Quiet()").contains("у типажа не бывает"));
        assertTrue(errorOf("trait Loud with Quiet").contains("у типажа не бывает"));
    }

    // --- тело ----------------------------------------------------------------

    @Test
    @DisplayName("в теле класса допустимы только объявления функций")
    void bodyIsDeclarationsOnly() {
        assertTrue(errorOf("class Broken(x) { x = 1 }")
                .contains("допустимы только объявления функций"));
        assertTrue(errorOf("class Broken(x) { println(x) }")
                .contains("допустимы только объявления функций"));
    }

    @Test
    @DisplayName("метод с именем класса — конструктор, и параметров он не принимает")
    void constructorIsMethodNamedLikeClass() {
        ClassDeclStmt box = classOf("class Box(w, h) { fun Box() { this.area = w * h } }");

        assertNotNull(box.constructor());
        assertTrue(box.constructor().params().isEmpty());
        assertTrue(box.methods().isEmpty());
        assertTrue(errorOf("class Box(w, h) { fun Box(a) { } }").contains("конструктор не принимает параметров"));
    }

    @Test
    @DisplayName("фабрика объявляется на своём классе, и this в ней запрещён")
    void factories() {
        ClassDeclStmt user = classOf(
                "class User(name, hash) { fun User.of(name, password) => new User(name, password) }");

        assertEquals(1, user.factories().size());
        assertEquals("of", user.factories().get(0).name());
        assertEquals("User.of", user.factories().get(0).function().title());

        assertTrue(errorOf("class User(name) { fun Other.of() => 1 }")
                .contains("фабрика объявляется на своём классе"));
        assertTrue(errorOf("class User(name) { fun User.of() => this.name }")
                .contains("'this' недопустим внутри фабрики"));
        assertTrue(errorOf("trait Printable { fun Printable.of() => 1 }")
                .contains("у типажа не бывает фабрик"));
    }

    @Test
    @DisplayName("тело члена — только блок или '=>': иначе требование съело бы следующее объявление")
    void memberBodyIsBraceOrArrow() {
        TraitDeclStmt counted = traitOf("trait Counted(limit) { fun report() fun full() => limit }");

        assertEquals(1, counted.requirements().size());
        assertEquals("report", counted.requirements().get(0).name());
        assertEquals(1, counted.methods().size());
        assertEquals("full", counted.methods().get(0).name());
        assertEquals(BodyStyle.ARROW, counted.methods().get(0).style());
    }

    @Test
    @DisplayName("у требования проверяется и число параметров")
    void requirementKeepsParams() {
        TraitDeclStmt trait = traitOf("trait T { fun compare(other, strict = false) }");
        assertEquals(2, trait.requirements().get(0).params().size());
    }

    @Test
    @DisplayName("метод без тела в классе — ошибка: требования бывают только в типаже")
    void classMethodNeedsBody() {
        assertTrue(errorOf("class A(x) { fun report() }").contains("нет тела"));
    }

    @Test
    @DisplayName("два члена с одним именем не лежат: пространство имён одно")
    void duplicateMembers() {
        assertTrue(errorOf("class A(x) { fun text() => 1 fun text() => 2 }").contains("уже объявлен"));
        assertTrue(errorOf("class A(x) { fun A() {} fun A() {} }").contains("уже объявлен"));
    }

    // --- this и super --------------------------------------------------------

    @Test
    @DisplayName("this виден методу и вложенной в него функции")
    void thisInsideMethods() {
        FunctionExpr rename = classOf("class User(name) { fun rename(name) { this.name = name } }")
                .methods().get(0);
        assertEquals("rename", rename.name());

        // Анонимная функция замыкает область метода, а экземпляр — часть этой цепочки
        parse("class B(rate) { fun report() { show = fun() => this.rate; show() } }");
    }

    @Test
    @DisplayName("this вне класса — ошибка разбора, а не выполнения")
    void thisOutsideClass() {
        assertTrue(errorOf("this.x = 1").contains("'this' допустим только внутри класса"));
        assertTrue(errorOf("fun f() => this").contains("'this' допустим только внутри класса"));
    }

    @Test
    @DisplayName("super есть только у класса с родителем")
    void superNeedsParent() {
        parse("class Circle(r) : Shape() { fun text() => super.text() }");
        assertTrue(errorOf("class A(x) { fun text() => super.text() }")
                .contains("нет родителя"));
        assertTrue(errorOf("trait T { fun text() => super.text() }")
                .contains("у типажа нет родителя"));
        assertTrue(errorOf("super.text()").contains("'super' допустим только внутри класса"));
    }

    @Test
    @DisplayName("this и super нельзя присвоить: это имена самого объекта")
    void selfNamesAreNotTargets() {
        assertTrue(errorOf("class A(x) { fun f() { this = 1 } }").contains("нельзя присвоить"));
        assertTrue(errorOf("class A(x) : B() { fun f() { super = 1 } }").contains("нельзя присвоить"));
    }

    // --- инструкция и восстановление -----------------------------------------

    @Test
    @DisplayName("new отдельной строкой — инструкция: конструктор что-то делает")
    void newIsAStatement() {
        assertEquals(2, parse("new User(\"wdeath\", \"секрет\")\nnew User(\"wdeath\", \"секрет\", 33)")
                .statements().size());
    }

    @Test
    @DisplayName("значение по умолчанию видит параметры слева и через new тоже")
    void defaultsThroughNew() {
        parse("fun f(a, b = new Point(a)) => a");
        assertTrue(errorOf("fun f(a = new Point(b), b = 1) => a").contains("связывается позже"));
    }

    @Test
    @DisplayName("после ошибки в теле разбор доходит до следующего объявления")
    void recoveryReachesNextClass() {
        Source source = Source.ofString("class Broken(x) { x = 1 }\nclass Good(y)");
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertEquals(2, program.statements().size(), () -> diagnostics.renderAll());
        assertEquals("Good", assertInstanceOf(ClassDeclStmt.class, program.statements().get(1)).name());
    }

    @Test
    @DisplayName("имя класса обязательно")
    void nameIsRequired() {
        assertTrue(errorOf("class (x)").contains("ожидалось имя класса"));
        assertTrue(errorOf("trait { }").contains("ожидалось имя типажа"));
    }

    @Test
    @DisplayName("класс без родителя и без типажей ничего лишнего в дерево не кладёт")
    void emptyPartsStayEmpty() {
        ClassDeclStmt point = classOf("class Point(x = 0, y = 0) { fun text() => x }");
        assertNull(point.parent());
        assertTrue(point.traits().isEmpty());
        assertTrue(point.factories().isEmpty());
    }
}
