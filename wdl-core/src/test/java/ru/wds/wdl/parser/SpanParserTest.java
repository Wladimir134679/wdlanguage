package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.ErrorExpr;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.ErrorStmt;
import ru.wds.wdl.ast.stmt.ExprStmt;
import ru.wds.wdl.ast.stmt.IfStmt;
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
 * Интервалы узлов на недописанном тексте: узел покрывает ровно то, что парсер прочитал.
 * <p>
 * Проверяется здесь то, из-за чего редактор не может работать с обычным парсером:
 * человек почти всегда правит незаконченный файл, и узел-ошибка, забравший чужой
 * токен, ломает и «узел под курсором», и построение PSI по интервалам.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SpanParserTest {

    private static Program broken(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для:\n" + code);
        return program;
    }

    @Test
    @DisplayName("недописанное 'obj.' даёт пустой узел сразу за точкой")
    void unfinishedAccess() {
        Program program = broken("x = obj.");

        AssignStmt statement = assertInstanceOf(AssignStmt.class, program.statements().get(0));
        AccessExpr access = assertInstanceOf(AccessExpr.class, statement.value());
        ErrorExpr key = assertInstanceOf(ErrorExpr.class, access.key());

        assertEquals(new Span(8, 8), key.span(), "ключ — точка после точки, а не чужой токен");
        assertEquals(new Span(4, 8), access.span(), "обращение кончается на точке");
        assertTrue(contains(access.span(), key.span()), "ключ обязан лежать внутри обращения");
    }

    @Test
    @DisplayName("'obj.' не забирает закрывающую скобку блока")
    void unfinishedAccessKeepsBlock() {
        String code = "{ x = obj.\n}";
        Program program = broken(code);

        BlockStmt block = assertInstanceOf(BlockStmt.class, program.statements().get(0));
        AssignStmt statement = assertInstanceOf(AssignStmt.class, block.statements().get(0));
        AccessExpr access = assertInstanceOf(AccessExpr.class, statement.value());

        assertEquals(new Span(10, 10), assertInstanceOf(ErrorExpr.class, access.key()).span());
        assertEquals(new Span(0, code.length()), block.span(), "блок закрылся своей скобкой");
        assertTrue(contains(block.span(), statement.span()), "присваивание лежит внутри блока");
    }

    @Test
    @DisplayName("перевод строки после точки не делит обращение: 'obj.\\nfoo()' — вызов метода")
    void accessSpansNewline() {
        Source source = Source.ofString("obj.\nfoo()");
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);

        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        assertEquals(1, program.statements().size());
        ExprStmt statement = assertInstanceOf(ExprStmt.class, program.statements().get(0));
        CallExpr call = assertInstanceOf(CallExpr.class, statement.expr());
        assertInstanceOf(AccessExpr.class, call.callee());
    }

    @Test
    @DisplayName("выражение, которого нет, занимает точку перед несъеденным токеном")
    void missingExpression() {
        Program program = broken("x = )");

        ErrorStmt statement = assertInstanceOf(ErrorStmt.class, program.statements().get(0));
        assertEquals(new Span(0, 4), statement.span(), "инструкция кончается там, где ждали значение");
    }

    @Test
    @DisplayName("тело, которого нет, не забирает закрывающую скобку блока")
    void missingBody() {
        Program program = broken("{ if (x) }");

        BlockStmt block = assertInstanceOf(BlockStmt.class, program.statements().get(0));
        IfStmt conditional = assertInstanceOf(IfStmt.class, block.statements().get(0));
        ErrorStmt body = assertInstanceOf(ErrorStmt.class, conditional.thenBranch());

        assertEquals(new Span(9, 9), body.span(), "тело — точка перед '}'");
        assertTrue(contains(block.span(), conditional.span()),
                "'if' обязан лежать внутри блока, а не кончаться его скобкой");
    }

    @Test
    @DisplayName("'new' без имени класса не забирает следующий токен")
    void missingClassName() {
        Program program = broken("x = new )");

        ErrorStmt statement = assertInstanceOf(ErrorStmt.class, program.statements().get(0));
        assertEquals(new Span(0, 8), statement.span());
    }

    private static boolean contains(Span outer, Span inner) {
        return outer.start() <= inner.start() && inner.end() <= outer.end();
    }
}
