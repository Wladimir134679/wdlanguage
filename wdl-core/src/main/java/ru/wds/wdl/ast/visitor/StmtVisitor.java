package ru.wds.wdl.ast.visitor;

import ru.wds.wdl.ast.stmt.*;

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

    R visitBlock(BlockStmt stmt, C context);

    R visitIf(IfStmt stmt, C context);

    R visitWhile(WhileStmt stmt, C context);

    R visitFor(ForStmt stmt, C context);

    R visitForEach(ForEachStmt stmt, C context);

    R visitBreak(BreakStmt stmt, C context);

    R visitContinue(ContinueStmt stmt, C context);

    R visitConstDecl(ConstDeclStmt stmt, C context);

    R visitDefDecl(DefDeclStmt stmt, C context);

    R visitClassDecl(ClassDeclStmt stmt, C context);

    R visitTraitDecl(TraitDeclStmt stmt, C context);

    R visitImport(ImportStmt stmt, C context);

    R visitReturn(ReturnStmt stmt, C context);

    R visitThrow(ThrowStmt stmt, C context);

    R visitTry(TryStmt stmt, C context);

    /** Отложенное действие: выполняется на выходе из своей области видимости. */
    R visitDefer(DeferStmt stmt, C context);

    /** Работа с ресурсом: значение закрывается на любом выходе из тела. */
    R visitUse(UseStmt stmt, C context);

    R visitErrorStmt(ErrorStmt stmt, C context);

    /** Точка входа: направляет инструкцию нужному методу. */
    default R visit(Stmt stmt, C context) {
        return switch (stmt) {
            case ExprStmt s -> visitExprStmt(s, context);
            case AssignStmt s -> visitAssign(s, context);
            case BlockStmt s -> visitBlock(s, context);
            case IfStmt s -> visitIf(s, context);
            case WhileStmt s -> visitWhile(s, context);
            case ForStmt s -> visitFor(s, context);
            case ForEachStmt s -> visitForEach(s, context);
            case BreakStmt s -> visitBreak(s, context);
            case ContinueStmt s -> visitContinue(s, context);
            case ConstDeclStmt s -> visitConstDecl(s, context);
            case DefDeclStmt s -> visitDefDecl(s, context);
            case ClassDeclStmt s -> visitClassDecl(s, context);
            case TraitDeclStmt s -> visitTraitDecl(s, context);
            case ImportStmt s -> visitImport(s, context);
            case ReturnStmt s -> visitReturn(s, context);
            case ThrowStmt s -> visitThrow(s, context);
            case TryStmt s -> visitTry(s, context);
            case DeferStmt s -> visitDefer(s, context);
            case UseStmt s -> visitUse(s, context);
            case ErrorStmt s -> visitErrorStmt(s, context);
        };
    }
}
