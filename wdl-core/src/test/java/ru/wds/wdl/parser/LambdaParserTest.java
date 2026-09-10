package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.BodyStyle;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.ExprStmt;
import ru.wds.wdl.ast.stmt.ReturnStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор лямбд: {@code p => p * 2} и {@code (a, b) => a + b}.
 * <p>
 * Главное утверждение здесь одно — лямбда это {@code def}-форма без слова {@code def},
 * то есть <b>тот же узел</b>. Всё остальное проверяет границы: где кончается тело,
 * куда уходит стрелка и что отличает лямбду от опечатки в {@code >=}.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class LambdaParserTest {

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

    /** Функция, стоящая в правой части присваивания. */
    private static FunctionExpr lambda(String code) {
        AssignStmt assign = assertInstanceOf(AssignStmt.class, single(code));
        return assertInstanceOf(FunctionExpr.class, assign.value());
    }

    /** Форма дерева заголовка: имя, параметры, остатки. Тело сюда не попадает. */
    private static String header(String code) {
        return SExprPrinter.print(lambda(code));
    }

    /** Форма дерева тела — того выражения, что стоит под стрелкой. */
    private static String body(String code) {
        ReturnStmt returned = assertInstanceOf(ReturnStmt.class, lambda(code).body());
        return SExprPrinter.print(returned.value());
    }

    private static String diagnose(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- узел тот же ---------------------------------------------------------

    @Test
    @DisplayName("три записи одной функции дают одно и то же дерево")
    void sameTreeAsDef() {
        assertEquals("(def double x)", header("double = x => x * 2"));
        assertEquals("(def double x)", header("double = (x) => x * 2"));
        assertEquals("(def double x)", header("double = def(x) => x * 2"));

        assertEquals("(* x 2)", body("double = x => x * 2"));
        assertEquals("(* x 2)", body("double = (x) => x * 2"));
        assertEquals("(* x 2)", body("double = def(x) => x * 2"));
    }

    @Test
    @DisplayName("заголовок в скобках умеет всё, что умеет 'def'")
    void parenHeaderIsTheSame() {
        assertEquals("(def now)", header("now = () => 7"));
        assertEquals("(def sum a b)", header("sum = (a, b) => a + b"));
        assertEquals("(def step x (by 1))", header("step = (x, by = 1) => x + by"));
        assertEquals("(def forward (* args) (** named))",
                header("forward = (*args, **named) => target(*args, **named)"));
        assertEquals("(def tagged ((@ (\"a\" 1)) x))", header("tagged = (@{a: 1} x) => x"));
    }

    @Test
    @DisplayName("форма записи остаётся в BodyStyle: без неё форматтер вернёт не то, что было")
    void styleRemembersHowItWasWritten() {
        assertEquals(BodyStyle.LAMBDA, lambda("f = x => x").style());
        assertEquals(BodyStyle.LAMBDA, lambda("f = (x) => x").style());
        assertEquals(BodyStyle.ARROW, lambda("f = def(x) => x").style());
    }

    @Test
    @DisplayName("имя достаётся от переменной, но анонимной лямбда быть не перестаёт")
    void borrowsTargetName() {
        FunctionExpr function = lambda("double = x => x * 2");
        assertEquals("double", function.name());
        assertTrue(function.anonymous());
        assertEquals("double", function.title());
    }

    @Test
    @DisplayName("у безымянной лямбды в сообщении стоит '=>', а не выдуманное 'def'")
    void titleIsTheArrow() {
        CallExpr call = assertInstanceOf(CallExpr.class,
                assertInstanceOf(ExprStmt.class, single("apply(x => x * 2)")).expr());
        FunctionExpr function = assertInstanceOf(FunctionExpr.class,
                call.arguments().get(0).value());
        assertNull(function.name());
        assertEquals("=>", function.title());
    }

    // --- границы тела --------------------------------------------------------

    @Test
    @DisplayName("тело забирает всё, что написано справа: тернарник целиком уходит в него")
    void bodyTakesEverythingToTheRight() {
        assertEquals("(?: (> x 0) 1 (- 1))", body("sign = x => x > 0 ? 1 : -1"));
        assertEquals("(|| (> x 0) (< x 0))", body("nonzero = x => x > 0 || x < 0"));
    }

    @Test
    @DisplayName("стрелка правоассоциативна — отсюда каррирование")
    void rightAssociative() {
        FunctionExpr outer = lambda("adder = a => b => a + b");
        assertEquals("(def adder a)", SExprPrinter.print(outer));
        ReturnStmt returned = assertInstanceOf(ReturnStmt.class, outer.body());
        FunctionExpr inner = assertInstanceOf(FunctionExpr.class, returned.value());
        // Печать безымянной функции не меняется от того, лямбда это или анонимный
        // 'def': форма дерева у них одна, а форма записи проверяется дампом.
        assertEquals("(def def b)", SExprPrinter.print(inner));
    }

    @Test
    @DisplayName("запятая списка аргументов обрывает тело")
    void commaStopsBody() {
        CallExpr call = assertInstanceOf(CallExpr.class,
                assertInstanceOf(ExprStmt.class, single("apply(p => p, 10)")).expr());
        assertEquals(2, call.arguments().size());
        assertInstanceOf(FunctionExpr.class, call.arguments().get(0).value());
    }

    @Test
    @DisplayName("лямбда живёт в цепочке вызовов — ради этого всё и затевалось")
    void worksInChains() {
        assertInstanceOf(ExprStmt.class, single("prices.filter(p => p >= 100).map(p => p * 2)"));
    }

    @Test
    @DisplayName("и уживается с 'match' в одной строке")
    void livesNextToMatch() {
        parse("""
                handler = match (kind) {
                    case "sum" -> (a, b) => a + b
                    else -> (a, b) => a - b
                }
                """);
    }

    // --- скан вперёд не путается ---------------------------------------------

    @Test
    @DisplayName("обычная скобка остаётся группировкой, а не заголовком")
    void groupStaysGroup() {
        assertEquals("(* (+ a b) c)",
                SExprPrinter.print(assertInstanceOf(AssignStmt.class,
                        single("x = (a + b) * c")).value()));
        assertInstanceOf(CallExpr.class,
                assertInstanceOf(AssignStmt.class, single("x = (a)(b)")).value());
    }

    @Test
    @DisplayName("лямбда в скобках и сразу вызванная")
    void immediatelyInvoked() {
        CallExpr call = assertInstanceOf(CallExpr.class,
                assertInstanceOf(AssignStmt.class, single("x = ((a, b) => a)(1, 2)")).value());
        assertInstanceOf(FunctionExpr.class, call.callee());
        assertEquals(2, call.arguments().size());
    }

    // --- пропуск -------------------------------------------------------------

    @Test
    @DisplayName("пропуск пишется в скобках — и вся его диагностика работает и здесь")
    void holeInParens() {
        assertEquals("(def ignore _)", header("ignore = (_) => 42"));
        assertEquals("(def second _ second)", header("second = (_, second) => second"));

        assertTrue(diagnose("f = (_ = 1) => 42")
                .contains("у пропуска '_' не может быть значения по умолчанию"));
        assertTrue(diagnose("f = (*rest, _) => 42")
                .contains("пропуск '_' не может идти после остаточного параметра '*rest'"));
    }

    @Test
    @DisplayName("пропуск без скобок — совет, и ровно одна ошибка")
    void holeWithoutParens() {
        Source source = Source.ofString("f = _ => 42");
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertEquals(1, diagnostics.all().size(), () -> diagnostics.renderAll());
        assertTrue(diagnostics.renderAll().contains("без скобок он не пишется: '(_) => 42'"));
    }

    @Test
    @DisplayName("пропуск в любом другом месте — по-прежнему 'читать его нельзя'")
    void holeElsewhereIsUnchanged() {
        assertTrue(diagnose("x = _ + 1").contains("читать его нельзя"));
    }

    // --- параметр обязан использоваться --------------------------------------

    @Test
    @DisplayName("бесскобочный параметр без употребления — почти наверняка опечатка в '>='")
    void bareParamMustBeUsed() {
        String error = diagnose("ready = count => limit");
        assertTrue(error.contains("параметр 'count' не используется в теле функции"));
        assertTrue(error.contains("знак пишется '>='"));
        assertTrue(error.contains("'(_) => ...'"));
    }

    @Test
    @DisplayName("скобки снимают правило: они уже сказали «это функция»")
    void parensLiftTheRule() {
        parse("f = (count) => limit");
        parse("f = (_) => limit");
        parse("f = def(count) => limit");
        parse("f = p => p * 2");
    }

    @Test
    @DisplayName("употреблением считается любое место в теле, а не только верхнее")
    void useCountsAnywhereInBody() {
        parse("f = p => other(p.name)");
        parse("f = p => 'привет, ${p}'");
        parse("f = a => b => a + b");
    }

    // --- слева не имя --------------------------------------------------------

    @Test
    @DisplayName("слева от стрелки бывает только имя")
    void leftSideMustBeName() {
        assertTrue(diagnose("f = 1 + 2 => 3").contains("слева от '=>' ожидалось имя параметра"));
        assertTrue(diagnose("f = a.b => 3").contains("слева от '=>' ожидалось имя параметра"));
    }

    @Test
    @DisplayName("в скобках оказались не параметры — говорит сам разбор заголовка")
    void parensWithExpressionInside() {
        assertTrue(diagnose("f = (a + b) => c").contains("ожидалось имя параметра"));
    }
}
