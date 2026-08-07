package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Цикл с предусловием: {@code while (условие) ...}.
 * <p>
 * Условие проверяется перед каждым проходом, поэтому тело может не выполниться ни разу.
 * Тело — обычная {@link Stmt}: блок или одна инструкция.
 *
 * @param condition условие продолжения
 * @param body      тело цикла
 * @param span      место в исходнике целиком
 */
public record WhileStmt(Expr condition, Stmt body, Span span) implements Stmt {

    public WhileStmt {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "while (" + condition + ") ...";
    }
}
