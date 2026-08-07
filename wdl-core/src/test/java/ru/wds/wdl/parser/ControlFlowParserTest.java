package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.ObjectExpr;
import ru.wds.wdl.ast.stmt.*;
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

/** Разбор ветвлений и циклов: форма дерева, границы конструкций, восстановление после ошибок. */
class ControlFlowParserTest {

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

    // --- блок ----------------------------------------------------------------

    @Test
    @DisplayName("фигурная скобка в начале инструкции — блок, а в позиции выражения — объект")
    void braceMeansBlockAtStatementStart() {
        BlockStmt block = assertInstanceOf(BlockStmt.class, single("{ println(1); println(2) }"));
        assertEquals(2, block.statements().size());

        AssignStmt assign = assertInstanceOf(AssignStmt.class, single("x = {a: 1}"));
        assertInstanceOf(ObjectExpr.class, assign.value());
    }

    @Test
    @DisplayName("пустой блок разбирается и остаётся пустым")
    void emptyBlock() {
        assertTrue(assertInstanceOf(BlockStmt.class, single("{}")).isEmpty());
        assertTrue(assertInstanceOf(BlockStmt.class, single("{ ;; }")).isEmpty());
    }

    // --- ветвление -----------------------------------------------------------

    @Test
    @DisplayName("if с блоком, с одной инструкцией и с else")
    void ifForms() {
        IfStmt withBlock = assertInstanceOf(IfStmt.class, single("if (x) { println(1) }"));
        assertInstanceOf(BlockStmt.class, withBlock.thenBranch());
        assertFalse(withBlock.hasElse());

        IfStmt oneLine = assertInstanceOf(IfStmt.class, single("if (x) println(1)"));
        assertInstanceOf(ExprStmt.class, oneLine.thenBranch());

        IfStmt withElse = assertInstanceOf(IfStmt.class, single("if (x) { a() } else { b() }"));
        assertInstanceOf(BlockStmt.class, withElse.elseBranch());
    }

    @Test
    @DisplayName("else if — это if в иначе-ветви, без особого узла")
    void elseIfIsNestedIf() {
        IfStmt first = assertInstanceOf(IfStmt.class, single("""
                if (a) { одно() }
                else if (b) { два() }
                else { три() }
                """));

        IfStmt second = assertInstanceOf(IfStmt.class, first.elseBranch());
        assertInstanceOf(BlockStmt.class, second.elseBranch());
    }

    @Test
    @DisplayName("else достаётся ближайшему if")
    void danglingElseBindsToNearestIf() {
        IfStmt outer = assertInstanceOf(IfStmt.class, single("if (a) if (b) x() else y()"));

        assertFalse(outer.hasElse(), "else не должен был уйти внешнему if");
        assertTrue(assertInstanceOf(IfStmt.class, outer.thenBranch()).hasElse());
    }

    @Test
    @DisplayName("условие требует скобок, а присваивание в него не вписать")
    void conditionRules() {
        assertTrue(diagnose("if x { println(1) }").renderAll().contains("открывающую скобку '('"));
        assertTrue(diagnose("if (x = 5) { println(1) }").hasErrors());
    }

    // --- циклы ---------------------------------------------------------------

    @Test
    @DisplayName("while разбирается с блоком и с одной инструкцией")
    void whileForms() {
        assertInstanceOf(BlockStmt.class, assertInstanceOf(WhileStmt.class,
                single("while (x) { i += 1 }")).body());
        assertInstanceOf(AssignStmt.class, assertInstanceOf(WhileStmt.class,
                single("while (x) i += 1")).body());
    }

    @Test
    @DisplayName("у for три части, и каждая может отсутствовать")
    void forParts() {
        ForStmt full = assertInstanceOf(ForStmt.class, single("for (i = 0; i < 10; i += 1) { println(i) }"));
        assertInstanceOf(AssignStmt.class, full.init());
        assertNotNull(full.condition());
        assertInstanceOf(AssignStmt.class, full.step());

        ForStmt endless = assertInstanceOf(ForStmt.class, single("for (;;) { break }"));
        assertNull(endless.init());
        assertNull(endless.condition(), "пропущенное условие не должно подменяться литералом");
        assertNull(endless.step());

        ForStmt onlyCondition = assertInstanceOf(ForStmt.class, single("for (; i < 3;) { i += 1 }"));
        assertNull(onlyCondition.init());
        assertNotNull(onlyCondition.condition());
        assertNull(onlyCondition.step());
    }

    @Test
    @DisplayName("for ... in разбирается в перебор, а не в цикл со счётчиком")
    void forEachIsRecognized() {
        ForEachStmt each = assertInstanceOf(ForEachStmt.class, single("for (товар in корзина) { println(товар) }"));

        assertEquals("товар", each.name());
        assertEquals("корзина", SExprPrinter.print(each.iterable()));
    }

    @Test
    @DisplayName("перебирать можно любое выражение, а не только имя")
    void forEachOverExpression() {
        ForEachStmt each = assertInstanceOf(ForEachStmt.class, single("for (x in склад.строки[0]) println(x)"));
        assertEquals("(get (get склад \"строки\") 0)", SExprPrinter.print(each.iterable()));
    }

    @Test
    @DisplayName("break и continue разбираются внутри цикла")
    void breakAndContinueInsideLoop() {
        WhileStmt loop = assertInstanceOf(WhileStmt.class, single("while (x) { break }"));
        BlockStmt body = assertInstanceOf(BlockStmt.class, loop.body());
        assertInstanceOf(BreakStmt.class, body.statements().get(0));

        ForStmt counted = assertInstanceOf(ForStmt.class, single("for (i = 0; i < 3; i += 1) continue"));
        assertInstanceOf(ContinueStmt.class, counted.body());
    }

    @Test
    @DisplayName("break вне цикла — ошибка разбора, а не сюрприз при выполнении")
    void breakOutsideLoopIsRejected() {
        assertTrue(diagnose("break").renderAll().contains("только внутри цикла"));
        assertTrue(diagnose("if (x) { continue }").renderAll().contains("только внутри цикла"));
        // Ветвление внутри цикла своей глубины не теряет.
        assertFalse(diagnose("while (x) { if (y) { break } }").hasErrors());
    }

    // --- ошибки и восстановление ---------------------------------------------

    @Test
    @DisplayName("испорченная строка внутри блока не съедает закрывающую скобку")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void recoveryStopsAtBlockEnd() {
        Source source = Source.ofString("""
                if (x) { a + 1 }
                println("живо")
                """);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertEquals(2, program.statements().size(), "вторая инструкция должна была уцелеть");
        assertInstanceOf(ExprStmt.class, program.statements().get(1));
    }

    @Test
    @DisplayName("незакрытый блок и цикл без тела не завешивают разбор")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void brokenConstructsDoNotHang() {
        assertTrue(diagnose("if (x) { println(1)").renderAll().contains("закрывающую скобку '}'"));
        assertTrue(diagnose("while (x)").renderAll().contains("ожидалось тело"));
        assertTrue(diagnose("for (i = 0; i < 3 i += 1) { }").hasErrors());
        assertTrue(diagnose("for (x in) { }").hasErrors());
    }
}
