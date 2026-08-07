package ru.wds.wdl.ast;

/**
 * Посетитель выражений: одна операция над деревом целиком.
 * <p>
 * Так устроены интерпретатор ({@code R = Value}, {@code C = ExecutionContext}),
 * печать дерева ({@code R = String}), будущие оптимизатор ({@code R = Expr}),
 * линтер и резолвер имён. Тип результата и тип контекста параметризованы именно
 * поэтому: нести контекст в поле посетителя нельзя — тогда посетитель становится
 * одноразовым и непригодным для рекурсии с разными окружениями.
 * <p>
 * <b>Почему диспетчер здесь, а не в узлах.</b> В классическом посетителе каждый
 * узел обязан иметь свой {@code accept}, и добавление узла правит весь список
 * классов. Здесь узлы — {@code record}'ы без единой строки логики, а разбор по видам
 * собран в {@link #visit(Expr, Object)}: {@code switch} по {@code sealed} иерархии
 * компилятор проверяет на полноту, так что забытая ветка — ошибка сборки, а не
 * {@code UnsupportedOperationException} в рантайме.
 *
 * @param <R> тип результата обхода
 * @param <C> тип контекста, который передаётся по дереву вниз
 */
public interface ExprVisitor<R, C> {

    R visitLiteral(LiteralExpr expr, C context);

    R visitVariable(VariableExpr expr, C context);

    R visitUnary(UnaryExpr expr, C context);

    R visitBinary(BinaryExpr expr, C context);

    R visitTernary(TernaryExpr expr, C context);

    R visitAccess(AccessExpr expr, C context);

    R visitCall(CallExpr expr, C context);

    R visitArray(ArrayExpr expr, C context);

    R visitObject(ObjectExpr expr, C context);

    /**
     * Узел-заглушка на месте синтаксической ошибки. Реализовать обязательно:
     * инструменты (форматтер, подсветка, LSP) работают и с битым деревом,
     * а интерпретатор такое дерево просто не должен получать.
     */
    R visitError(ErrorExpr expr, C context);

    /** Точка входа: направляет выражение нужному методу. */
    default R visit(Expr expr, C context) {
        return switch (expr) {
            case LiteralExpr e -> visitLiteral(e, context);
            case VariableExpr e -> visitVariable(e, context);
            case UnaryExpr e -> visitUnary(e, context);
            case BinaryExpr e -> visitBinary(e, context);
            case TernaryExpr e -> visitTernary(e, context);
            case AccessExpr e -> visitAccess(e, context);
            case CallExpr e -> visitCall(e, context);
            case ArrayExpr e -> visitArray(e, context);
            case ObjectExpr e -> visitObject(e, context);
            case ErrorExpr e -> visitError(e, context);
        };
    }
}
