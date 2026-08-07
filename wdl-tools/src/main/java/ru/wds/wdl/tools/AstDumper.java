package ru.wds.wdl.tools;

import ru.wds.wdl.ast.AccessExpr;
import ru.wds.wdl.ast.AccessStyle;
import ru.wds.wdl.ast.ArrayExpr;
import ru.wds.wdl.ast.AssignStmt;
import ru.wds.wdl.ast.BinaryExpr;
import ru.wds.wdl.ast.CallExpr;
import ru.wds.wdl.ast.ErrorExpr;
import ru.wds.wdl.ast.ErrorStmt;
import ru.wds.wdl.ast.Expr;
import ru.wds.wdl.ast.ExprStmt;
import ru.wds.wdl.ast.ExprVisitor;
import ru.wds.wdl.ast.LiteralExpr;
import ru.wds.wdl.ast.ObjectExpr;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.StmtVisitor;
import ru.wds.wdl.ast.TernaryExpr;
import ru.wds.wdl.ast.UnaryExpr;
import ru.wds.wdl.ast.VariableExpr;
import ru.wds.wdl.source.Span;

/**
 * Печать дерева с отступами — главный инструмент отладки грамматики.
 * <p>
 * Приоритеты операторов проверяются глазами именно так: если {@code 1 + 2 * 3}
 * напечаталось с умножением внутри сложения — таблица приоритетов верна.
 * <p>
 * Заодно это второй посетитель в проекте после интерпретатора, и написан он ровно
 * тем же способом: {@link ExprVisitor} с другим типом результата. Ни один узел
 * дерева ради него не менялся — в этом и смысл того, что диспетчеризация собрана
 * в одном месте, а узлы остаются данными.
 */
public final class AstDumper implements ExprVisitor<Void, Integer>, StmtVisitor<Void, Integer> {

    private final StringBuilder sb = new StringBuilder(256);
    private final boolean withSpans;

    private AstDumper(boolean withSpans) {
        this.withSpans = withSpans;
    }

    /** Дерево программы с указанием интервалов исходника у каждого узла. */
    public static String dump(Program program) {
        return dump(program, true);
    }

    public static String dump(Program program, boolean withSpans) {
        AstDumper dumper = new AstDumper(withSpans);
        dumper.line(0, "программа, инструкций: " + program.statements().size(), program.span());
        program.statements().forEach(statement -> dumper.visit(statement, 1));
        return dumper.sb.toString();
    }

    /** Дерево одного выражения — для REPL и тестов. */
    public static String dump(Expr expr) {
        return dump(expr, true);
    }

    public static String dump(Expr expr, boolean withSpans) {
        AstDumper dumper = new AstDumper(withSpans);
        dumper.visit(expr, 0);
        return dumper.sb.toString();
    }

    // --- инструкции ----------------------------------------------------------

    @Override
    public Void visitExprStmt(ExprStmt stmt, Integer depth) {
        return visit(stmt.expr(), depth);
    }

    @Override
    public Void visitAssign(AssignStmt stmt, Integer depth) {
        line(depth, "присваивание '" + stmt.op().symbol() + "'", stmt.span());
        visit(stmt.target(), depth + 1);
        return visit(stmt.value(), depth + 1);
    }

    @Override
    public Void visitErrorStmt(ErrorStmt stmt, Integer depth) {
        return line(depth, "<неразобранная инструкция>", stmt.span());
    }

    // --- выражения -----------------------------------------------------------

    @Override
    public Void visitLiteral(LiteralExpr expr, Integer depth) {
        return line(depth, "литерал " + expr.value(), expr);
    }

    @Override
    public Void visitVariable(VariableExpr expr, Integer depth) {
        return line(depth, "имя " + expr.name(), expr);
    }

    @Override
    public Void visitUnary(UnaryExpr expr, Integer depth) {
        line(depth, "унарная '" + expr.op().symbol() + "'", expr);
        return visit(expr.operand(), depth + 1);
    }

    @Override
    public Void visitBinary(BinaryExpr expr, Integer depth) {
        line(depth, "бинарная '" + expr.op().symbol() + "'", expr);
        visit(expr.left(), depth + 1);
        return visit(expr.right(), depth + 1);
    }

    @Override
    public Void visitTernary(TernaryExpr expr, Integer depth) {
        line(depth, "условное '?:'", expr);
        visit(expr.condition(), depth + 1);
        visit(expr.ifTrue(), depth + 1);
        return visit(expr.ifFalse(), depth + 1);
    }

    @Override
    public Void visitAccess(AccessExpr expr, Integer depth) {
        String style = expr.style() == AccessStyle.DOT ? "точка" : "скобки";
        line(depth, "обращение (" + style + ")", expr);
        visit(expr.target(), depth + 1);
        return visit(expr.key(), depth + 1);
    }

    @Override
    public Void visitCall(CallExpr expr, Integer depth) {
        line(depth, "вызов, аргументов: " + expr.arguments().size(), expr);
        visit(expr.callee(), depth + 1);
        expr.arguments().forEach(argument -> visit(argument, depth + 1));
        return null;
    }

    @Override
    public Void visitArray(ArrayExpr expr, Integer depth) {
        line(depth, "массив, элементов: " + expr.elements().size(), expr);
        expr.elements().forEach(element -> visit(element, depth + 1));
        return null;
    }

    @Override
    public Void visitObject(ObjectExpr expr, Integer depth) {
        line(depth, "объект, пар: " + expr.entries().size(), expr);
        for (ObjectExpr.Entry entry : expr.entries()) {
            visit(entry.key(), depth + 1);
            visit(entry.value(), depth + 2);
        }
        return null;
    }

    @Override
    public Void visitError(ErrorExpr expr, Integer depth) {
        return line(depth, "<неразобранное выражение>", expr);
    }

    private Void line(int depth, String text, Expr expr) {
        return line(depth, text, expr.span());
    }

    private Void line(int depth, String text, Span span) {
        sb.append("  ".repeat(depth)).append(text);
        if (withSpans) {
            sb.append("  [").append(span).append(']');
        }
        sb.append('\n');
        return null;
    }
}
