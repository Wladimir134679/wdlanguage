package ru.wds.wdl.ast;

/**
 * Посетитель инструкций — брат {@link ExprVisitor} и устроен так же.
 * <p>
 * Отдельный интерфейс, а не общий на всё дерево, потому что результаты у них разные
 * по своей природе: выражение даёт значение, инструкция — нет. Интерпретатор
 * реализует оба: {@code ExprVisitor<Value, ExecutionContext>} и
 * {@code StmtVisitor<Void, ExecutionContext>}.
 *
 * @param <R> тип результата обхода
 * @param <C> тип контекста
 */
public interface StmtVisitor<R, C> {

    R visitExprStmt(ExprStmt stmt, C context);

    R visitAssign(AssignStmt stmt, C context);

    R visitErrorStmt(ErrorStmt stmt, C context);

    /** Точка входа: направляет инструкцию нужному методу. */
    default R visit(Stmt stmt, C context) {
        return switch (stmt) {
            case ExprStmt s -> visitExprStmt(s, context);
            case AssignStmt s -> visitAssign(s, context);
            case ErrorStmt s -> visitErrorStmt(s, context);
        };
    }
}
