package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.op.*;
import ru.wds.wdl.ast.stmt.*;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementParserTest {

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

    private static Diagnostics diagnose(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        return diagnostics;
    }

    private static Program parseWithErrors(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), "ожидались ошибки разбора");
        return program;
    }

    // --- присваивание --------------------------------------------------------

    @Test
    @DisplayName("простое присваивание в имя")
    void simpleAssignment() {
        AssignStmt stmt = assertInstanceOf(AssignStmt.class, single("счёт = 42"));

        assertEquals(AssignOp.ASSIGN, stmt.op());
        assertEquals("счёт", assertInstanceOf(VariableExpr.class, stmt.target()).name());
        assertEquals("42", SExprPrinter.print(stmt.value()));
    }

    @Test
    @DisplayName("составное присваивание помнит операцию, а не разворачивается в парсере")
    void compoundAssignment() {
        assertEquals(AssignOp.ADD, assertInstanceOf(AssignStmt.class, single("x += 1")).op());
        assertEquals(AssignOp.SHIFT_RIGHT_UNSIGNED, assertInstanceOf(AssignStmt.class, single("x >>>= 2")).op());
        assertEquals(AssignOp.REMAINDER, assertInstanceOf(AssignStmt.class, single("x %= 3")).op());
    }

    @Test
    @DisplayName("цель присваивания — то же обращение, что и при чтении")
    void assignmentTargets() {
        AssignStmt dot = assertInstanceOf(AssignStmt.class, single("точка.x = 10"));
        assertEquals("(get точка \"x\")", SExprPrinter.print(dot.target()));

        AssignStmt chain = assertInstanceOf(AssignStmt.class, single("данные.строки[0].имя = \"болт\""));
        assertInstanceOf(AccessExpr.class, chain.target());
        assertEquals("(get (get (get данные \"строки\") 0) \"имя\")", SExprPrinter.print(chain.target()));
    }

    @Test
    @DisplayName("присваивание — инструкция, а не выражение: в условие его не вписать")
    void assignmentIsNotAnExpression() {
        assertTrue(diagnose("x = (y = 1)").hasErrors());
    }

    @Test
    @DisplayName("слева от знака присваивания должно стоять имя или обращение")
    void badAssignmentTarget() {
        assertTrue(diagnose("5 = 1").renderAll().contains("слева от '='"));
        assertTrue(diagnose("a + b = 1").renderAll().contains("слева от '='"));
    }

    // --- константы -----------------------------------------------------------

    @Test
    @DisplayName("объявление константы: имя и начальное значение")
    void constDeclaration() {
        ConstDeclStmt stmt = assertInstanceOf(ConstDeclStmt.class, single("const LIMIT = 10"));

        assertEquals("LIMIT", stmt.name());
        assertEquals("10", SExprPrinter.print(stmt.value()));
    }

    @Test
    @DisplayName("значение константы — обычное выражение")
    void constValueIsAnExpression() {
        ConstDeclStmt stmt = assertInstanceOf(ConstDeclStmt.class,
                single("const TOTAL = price * count + 1"));

        assertEquals("(+ (* price count) 1)", SExprPrinter.print(stmt.value()));
    }

    @Test
    @DisplayName("после 'const' обязано стоять имя")
    void constNeedsName() {
        assertTrue(diagnose("const = 1").renderAll().contains("после 'const' ожидалось имя"));
        assertTrue(diagnose("const 5 = 1").renderAll().contains("после 'const' ожидалось имя"));
    }

    @Test
    @DisplayName("константа без начального значения — ошибка: второго присваивания не будет")
    void constNeedsValue() {
        assertTrue(diagnose("const LIMIT").renderAll().contains("нужно начальное значение"));
        assertTrue(diagnose("const LIMIT += 1").renderAll().contains("нужно начальное значение"));
    }

    @Test
    @DisplayName("слева от '=' в объявлении константы стоит имя, а не обращение")
    void constTargetIsAName() {
        assertTrue(diagnose("const item.price = 1").renderAll().contains("заморозить можно имя"));
        assertTrue(diagnose("const items[0] = 1").renderAll().contains("заморозить можно имя"));
    }

    @Test
    @DisplayName("анонимная функция получает имя константы — как и при присваивании")
    void constNamesAnonymousFunction() {
        ConstDeclStmt stmt = assertInstanceOf(ConstDeclStmt.class,
                single("const add = def(a, b) => a + b"));

        assertEquals("add", assertInstanceOf(FunctionExpr.class, stmt.value()).name());
    }

    @Test
    @DisplayName("испорченная строка не съедает следующее объявление константы")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void constIsAStatementBoundary() {
        Program program = parseWithErrors("a = = =\nconst LIMIT = 10");

        assertEquals(2, program.statements().size());
        assertInstanceOf(ErrorStmt.class, program.statements().get(0));
        assertInstanceOf(ConstDeclStmt.class, program.statements().get(1));
    }

    @Test
    @DisplayName("'const' остаётся допустимым ключом объекта и именем поля")
    void constStaysUsableAsKey() {
        assertEquals(2, parse("o = {const: 1}\nx = o.const").statements().size());
    }

    // --- вызовы --------------------------------------------------------------

    @Test
    @DisplayName("вызов — инструкция; аргументов сколько угодно")
    void callStatement() {
        ExprStmt stmt = assertInstanceOf(ExprStmt.class, single("println(\"итого = \", 5)"));
        CallExpr call = assertInstanceOf(CallExpr.class, stmt.expr());

        assertEquals("println", assertInstanceOf(VariableExpr.class, call.callee()).name());
        assertEquals(2, call.arguments().size());
        assertEquals("(call println \"итого = \" 5)", SExprPrinter.print(call));
    }

    @Test
    @DisplayName("вызывается выражение, а не имя: цепочки разбираются без особых случаев")
    void callsAnyExpression() {
        assertEquals("(call (call f) 1)", SExprPrinter.print(callOf("f()(1)")));
        assertEquals("(call (get точка \"строкой\"))", SExprPrinter.print(callOf("точка.строкой()")));
        assertEquals("(call (get обработчики 0) x)", SExprPrinter.print(callOf("обработчики[0](x)")));
        assertEquals("(get (call f) \"поле\")", SExprPrinter.print(exprOf("f().поле")));
    }

    @Test
    @DisplayName("вызов связывает сильнее любых операций")
    void callBindsTightest() {
        assertEquals("(+ 1 (call f 2))", SExprPrinter.print(exprOf("1 + f(2)")));
        assertEquals("(- (call f))", SExprPrinter.print(exprOf("-f()")));
    }

    @Test
    @DisplayName("пустой список аргументов и висячая запятая")
    void argumentListForms() {
        assertEquals("(call f)", SExprPrinter.print(callOf("f()")));
        assertEquals("(call f 1 2)", SExprPrinter.print(callOf("f(1, 2,)")));
    }

    // --- границы инструкций --------------------------------------------------

    @Test
    @DisplayName("инструкции разделяются переводом строки или точкой с запятой")
    void statementSeparators() {
        assertEquals(3, parse("a = 1\nb = 2\nprintln(a + b)").statements().size());
        assertEquals(3, parse("a = 1; b = 2; println(a + b)").statements().size());
        assertEquals(2, parse(";;a = 1;;;b = 2;;").statements().size());
        assertEquals(0, parse("").statements().size());
        assertEquals(0, parse("// только комментарий\n").statements().size());
    }

    @Test
    @DisplayName("выражение без эффекта — ошибка, а не молчаливо выброшенная строка")
    void expressionWithoutEffectIsRejected() {
        assertTrue(diagnose("a + 1").renderAll().contains("ничего не делает"));
        assertTrue(diagnose("42").renderAll().contains("ничего не делает"));
    }

    @Test
    @DisplayName("испорченная строка не уносит с собой остальные")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void recoveryKeepsGoodStatements() {
        Program program = parseWithErrors("a = 1\nb = = =\nprintln(a)");

        assertEquals(3, program.statements().size());
        assertInstanceOf(AssignStmt.class, program.statements().get(0));
        assertInstanceOf(ErrorStmt.class, program.statements().get(1));
        assertInstanceOf(ExprStmt.class, program.statements().get(2));
    }

    @Test
    @DisplayName("незакрытый вызов не завешивает разбор")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void unclosedCall() {
        assertTrue(diagnose("println(1, 2").renderAll().contains("закрывающую скобку ')'"));
        assertTrue(diagnose("println(1 2)").hasErrors());
    }

    // --- служебное -----------------------------------------------------------

    private static CallExpr callOf(String code) {
        return assertInstanceOf(CallExpr.class, assertInstanceOf(ExprStmt.class, single(code)).expr());
    }

    /** Выражение из строки — через присваивание, чтобы оно было допустимой инструкцией. */
    private static Expr exprOf(String code) {
        return assertInstanceOf(AssignStmt.class, single("x = " + code)).value();
    }
}
