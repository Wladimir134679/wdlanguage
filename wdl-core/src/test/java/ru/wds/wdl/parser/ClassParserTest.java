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
 * Разбор классов и трейтов: форма заголовка, что допустимо в теле и где допустимы
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
        assertEquals(1, traitOf("trait Printable { def print() => 1 }").methods().size());
        assertTrue(traitOf("trait Printable { def print() => 1 }").params().isEmpty());
    }

    @Test
    @DisplayName("родитель и трейты: сначала ':', потом 'with'")
    void parentThenTraits() {
        ClassDeclStmt basket = classOf(
                "class Basket(items, limit = 10) : Store(\"склад\") with Printable, Counted");

        assertEquals("Store", SExprPrinter.print(basket.parent().type()));
        assertEquals(1, basket.parent().arguments().size());
        assertEquals(2, basket.traits().size());
        assertEquals("Printable", SExprPrinter.print(basket.traits().get(0).type()));
        assertEquals("Counted", SExprPrinter.print(basket.traits().get(1).type()));
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
    @DisplayName("аргументы родителя можно назвать по именам")
    void parentArgumentsCanBeNamed() {
        ClassDeclStmt circle = classOf("class Circle(r) : Shape(title: \"круг\", kind: \"round\")");
        assertEquals(2, circle.parent().arguments().size());
        assertEquals("title", circle.parent().arguments().get(0).name());
        assertEquals("\"круг\"", SExprPrinter.print(circle.parent().arguments().get(0)));
        assertEquals("kind", circle.parent().arguments().get(1).name());
    }

    @Test
    @DisplayName("порядок частей заголовка фиксирован")
    void headerOrderIsFixed() {
        assertTrue(errorOf("class A(x) with Loud : Shape()").contains("родитель указывается перед трейтами"));
    }

    @Test
    @DisplayName("один трейт дважды — ошибка, а не молчаливое ничего")
    void traitTwice() {
        assertTrue(errorOf("class A(x) with Loud, Loud").contains("подмешан дважды"));
    }

    // --- имена из модуля -----------------------------------------------------

    @Test
    @DisplayName("родитель из именованного импорта: class A : m.Shape")
    void qualifiedParent() {
        ClassDeclStmt circle = classOf("class Circle(r) : m.Shape(\"круг\")");

        assertEquals("(get m \"Shape\")", SExprPrinter.print(circle.parent().type()));
        assertEquals("m.Shape", circle.parent().title());
        assertEquals(1, circle.parent().arguments().size());
    }

    @Test
    @DisplayName("трейт из именованного импорта: with m.Countable")
    void qualifiedTrait() {
        ClassDeclStmt bag = classOf("class Bag(items) with Loud, m.Countable");

        assertEquals("Loud", SExprPrinter.print(bag.traits().get(0).type()));
        assertEquals("(get m \"Countable\")", SExprPrinter.print(bag.traits().get(1).type()));
        assertEquals("m.Countable", bag.traits().get(1).title());
    }

    @Test
    @DisplayName("один и тот же трейт под своим именем и через модуль — разные имена")
    void qualifiedTraitIsNotADuplicate() {
        assertEquals(2, classOf("class A(x) with Loud, m.Loud").traits().size());
        assertTrue(errorOf("class A(x) with m.Loud, m.Loud").contains("подмешан дважды"));
    }

    // --- ссылка на тип — выражение -------------------------------------------

    @Test
    @DisplayName("вложенность любой глубины: class A : w.m.C")
    void nestedTypeName() {
        ClassDeclStmt a = classOf("class A(x) : w.m.C(1)");

        assertEquals("(get (get w \"m\") \"C\")", SExprPrinter.print(a.parent().type()));
        assertEquals("w.m.C", a.parent().title());
        assertEquals(1, a.parent().arguments().size());
    }

    @Test
    @DisplayName("родителя можно достать по ключу — и трейт тоже")
    void typeFromKey() {
        ClassDeclStmt a = classOf("class A(x) : registry[\"Shape\"](\"круг\") with plugins.all[0]");

        assertEquals("(get registry \"Shape\")", SExprPrinter.print(a.parent().type()));
        assertEquals(1, a.parent().arguments().size());
        assertEquals("(get (get plugins \"all\") 0)", SExprPrinter.print(a.traits().get(0).type()));
    }

    @Test
    @DisplayName("последние скобки родителя — аргументы заголовка, а вызов внутри цепочки обычный")
    void lastCallIsArguments() {
        // ': make()' — родитель make без аргументов, иначе ': Shape("круг")' значило бы
        // «вызвать Shape», а это основная форма записи.
        ClassDeclStmt plain = classOf("class A(x) : make()");
        assertEquals("make", SExprPrinter.print(plain.parent().type()));
        assertTrue(plain.parent().arguments().isEmpty());

        // Здесь последняя операция — обращение, поэтому скобки достаются all().
        ClassDeclStmt inner = classOf("class B(x) : registry.all()[\"Shape\"]");
        assertEquals("(get (call (get registry \"all\")) \"Shape\")",
                SExprPrinter.print(inner.parent().type()));
        assertTrue(inner.parent().arguments().isEmpty());
    }

    @Test
    @DisplayName("трейту скобки не аргументы: у него нет конструктора, значит это вызов")
    void traitParenthesesAreACall() {
        ClassDeclStmt a = classOf("class A(x) with make()");
        assertEquals("(call make)", SExprPrinter.print(a.traits().get(0).type()));
    }

    @Test
    @DisplayName("оператор в позиции типа не разбирается: складывать классы незачем")
    void operatorIsNotATypeReference() {
        // Цепочка обрывается на '+', и '{' там, где ждали тело класса, уже не подходит.
        assertFalse(errorOf("class A(x) : first + second { }").isEmpty());
    }

    @Test
    @DisplayName("ссылка на тип начинается с имени")
    void typeReferenceStartsWithName() {
        assertTrue(errorOf("class A(x) : 42").contains("ожидалось имя класса-родителя"));
        assertTrue(errorOf("class A(x) with \"Loud\"").contains("ожидалось имя трейта"));
    }

    @Test
    @DisplayName("после точки в имени типа обязательно имя")
    void nameAfterDotIsRequired() {
        assertTrue(errorOf("class A(x) : m.()").contains("после точки ожидалось имя поля"));
        assertTrue(errorOf("class A(x) with m.").contains("после точки ожидалось имя поля"));
    }

    // --- заголовок трейта ----------------------------------------------------

    @Test
    @DisplayName("в заголовке трейта поле без значения — требование, а не обязательный параметр")
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
    @DisplayName("значения полей трейта друг друга не видят: они считаются в области трейта")
    void traitDefaultsSeeNothing() {
        assertTrue(errorOf("trait Counted(count = 0, limit = count)")
                .contains("вычисляются в области объявления трейта"));
    }

    @Test
    @DisplayName("у трейта нет ни родителя, ни трейтов")
    void traitHasNoDependencies() {
        assertTrue(errorOf("trait Loud : Quiet()").contains("у трейта не бывает"));
        assertTrue(errorOf("trait Loud with Quiet").contains("у трейта не бывает"));
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
        ClassDeclStmt box = classOf("class Box(w, h) { def Box() { this.area = w * h } }");

        assertNotNull(box.constructor());
        assertTrue(box.constructor().params().isEmpty());
        assertTrue(box.methods().isEmpty());
        assertTrue(errorOf("class Box(w, h) { def Box(a) { } }").contains("конструктор не принимает параметров"));
    }

    @Test
    @DisplayName("фабрика объявляется на своём классе, и this в ней запрещён")
    void factories() {
        ClassDeclStmt user = classOf(
                "class User(name, hash) { def User.of(name, password) => new User(name, password) }");

        assertEquals(1, user.factories().size());
        assertEquals("of", user.factories().get(0).name());
        assertEquals("User.of", user.factories().get(0).function().title());

        assertTrue(errorOf("class User(name) { def Other.of() => 1 }")
                .contains("фабрика объявляется на своём классе"));
        assertTrue(errorOf("class User(name) { def User.of() => this.name }")
                .contains("'this' недопустим внутри фабрики"));
        assertTrue(errorOf("trait Printable { def Printable.of() => 1 }")
                .contains("у трейта не бывает фабрик"));
    }

    @Test
    @DisplayName("тело члена — только блок или '=>': иначе требование съело бы следующее объявление")
    void memberBodyIsBraceOrArrow() {
        TraitDeclStmt counted = traitOf("trait Counted(limit) { def report() def full() => limit }");

        assertEquals(1, counted.requirements().size());
        assertEquals("report", counted.requirements().get(0).name());
        assertEquals(1, counted.methods().size());
        assertEquals("full", counted.methods().get(0).name());
        assertEquals(BodyStyle.ARROW, counted.methods().get(0).style());
    }

    @Test
    @DisplayName("у требования проверяется и число параметров")
    void requirementKeepsParams() {
        TraitDeclStmt trait = traitOf("trait T { def compare(other, strict = false) }");
        assertEquals(2, trait.requirements().get(0).params().size());
    }

    @Test
    @DisplayName("метод без тела в классе — ошибка: требования бывают только в трейте")
    void classMethodNeedsBody() {
        assertTrue(errorOf("class A(x) { def report() }").contains("нет тела"));
    }

    @Test
    @DisplayName("два члена с одним именем не лежат: пространство имён одно")
    void duplicateMembers() {
        assertTrue(errorOf("class A(x) { def text() => 1 def text() => 2 }").contains("уже объявлен"));
        assertTrue(errorOf("class A(x) { def A() {} def A() {} }").contains("уже объявлен"));
    }

    // --- this и super --------------------------------------------------------

    @Test
    @DisplayName("this виден методу и вложенной в него функции")
    void thisInsideMethods() {
        FunctionExpr rename = classOf("class User(name) { def rename(name) { this.name = name } }")
                .methods().get(0);
        assertEquals("rename", rename.name());

        // Анонимная функция замыкает область метода, а экземпляр — часть этой цепочки
        parse("class B(rate) { def report() { show = def() => this.rate; show() } }");
    }

    @Test
    @DisplayName("this вне класса — ошибка разбора, а не выполнения")
    void thisOutsideClass() {
        assertTrue(errorOf("this.x = 1").contains("'this' допустим только внутри класса"));
        assertTrue(errorOf("def f() => this").contains("'this' допустим только внутри класса"));
    }

    @Test
    @DisplayName("super есть только у класса с родителем")
    void superNeedsParent() {
        parse("class Circle(r) : Shape() { def text() => super.text() }");
        assertTrue(errorOf("class A(x) { def text() => super.text() }")
                .contains("нет родителя"));
        assertTrue(errorOf("trait T { def text() => super.text() }")
                .contains("у трейта нет родителя"));
        assertTrue(errorOf("super.text()").contains("'super' допустим только внутри класса"));
    }

    @Test
    @DisplayName("this и super нельзя присвоить: это имена самого объекта")
    void selfNamesAreNotTargets() {
        assertTrue(errorOf("class A(x) { def f() { this = 1 } }").contains("нельзя присвоить"));
        assertTrue(errorOf("class A(x) : B() { def f() { super = 1 } }").contains("нельзя присвоить"));
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
        parse("def f(a, b = new Point(a)) => a");
        assertTrue(errorOf("def f(a = new Point(b), b = 1) => a").contains("связывается позже"));
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
        assertTrue(errorOf("trait { }").contains("ожидалось имя трейта"));
    }

    @Test
    @DisplayName("класс без родителя и без трейтов ничего лишнего в дерево не кладёт")
    void emptyPartsStayEmpty() {
        ClassDeclStmt point = classOf("class Point(x = 0, y = 0) { def text() => x }");
        assertNull(point.parent());
        assertTrue(point.traits().isEmpty());
        assertTrue(point.factories().isEmpty());
    }
}
