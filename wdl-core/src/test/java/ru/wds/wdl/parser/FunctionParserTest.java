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
        return assertInstanceOf(FunDeclStmt.class, single(code)).function();
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
        FunctionExpr block = declaration("fun сумма(a, b) { return a + b; }");
        FunctionExpr statement = declaration("fun сумма(a, b) return a + b;");
        FunctionExpr arrow = declaration("fun сумма(a, b) => a + b");

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
        assertEquals(BodyStyle.ARROW, declaration("fun f(a) => a").style());
        assertEquals(BodyStyle.STATEMENT, declaration("fun f(a) return a;").style());
        assertEquals(BodyStyle.STATEMENT, declaration("fun f(a) { return a; }").style());
    }

    @Test
    @DisplayName("перенос строки на разбор не влияет")
    void newlineDoesNotMatter() {
        FunctionExpr oneLine = declaration("fun f(a, b) return a + b;");
        FunctionExpr twoLines = declaration("fun f(a, b)\n    return a + b;");
        assertEquals(oneLine.style(), twoLines.style());
        assertEquals(oneLine.params().size(), twoLines.params().size());
    }

    @Test
    @DisplayName("функция без параметров и с висячей запятой")
    void parameterListEdges() {
        assertEquals(0, declaration("fun f() => 1").params().size());
        assertEquals(2, declaration("fun f(a, b,) => 1").params().size());
    }

    @Test
    @DisplayName("объявление внутри функции и внутри блока — обычная инструкция")
    void declarationNests() {
        FunctionExpr outer = declaration("fun снаружи() { fun внутри() => 1\n return внутри(); }");
        BlockStmt body = assertInstanceOf(BlockStmt.class, outer.body());
        assertInstanceOf(FunDeclStmt.class, body.statements().get(0));
        assertInstanceOf(ReturnStmt.class, body.statements().get(1));
    }

    // --- значения по умолчанию -----------------------------------------------

    @Test
    @DisplayName("значение по умолчанию попадает в дерево вместе с параметром")
    void defaultValueIsPartOfParameter() {
        FunctionExpr function = declaration("fun total(price, count = 1) => price * count");
        assertEquals("(fun total price (count 1))", SExprPrinter.print(function));
        assertFalse(function.params().get(0).hasDefault());
        assertTrue(function.params().get(1).hasDefault());
    }

    @Test
    @DisplayName("по умолчанию — выражение, а не литерал: приоритеты те же, запятая не рвёт список")
    void defaultValueIsFullExpression() {
        assertEquals("(fun f a (b (+ 1 (* 2 3))))",
                SExprPrinter.print(declaration("fun f(a, b = 1 + 2 * 3) => a")));
        assertEquals("(fun f (a (call now)) (b (array 1 2)))",
                SExprPrinter.print(declaration("fun f(a = now(), b = [1, 2]) => a")));
        assertEquals(2, declaration("fun f(a = 1, b = 2,) => a").params().size());
    }

    @Test
    @DisplayName("значение по умолчанию видит параметры слева")
    void defaultValueSeesEarlierParameters() {
        assertEquals("(fun f a (b (* a 2)))",
                SExprPrinter.print(declaration("fun f(a, b = a * 2) => b")));
    }

    @Test
    @DisplayName("одинаково у объявления и у анонимной функции — узел-то один")
    void defaultValueWorksForAnonymous() {
        AssignStmt assign = assertInstanceOf(AssignStmt.class, single("double = fun(x, by = 2) => x * by"));
        FunctionExpr function = assertInstanceOf(FunctionExpr.class, assign.value());
        assertEquals("(fun double x (by 2))", SExprPrinter.print(function));
    }

    @Test
    @DisplayName("обязательный параметр после необязательного — ошибка разбора")
    void requiredCannotFollowOptional() {
        String message = errorOf("fun f(a, b = 10, c) => a");
        assertTrue(message.contains("параметр 'c' без значения по умолчанию"), message);
        assertTrue(message.contains("после параметра со значением по умолчанию"), message);
        // Обратный порядок законен, как и все параметры со значениями.
        parse("fun f(a, b = 10, c = 20) => a");
        parse("fun f(a = 1) => a");
    }

    @Test
    @DisplayName("ссылка на параметр правее — ошибка: иначе молча взялась бы внешняя переменная")
    void defaultValueCannotLookRight() {
        assertTrue(errorOf("fun f(a = b, b = 1) => a").contains("связывается позже"));
        assertTrue(errorOf("fun f(a, b = c * 2, c = 1) => b").contains("параметр 'c'"));
        assertTrue(errorOf("fun f(a = a) => a").contains("ссылается на сам параметр"));
        // Обращение спрятано глубоко в выражении — всё равно находится
        assertTrue(errorOf("fun f(a = [1, {k: b}], b = 2) => a").contains("связывается позже"));
    }

    @Test
    @DisplayName("одноимённый параметр внутри вложенной функции — свой и претензий не вызывает")
    void nestedFunctionHasItsOwnNames() {
        parse("fun f(a = fun(b) => b, b = 1) => a");
        parse("fun f(a = fun(x) { return x; }, x = 1) => a");
    }

    // --- анонимные функции ---------------------------------------------------

    @Test
    @DisplayName("анонимная функция — выражение, и кладётся куда угодно")
    void anonymousIsExpression() {
        AssignStmt assign = assertInstanceOf(AssignStmt.class, single("f = fun(a) => a"));
        assertInstanceOf(FunctionExpr.class, assign.value());

        ObjectExpr object = assertInstanceOf(ObjectExpr.class,
                assertInstanceOf(AssignStmt.class, single("o = {плюс: fun(a, b) => a + b}")).value());
        assertInstanceOf(FunctionExpr.class, object.entries().get(0).value());
    }

    @Test
    @DisplayName("тело-стрелка не съедает запятую списка аргументов")
    void arrowStopsAtComma() {
        CallExpr call = assertInstanceOf(CallExpr.class,
                assertInstanceOf(ExprStmt.class, single("применить(fun(x) => x * 2, 5)")).expr());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(FunctionExpr.class, call.arguments().get(0));
    }

    @Test
    @DisplayName("после => фигурная скобка — литерал объекта, а не блок")
    void braceAfterArrowIsObject() {
        FunctionExpr function = declaration("fun точка(x, y) => {x: x, y: y}");
        ReturnStmt body = assertInstanceOf(ReturnStmt.class, function.body());
        assertInstanceOf(ObjectExpr.class, body.value());
    }

    @Test
    @DisplayName("анонимной достаётся имя переменной — только ради диагностики")
    void anonymousBorrowsTargetName() {
        AssignStmt assign = assertInstanceOf(AssignStmt.class, single("f = fun(a) => a"));
        assertEquals("f", assertInstanceOf(FunctionExpr.class, assign.value()).name());

        // Составное присваивание и запись в поле имени не дают: там нет объявления.
        ObjectExpr object = assertInstanceOf(ObjectExpr.class,
                assertInstanceOf(AssignStmt.class, single("o = {к: fun() => 1}")).value());
        assertNull(assertInstanceOf(FunctionExpr.class, object.entries().get(0).value()).name());
    }

    @Test
    @DisplayName("анонимная функция сама по себе — инструкция, которая ничего не делает")
    void anonymousAloneIsNotAStatement() {
        assertTrue(errorOf("fun(a) => a").contains("ничего не делает"));
    }

    // --- return --------------------------------------------------------------

    @Test
    @DisplayName("return со значением и без него")
    void returnWithAndWithoutValue() {
        FunctionExpr withValue = declaration("fun f() { return 1; }");
        BlockStmt body = assertInstanceOf(BlockStmt.class, withValue.body());
        assertTrue(assertInstanceOf(ReturnStmt.class, body.statements().get(0)).hasValue());

        FunctionExpr bare = declaration("fun f() { return; }");
        BlockStmt bareBody = assertInstanceOf(BlockStmt.class, bare.body());
        ReturnStmt statement = assertInstanceOf(ReturnStmt.class, bareBody.statements().get(0));
        assertFalse(statement.hasValue());
        assertNull(statement.value());
    }

    @Test
    @DisplayName("точка с запятой обязательна: без неё следующая строка не приклеивается молча")
    void semicolonIsRequired() {
        String message = errorOf("fun f() { return 1\nprintln(\"после\") }");
        assertTrue(message.contains("';'"), message);
        assertTrue(message.contains("return"), message);
    }

    @Test
    @DisplayName("return вне функции — ошибка разбора, а не выполнения")
    void returnOutsideFunction() {
        assertTrue(errorOf("return 5;").contains("только внутри функции"));
        assertTrue(errorOf("while (true) { return 5; }").contains("только внутри функции"));
        // А внутри функции — в любой вложенности
        parse("fun f(x) { while (true) { if (x) { return 1; } } return 0; }");
    }

    @Test
    @DisplayName("из цикла нельзя выйти через границу функции")
    void loopDoesNotCrossFunctionBoundary() {
        assertTrue(errorOf("while (true) { fun f() { break } }").contains("только внутри цикла"));
        assertTrue(errorOf("for (x in [1]) { fun f() => 1\n fun g() { continue } }")
                .contains("только внутри цикла"));
        // Свой цикл внутри функции — пожалуйста
        parse("while (true) { fun f() { while (true) { break } } }");
    }

    // --- ошибки и восстановление ---------------------------------------------

    @Test
    @DisplayName("одноимённые параметры — ошибка с указанием на второй")
    void duplicateParameter() {
        assertTrue(errorOf("fun f(a, b, a) => a").contains("параметр 'a' уже объявлен"));
    }

    @Test
    @DisplayName("мусор в списке параметров не уносит остальной файл")
    void recoversFromBrokenParameters() {
        Diagnostics diagnostics = diagnose("fun f(a, 5, b) => a\nx = 1");
        assertTrue(diagnostics.hasErrors());
        assertTrue(diagnostics.renderAll().contains("имя параметра"), diagnostics.renderAll());
    }

    @Test
    @DisplayName("отсутствующее тело — ошибка на месте, а не молчание")
    void missingBody() {
        assertTrue(errorOf("fun f(a)").contains("тело"));
        assertTrue(errorOf("fun f(a);").contains("тело"));
    }

    @Test
    @DisplayName("после испорченного объявления разбор продолжается")
    void recoversAfterBrokenDeclaration() {
        Diagnostics diagnostics = diagnose("fun f(a) => = =\nx = 1");
        assertTrue(diagnostics.hasErrors());
        // Вторая строка разобралась: ошибка ровно одна, про первую строку.
        assertEquals(1, diagnostics.errorCount(), diagnostics.renderAll());
    }
}
