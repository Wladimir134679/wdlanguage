package ru.wds.wdl.ast.expr;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.visitor.ExprVisitor;

/**
 * Выражение — узел, вычисление которого даёт значение.
 * <p>
 * Список наследников закрыт ({@code sealed}), и это главный инструмент контроля
 * в проекте: и {@link ExprVisitor}, и любой {@code switch} по выражению перестанут
 * компилироваться, стоит добавить сюда новый вид узла и забыть его где-нибудь
 * обработать.
 * <p>
 * Все узлы — {@code record}'ы: они неизменяемы, у них даром есть {@code equals},
 * {@code hashCode} и разбор через pattern matching. Преобразования дерева
 * (свёртка констант, инлайн) не правят узел, а возвращают новый — так же,
 * как это делает {@link String}.
 */
public sealed interface Expr extends Node
        permits LiteralExpr, VariableExpr, UnaryExpr, BinaryExpr, TernaryExpr,
                AccessExpr, CallExpr, NewExpr, ArrayExpr, ObjectExpr, FunctionExpr, TryExpr,
                ErrorExpr {

    /**
     * Принимает посетителя. Метод оставлен для симметрии с классическим паттерном
     * и для удобства вызова: вся диспетчеризация на самом деле живёт в одном месте —
     * в {@link ExprVisitor#visit(Expr, Object)}, поэтому новый вид посетителя не требует
     * правки ни одного узла.
     */
    default <R, C> R accept(ExprVisitor<R, C> visitor, C context) {
        return visitor.visit(this, context);
    }
}
