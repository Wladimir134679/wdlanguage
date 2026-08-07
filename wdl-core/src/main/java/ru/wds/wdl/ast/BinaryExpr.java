package ru.wds.wdl.ast;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Двухместная операция: {@code a + b}, {@code a < b}, {@code a && b}.
 * <p>
 * Приоритеты и ассоциативность разобраны парсером и в дереве уже не хранятся:
 * {@code 1 + 2 * 3} и {@code 1 + (2 * 3)} дают одинаковые узлы. Скобки тоже
 * не сохраняются — форматтер, когда появится, расставит их сам по приоритетам.
 *
 * @param op    операция
 * @param left  левый операнд
 * @param right правый операнд
 * @param span  место в исходнике: от начала левого операнда до конца правого
 */
public record BinaryExpr(BinaryOp op, Expr left, Expr right, Span span) implements Expr {

    public BinaryExpr {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "(" + left + " " + op.symbol() + " " + right + ")";
    }
}
