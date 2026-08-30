package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.MatchExpr;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.ExprStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор {@code match}: форма дерева, обе позиции, диагностика и восстановление. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class MatchParserTest {

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

    /** Форма дерева того выражения, которое стоит в правой части присваивания. */
    private static String value(String code) {
        AssignStmt assign = assertInstanceOf(AssignStmt.class, single(code));
        return SExprPrinter.print(assign.value());
    }

    /** Форма дерева match, стоящего инструкцией. */
    private static String statement(String code) {
        ExprStmt stmt = assertInstanceOf(ExprStmt.class, single(code));
        assertInstanceOf(MatchExpr.class, stmt.expr());
        return SExprPrinter.print(stmt.expr());
    }

    private static String diagnose(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), "ожидалась ошибка разбора");
        return diagnostics.renderAll();
    }

    // --- форма дерева --------------------------------------------------------

    @Test
    @DisplayName("голый образец — это хвост с неявным '=='")
    void barePatternIsEqual() {
        assertEquals("(match code (case (== 1) => \"one\") (else => \"other\"))",
                value("""
                        text = match (code) {
                            case 1 => "one"
                            else => "other"
                        }
                        """));
    }

    @Test
    @DisplayName("образец с оператором берётся из той же таблицы, что и выражения")
    void operatorPatterns() {
        assertEquals("(match cpu (case (> 90) => \"critical\") (else => \"fine\"))",
                value("""
                        level = match (cpu) {
                            case > 90 => "critical"
                            else => "fine"
                        }
                        """));
        assertEquals("(match figure (case (is Circle) => \"circle\") (else => \"other\"))",
                value("""
                        kind = match (figure) {
                            case is Circle => "circle"
                            else => "other"
                        }
                        """));
        assertEquals("(match role (case (in admins) => true) (else => false))",
                value("""
                        allowed = match (role) {
                            case in admins => true
                            else => false
                        }
                        """));
        assertEquals("(match text (case (!in banned) => true) (else => false))",
                value("""
                        ok = match (text) {
                            case !in banned => true
                            else => false
                        }
                        """));
        assertEquals("(match age (case (in (.. 18 65)) => \"working\") (else => \"other\"))",
                value("""
                        group = match (age) {
                            case in 18..65 => "working"
                            else => "other"
                        }
                        """));
    }

    @Test
    @DisplayName("перечисление образцов заменяет провал через пустую метку")
    void severalPatternsInOneCase() {
        assertEquals("(match n (case (== 1) (== 2) (== 3) => \"few\") (else => \"many\"))",
                value("""
                        size = match (n) {
                            case 1, 2, 3 => "few"
                            else => "many"
                        }
                        """));
        assertEquals("(match n (case (< 0) (> 100) => \"outside\") (else => \"inside\"))",
                value("""
                        where = match (n) {
                            case < 0, > 100 => "outside"
                            else => "inside"
                        }
                        """));
    }

    @Test
    @DisplayName("условие пишется после образцов и бывает без них")
    void guards() {
        assertEquals("(match cpu (case (> 90) (if (! throttled)) => \"critical\") (else => \"fine\"))",
                value("""
                        level = match (cpu) {
                            case > 90 if !throttled => "critical"
                            else => "fine"
                        }
                        """));
        assertEquals("(match cpu (case (if throttled) => \"slowed\") (else => \"fine\"))",
                value("""
                        level = match (cpu) {
                            case if throttled => "slowed"
                            else => "fine"
                        }
                        """));
    }

    @Test
    @DisplayName("в позиции инструкции ветка бывает блоком, а else не обязателен")
    void statementPosition() {
        assertEquals("(match code (case (== 1) {}))",
                statement("""
                        match (code) {
                            case 1 { println("one") }
                        }
                        """));
        assertEquals("(match code (case (== 1) => (call println \"one\")))",
                statement("""
                        match (code) {
                            case 1 => println("one")
                        }
                        """));
    }

    @Test
    @DisplayName("после '=>' фигурная скобка — объект, после образца — блок")
    void braceAfterArrowIsObject() {
        assertEquals("(match code (case (== 1) => (object (\"a\" 1))) (else => (object)))",
                value("""
                        r = match (code) {
                            case 1 => {a: 1}
                            else => {}
                        }
                        """));
        assertEquals("(match code (case (== 1) {}))",
                statement("""
                        match (code) {
                            case 1 {a = 1}
                        }
                        """));
    }

    @Test
    @DisplayName("match — обычное выражение: он вкладывается и передаётся аргументом")
    void matchIsAnExpression() {
        assertEquals("(match a (case (== 1) => (match b (case (== 2) => \"x\") (else => \"y\"))) (else => \"z\"))",
                value("""
                        r = match (a) {
                            case 1 => match (b) { case 2 => "x" else => "y" }
                            else => "z"
                        }
                        """));
        ExprStmt stmt = assertInstanceOf(ExprStmt.class, single("""
                println(match (a) { case 1 => "one" else => "other" })
                """));
        assertEquals("(call println (match a (case (== 1) => \"one\") (else => \"other\")))",
                SExprPrinter.print(stmt.expr()));
    }

    // --- диагностика ---------------------------------------------------------

    @Test
    @DisplayName("полное выражение в образце — ошибка, называющая причину")
    void wholeExpressionInPatternIsRejected() {
        assertTrue(diagnose("""
                text = match (score) {
                    case score > 90 => "high"
                    else => "low"
                }
                """).contains("слева от '>' не нужен операнд"));
        // Скобки снимают запрет: это осознанное сравнение предмета с логическим.
        assertEquals("(match flag (case (== (> a b)) => \"yes\") (else => \"no\"))",
                value("""
                        text = match (flag) {
                            case (a > b) => "yes"
                            else => "no"
                        }
                        """));
    }

    @Test
    @DisplayName("в позиции выражения ветка-блок отдаёт значение через yield, а else обязателен")
    void valuePositionRequiresYieldAndElse() {
        // Блок в позиции выражения законен — но обязан отдать значение.
        assertEquals("(match code (case (== 1) {}) (else => \"other\"))",
                value("""
                        text = match (code) {
                            case 1 {
                                prepared = prepare(code)
                                yield prepared
                            }
                            else => "other"
                        }
                        """));
        assertTrue(diagnose("""
                text = match (code) {
                    case 1 { println("one") }
                    else => "other"
                }
                """).contains("ветка не отдаёт значения"));
        assertTrue(diagnose("""
                text = match (code) {
                    case 1 => "one"
                }
                """).contains("'match' в позиции выражения обязан иметь 'else'"));
    }

    @Test
    @DisplayName("ветка 'else' в позиции выражения тоже обязана отдать значение")
    void otherwiseMustYieldToo() {
        assertTrue(diagnose("""
                text = match (code) {
                    case 1 => "one"
                    else { println("нет") }
                }
                """).contains("ветка не отдаёт значения"));
    }

    @Test
    @DisplayName("'yield' допустим только в ветке-значении и не через границу функции")
    void yieldOnlyInValueBranch() {
        String outside = "'yield' допустим только в ветке 'case'";
        // На верхнем уровне.
        assertTrue(diagnose("yield 1").contains(outside));
        // В ветке match-инструкции: значения такая ветка не даёт.
        assertTrue(diagnose("""
                match (code) {
                    case 1 { yield "one" }
                }
                """).contains(outside));
        // Через границу функции: из ветки нельзя отдать значение изнутри def.
        assertTrue(diagnose("""
                text = match (code) {
                    case 1 {
                        def inner() { yield "one" }
                        yield inner()
                    }
                    else => "other"
                }
                """).contains(outside));
        // Вложенный match-инструкция внутри ветки-значения: 'yield' читается как
        // «значение этого case», а этот case значения не даёт — молча уводить его
        // во внешнюю ветку нельзя.
        assertTrue(diagnose("""
                text = match (a) {
                    case 1 {
                        match (b) {
                            case 2 { yield "x" }
                        }
                        yield "z"
                    }
                    else => "other"
                }
                """).contains(outside));
    }

    @Test
    @DisplayName("'yield' в finally внутри ветки запрещён, а match внутри finally — нет")
    void yieldAndFinally() {
        assertTrue(diagnose("""
                text = match (code) {
                    case 1 {
                        try { risky() } finally { yield "нет" }
                    }
                    else => "other"
                }
                """).contains("'yield' в блоке 'finally' запрещён"));
        // А вот match, целиком написанный внутри finally, законен: его yield дальше
        // своей ветки не идёт и гасить ему нечего.
        assertEquals(1, parse("""
                def f() {
                    try {
                        risky()
                    } finally {
                        label = match (code) {
                            case 1 { yield "one" }
                            else => "other"
                        }
                        log(label)
                    }
                }
                """).statements().size());
    }

    @Test
    @DisplayName("'yield' не требует точки с запятой: значение у него есть всегда")
    void yieldNeedsNoSemicolon() {
        assertEquals("(match code (case (== 1) {}) (else => 0))",
                value("""
                        n = match (code) {
                            case 1 {
                                yield 5
                            }
                            else => 0
                        }
                        """));
    }

    @Test
    @DisplayName("после 'case' нужен образец или условие")
    void emptyCase() {
        assertTrue(diagnose("""
                match (code) {
                    case => println("one")
                }
                """).contains("после 'case' нужен образец или условие 'if'"));
    }

    @Test
    @DisplayName("ошибка в одной ветке не съедает остальные")
    void recoveryStopsAtNextCase() {
        String rendered = diagnose("""
                match (code) {
                    case score > 90 => println("high")
                    case 1 => println("one")
                    case 2 => println("two")
                }
                """);
        assertTrue(rendered.contains("слева от '>' не нужен операнд"), rendered);
        // Единственная ошибка: разбор следующих веток не пострадал.
        assertEquals(1, rendered.lines().filter(line -> line.contains("не нужен операнд")).count(),
                rendered);
        assertFalse(rendered.contains("ожидалось выражение"), rendered);
    }

    @Test
    @DisplayName("веток 'else' у match не бывает двух")
    void onlyOneElse() {
        assertTrue(diagnose("""
                match (code) {
                    case 1 => println("one")
                    else => println("a")
                    else => println("b")
                }
                """).contains("только одна ветка 'else'"));
    }

    @Test
    @DisplayName("телом ветки-инструкции не бывает выражение без действия")
    void statementBranchMustDoSomething() {
        assertTrue(diagnose("""
                match (code) {
                    case 1 => a + 1
                }
                """).contains("это выражение ничего не делает"));
    }

    @Test
    @DisplayName("предмет обязателен: match без скобок не разбирается")
    void subjectIsMandatory() {
        assertTrue(diagnose("""
                match {
                    case 1 => println("one")
                }
                """).contains("открывающую скобку '(' после 'match'"));
    }

    @Test
    @DisplayName("после точки 'match' и 'case' остаются именами членов")
    void keywordsAfterDotStayNames() {
        Expr expr = assertInstanceOf(ExprStmt.class, single("println(answer.match)"))
                .expr();
        assertEquals("(call println (get answer \"match\"))", SExprPrinter.print(expr));
    }
}
