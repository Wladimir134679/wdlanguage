package ru.wds.wdl.ast.expr;

import ru.wds.wdl.ast.op.UnaryOp;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Префиксная операция: {@code -x}, {@code !флаг}, {@code ~маска}.
 *
 * @param op      операция
 * @param operand операнд
 * @param span    место в исходнике, включая знак операции
 */
public record UnaryExpr(UnaryOp op, Expr operand, Span span) implements Expr {

    public UnaryExpr {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return op.symbol() + operand;
    }
}
