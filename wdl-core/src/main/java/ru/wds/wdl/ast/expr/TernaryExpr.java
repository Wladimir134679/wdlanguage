package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Условное выражение: {@code условие ? тогда : иначе}.
 * <p>
 * Вычисляется ровно одна из веток — как у {@code &&} и {@code ||}. Отдельным видом
 * узла, а не тремя вложенными: у него три операнда, и втискивать его в
 * {@link BinaryExpr} значило бы кодировать смысл в структуре, которую потом
 * пришлось бы расшифровывать в каждом обходе.
 *
 * @param condition условие
 * @param ifTrue    ветка для истинного условия
 * @param ifFalse   ветка для ложного
 * @param span      место в исходнике целиком
 */
public record TernaryExpr(Expr condition, Expr ifTrue, Expr ifFalse, Span span) implements Expr {

    public TernaryExpr {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(ifTrue, "ifTrue");
        Objects.requireNonNull(ifFalse, "ifFalse");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "(" + condition + " ? " + ifTrue + " : " + ifFalse + ")";
    }
}
