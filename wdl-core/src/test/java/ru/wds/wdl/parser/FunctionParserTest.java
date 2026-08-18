package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.BodyStyle;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.ObjectExpr;
import ru.wds.wdl.ast.stmt.*;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор функций: формы объявления, тело-стрелка, {@code return}, восстановление после ошибок. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class FunctionParserTest {

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

    private static FunctionExpr declaration(String code) {
        return assertInstanceOf(DefDeclStmt.class, single(code)).function();
    }

    private static Diagnostics diagnose(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        return diagnostics;
    }

    private static String errorOf(String code) {
        Diagnostics diagnostics = diagnose(code);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- формы объявления ----------------------------------------------------

    @Test
    @DisplayName("все формы тела дают одно дерево: блок, инструкция, стрелка")
    void bodyFormsAgree() {
        FunctionExpr block = declaration("def сумма(a, b) { return a + b; }");
        FunctionExpr statement = declaration("def сумма(a, b) return a + b;");
        FunctionExpr arrow = declaration("def сумма(a, b) => a + b");

        assertEquals("сумма", block.name());
        assertEquals(2, block.params().size());

        // Блок — это блок, а одиночная инструкция и стрелка дают сразу return.
        BlockStmt body = assertInstanceOf(BlockStmt.class, block.body());
        ReturnStmt inBlock = assertInstanceOf(ReturnStmt.class, body.statements().get(0));
        ReturnStmt asStatement = assertInstanceOf(ReturnStmt.class, statement.body());
        ReturnStmt fromArrow = assertInstanceOf(ReturnStmt.class, arrow.body());

        assertEquals(SExprPrinter.print(inBlock.value()), SExprPrinter.print(asStatement.value()));
        assertEquals(SExprPrinter.print(inBlock.value()), SExprPrinter.print(fromArrow.value()));
    }

    @Test
    @DisplayName("форма записи тела сохраняется — она нужна форматтеру")
    void bodyStyleIsRemembered() {
        assertEquals(BodyStyle.ARROW, declaration("def f(a) => a").style());
        assertEquals(BodyStyle.STATEMENT, declaration("def f(a) return a;").style());
        assertEquals(BodyStyle.STATEMENT, declaration("def f(a) { return a; }").style());
    }

    @Test
    @DisplayName("перенос строки на разбор не влияет")
    void newlineDoesNotMatter() {
        FunctionExpr oneLine = declaration("def f(a, b) return a + b;");
        FunctionExpr twoLines = declaration("def f(a, b)\n    return a + b;");
        assertEquals(oneLine.style(), twoLines.style());
        assertEquals(oneLine.params().size(), twoLines.params().size());
    }

    @Test
    @DisplayName("функция без параметров и с висячей запятой")
    void parameterListEdges() {
        assertEquals(0, declaration("def f() => 1").params().size());
        assertEquals(2, declaration("def f(a, b,) => 1").params().size());
    }

    @Test
    @DisplayName("объявление внутри функции и внутри блока — обычная инструкция")
    void declarationNests() {
        FunctionExpr outer = declaration("def снаружи() { def внутри() => 1\n return внутри(); }");
        BlockStmt body = assertInstanceOf(BlockStmt.class, outer.body());
        assertInstanceOf(DefDeclStmt.class, body.statements().get(0));
        assertInstanceOf(ReturnStmt.class, body.statements().get(1));
    }

    // --- значения по умолчанию -----------------------------------------------

    @Test
    @DisplayName("значение по умолчанию попадает в дерево вместе с параметром")
    void defaultValueIsPartOfParameter() {
        FunctionExpr function = declaration("def total(price, count = 1) => price * count");
        assertEquals("(def total price (count 1))", SExprPrinter.print(function));
        assertFalse(function.params().get(0).hasDefault());
        assertTrue(function.params().get(1).hasDefault());
    }

    @Test
    @DisplayName("по умолчанию — выражение, а не литерал: приоритеты те же, запятая не рвёт список")
    void defaultValueIsFullExpression() {
        assertEquals("(def f a (b (+ 1 (* 2 3))))",
                SExprPrinter.print(declaration("def f(a, b = 1 + 2 * 3) => a")));
        assertEquals("(def f (a (call now)) (b (array 1 2)))",
                SExprPrinter.print(declaration("def f(a = now(), b = [1, 2]) => a")));
        assertEquals(2, declaration("def f(a = 1, b = 2,) => a").params().size());
    }

    @Test
    @DisplayName("значение по умолчанию видит параметры слева")
    void defaultValueSeesEarlierParameters() {
        assertEquals("(def f a (b (* a 2)))",
                SExprPrinter.print(declaration("def f(a, b = a * 2) => b")));
    }

    @Test
    @DisplayName("одинаково у объявления и у анонимной функции — узел-то один")
    void defaultValueWorksForAnonymous() {
        AssignStmt assign = assertInstanceOf(AssignStmt.class, single("double = def(x, by = 2) => x * by"));
        FunctionExpr function = assertInstanceOf(FunctionExpr.class, assign.value());
        assertEquals("(def double x (by 2))", SExprPrinter.print(function));
    }

    @Test
    @DisplayName("обязательный параметр после необязательного — ошибка разбора")
    void requiredCannotFollowOptional() {
        String message = errorOf("def f(a, b = 10, c) => a");
        assertTrue(message.contains("параметр 'c' без значения по умолчанию"), message);
        assertTrue(message.contains("после параметра со значением по умолчанию"), message);
        // Обратный порядок законен, как и все параметры со значениями.
        parse("def f(a, b = 10, c = 20) => a");
        parse("def f(a = 1) => a");
    }

    @Test
    @DisplayName("ссылка на параметр правее — ошибка: иначе молча взялась бы внешняя переменная")
    void defaultValueCannotLookRight() {
        assertTrue(errorOf("def f(a = b, b = 1) => a").contains("связывается позже"));
        assertTrue(errorOf("def f(a, b = c * 2, c = 1) => b").contains("параметр 'c'"));
        assertTrue(errorOf("def f(a = a) => a").contains("ссылается на сам параметр"));
        // Обращение спрятано глубоко в выражении — всё равно находится
        assertTrue(errorOf("def f(a = [1, {k: b}], b = 2) => a").contains("связывается позже"));
    }

    @Test
    @DisplayName("одноимённый параметр внутри вложенной функции — свой и претензий не вызывает")
    void nestedFunctionHasItsOwnNames() {
        parse("def f(a = def(b) => b, b = 1) => a");
        parse("def f(a = def(x) { return x; }, x = 1) => a");
    }

    // --- анонимные функции ---------------------------------------------------

    @Test
    @DisplayName("анонимная функция — выражение, и кладётся куда угодно")
    void anonymousIsExpression() {
        AssignStmt assign = assertInstanceOf(AssignStmt.class, single("f = def(a) => a"));
        assertInstanceOf(FunctionExpr.class, assign.value());

        ObjectExpr object = assertInstanceOf(ObjectExpr.class,
                assertInstanceOf(AssignStmt.class, single("o = {плюс: def(a, b) => a + b}")).value());
        assertInstanceOf(FunctionExpr.class, object.entries().get(0).value());
    }

    @Test
    @DisplayName("тело-стрелка не съедает запятую списка аргументов")
    void arrowStopsAtComma() {
        CallExpr call = assertInstanceOf(CallExpr.class,
                assertInstanceOf(ExprStmt.class, single("применить(def(x) => x * 2, 5)")).expr());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(FunctionExpr.class, call.arguments().get(0).value());
    }

    @Test
    @DisplayName("после => фигурная скобка — литерал объекта, а не блок")
    void braceAfterArrowIsObject() {
        FunctionExpr function = declaration("def точка(x, y) => {x: x, y: y}");
        ReturnStmt body = assertInstanceOf(ReturnStmt.class, function.body());
        assertInstanceOf(ObjectExpr.class, body.value());
    }

    @Test
    @DisplayName("анонимной достаётся имя переменной — только ради диагностики")
    void anonymousBorrowsTargetName() {
        AssignStmt assign = assertInstanceOf(AssignStmt.class, single("f = def(a) => a"));
        assertEquals("f", assertInstanceOf(FunctionExpr.class, assign.value()).name());

        // Составное присваивание и запись в поле имени не дают: там нет объявления.
        ObjectExpr object = assertInstanceOf(ObjectExpr.class,
                assertInstanceOf(AssignStmt.class, single("o = {к: def() => 1}")).value());
        assertNull(assertInstanceOf(FunctionExpr.class, object.entries().get(0).value()).name());
    }

    @Test
    @DisplayName("анонимная функция сама по себе — инструкция, которая ничего не делает")
    void anonymousAloneIsNotAStatement() {
        assertTrue(errorOf("def(a) => a").contains("ничего не делает"));
    }

    // --- return --------------------------------------------------------------

    @Test
    @DisplayName("return со значением и без него")
    void returnWithAndWithoutValue() {
        FunctionExpr withValue = declaration("def f() { return 1; }");
        BlockStmt body = assertInstanceOf(BlockStmt.class, withValue.body());
        assertTrue(assertInstanceOf(ReturnStmt.class, body.statements().get(0)).hasValue());

        FunctionExpr bare = declaration("def f() { return; }");
        BlockStmt bareBody = assertInstanceOf(BlockStmt.class, bare.body());
        ReturnStmt statement = assertInstanceOf(ReturnStmt.class, bareBody.statements().get(0));
        assertFalse(statement.hasValue());
        assertNull(statement.value());
    }

    @Test
    @DisplayName("точка с запятой обязательна: без неё следующая строка не приклеивается молча")
    void semicolonIsRequired() {
        String message = errorOf("def f() { return 1\nprintln(\"после\") }");
        assertTrue(message.contains("';'"), message);
        assertTrue(message.contains("return"), message);
    }

    @Test
    @DisplayName("return вне функции — ошибка разбора, а не выполнения")
    void returnOutsideFunction() {
        assertTrue(errorOf("return 5;").contains("только внутри функции"));
        assertTrue(errorOf("while (true) { return 5; }").contains("только внутри функции"));
        // А внутри функции — в любой вложенности
        parse("def f(x) { while (true) { if (x) { return 1; } } return 0; }");
    }

    @Test
    @DisplayName("из цикла нельзя выйти через границу функции")
    void loopDoesNotCrossFunctionBoundary() {
        assertTrue(errorOf("while (true) { def f() { break } }").contains("только внутри цикла"));
        assertTrue(errorOf("for (x in [1]) { def f() => 1\n def g() { continue } }")
                .contains("только внутри цикла"));
        // Свой цикл внутри функции — пожалуйста
        parse("while (true) { def f() { while (true) { break } } }");
    }

    // --- ошибки и восстановление ---------------------------------------------

    @Test
    @DisplayName("одноимённые параметры — ошибка с указанием на второй")
    void duplicateParameter() {
        assertTrue(errorOf("def f(a, b, a) => a").contains("параметр 'a' уже объявлен"));
    }

    @Test
    @DisplayName("мусор в списке параметров не уносит остальной файл")
    void recoversFromBrokenParameters() {
        Diagnostics diagnostics = diagnose("def f(a, 5, b) => a\nx = 1");
        assertTrue(diagnostics.hasErrors());
        assertTrue(diagnostics.renderAll().contains("имя параметра"), diagnostics.renderAll());
    }

    @Test
    @DisplayName("отсутствующее тело — ошибка на месте, а не молчание")
    void missingBody() {
        assertTrue(errorOf("def f(a)").contains("тело"));
        assertTrue(errorOf("def f(a);").contains("тело"));
    }

    @Test
    @DisplayName("после испорченного объявления разбор продолжается")
    void recoversAfterBrokenDeclaration() {
        Diagnostics diagnostics = diagnose("def f(a) => = =\nx = 1");
        assertTrue(diagnostics.hasErrors());
        // Вторая строка разобралась: ошибка ровно одна, про первую строку.
        assertEquals(1, diagnostics.errorCount(), diagnostics.renderAll());
    }

    // --- модификатор synchronized --------------------------------------------

    @Test
    @DisplayName("'synchronized' ложится в модификаторы функции, а не в её тело")
    void synchronizedIsAModifier() {
        FunctionExpr function = declaration("synchronized def bump() { count = count + 1 }");

        assertEquals("bump", function.name());
        assertTrue(function.isSynchronized());
        assertEquals(1, function.modifiers().size());
        // Форма дерева: модификатор виден отдельным словом заголовка.
        assertEquals("(synchronized-def bump)", new SExprPrinter().visit(function, null));
    }

    @Test
    @DisplayName("обычная функция модификаторов не получает")
    void plainFunctionHasNoModifiers() {
        FunctionExpr function = declaration("def bump() { count = count + 1 }");

        assertFalse(function.isSynchronized());
        assertTrue(function.modifiers().isEmpty());
        assertEquals("(def bump)", new SExprPrinter().visit(function, null));
    }

    @Test
    @DisplayName("модификатор работает и у анонимной функции")
    void anonymousFunctionTakesModifier() {
        Stmt statement = single("handler = synchronized def (event) => event");
        FunctionExpr function = assertInstanceOf(FunctionExpr.class,
                assertInstanceOf(AssignStmt.class, statement).value());

        assertTrue(function.isSynchronized());
        // Имя подставлено целью присваивания — и модификатор при этом не потерялся.
        assertEquals("(synchronized-def handler event)", new SExprPrinter().visit(function, null));
    }

    @Test
    @DisplayName("место функции начинается с модификатора, а не с 'def'")
    void spanStartsAtTheModifier() {
        FunctionExpr function = declaration("synchronized def f() => 1");
        assertEquals(0, function.span().start(), "подчёркивание обязано указывать на всё объявление");
    }

    @Test
    @DisplayName("'synchronized' без 'def' — своя ошибка, а не «ожидалось выражение»")
    void synchronizedWithoutDef() {
        String message = errorOf("synchronized x = 1");
        assertTrue(message.contains("модификатор функции"), message);
    }

    @Test
    @DisplayName("после 'synchronized' без 'def' разбор продолжается")
    void recoversAfterOrphanModifier() {
        Diagnostics diagnostics = diagnose("synchronized 5\nx = 1");
        assertTrue(diagnostics.hasErrors());
        assertEquals(1, diagnostics.errorCount(), diagnostics.renderAll());
    }

    // --- остаточные параметры ------------------------------------------------

    @Test
    @DisplayName("остатки лежат отдельно от параметров и видны в форме дерева")
    void restParameters() {
        FunctionExpr function = declaration("def inspect(a, b = 10, *args, **named) {}");
        assertEquals(2, function.params().size(), "остаток позиции не занимает");
        assertEquals("args", function.rest().name());
        assertEquals("named", function.namedRest().name());
        assertTrue(function.isVariadic());
        assertEquals("(def inspect a (b 10) (* args) (** named))",
                new SExprPrinter().visit(function, null));
    }

    @Test
    @DisplayName("каждый остаток бывает по отдельности")
    void restsAreIndependent() {
        assertNull(declaration("def f(**named) {}").rest());
        assertNull(declaration("def f(*args) {}").namedRest());
        assertEquals("(def f (** named))",
                new SExprPrinter().visit(declaration("def f(**named) {}"), null));
    }

    @Test
    @DisplayName("обычный параметр после остатка — ошибка разбора")
    void plainParameterAfterRest() {
        assertTrue(errorOf("def bad(*args, value) {}")
                .contains("параметр 'value' не может идти после остаточного параметра '*args'"));
        assertTrue(errorOf("def bad(**named, value) {}")
                .contains("параметр 'value' не может идти после остаточного параметра '**named'"));
    }

    @Test
    @DisplayName("позиционный остаток после именованного — ошибка разбора")
    void restAfterNamedRest() {
        assertTrue(errorOf("def bad(**named, *args) {}")
                .contains("остаточный параметр '*args' не может идти после '**named'"));
    }

    @Test
    @DisplayName("двух одинаковых остатков не бывает")
    void restDeclaredTwice() {
        assertTrue(errorOf("def bad(*a, *b) {}").contains("остаточный параметр '*a' уже объявлен"));
        assertTrue(errorOf("def bad(**a, **b) {}").contains("остаточный параметр '**a' уже объявлен"));
    }

    @Test
    @DisplayName("у остатка нет значения по умолчанию")
    void restHasNoDefault() {
        assertTrue(errorOf("def bad(*args = []) {}")
                .contains("у остаточного параметра 'args' не может быть значения по умолчанию"));
    }

    @Test
    @DisplayName("имя остатка не может повторять имя параметра")
    void restRepeatsParameterName() {
        assertTrue(errorOf("def bad(args, *args) {}").contains("параметр 'args' уже объявлен"));
    }

    @Test
    @DisplayName("в заголовке класса и трейта остатка нет: там список полей")
    void restForbiddenInTypeHeader() {
        assertTrue(errorOf("class Point(x, *rest) {}")
                .contains("остаточный параметр 'rest' здесь не разрешён"));
        assertTrue(errorOf("trait Counted(*rest) {}")
                .contains("остаточный параметр 'rest' здесь не разрешён"));
    }

    @Test
    @DisplayName("у метода остаток есть, у конструктора — нет")
    void restInMembers() {
        FunctionExpr method = assertInstanceOf(ClassDeclStmt.class,
                single("class Box { def all(*args) => args }")).methods().get(0);
        assertEquals("args", method.rest().name());
        assertTrue(errorOf("class Box { def Box(*args) {} }")
                .contains("конструктор не принимает параметров"));
    }
}
