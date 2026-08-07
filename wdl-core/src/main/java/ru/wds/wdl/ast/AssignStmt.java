package ru.wds.wdl.ast;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Присваивание: {@code счёт = 0}, {@code счёт += 5}, {@code точка.x = 10},
 * {@code массив[0] = "первый"}.
 * <p>
 * Цель — выражение, но не любое: слева может стоять либо имя ({@link VariableExpr}),
 * либо обращение ({@link AccessExpr}). Проверяет это парсер, поэтому в дереве
 * недопустимой цели не бывает. Тип цели остался {@link Expr} специально: обращение
 * слева и справа от {@code =} — это буквально один и тот же узел, и как только
 * появятся новые контейнеры, присваивание в них заработает само.
 *
 * @param target цель записи: имя или обращение
 * @param op     вид присваивания
 * @param value  выражение, дающее новое значение
 * @param span   место в исходнике целиком
 */
public record AssignStmt(Expr target, AssignOp op, Expr value, Span span) implements Stmt {

    public AssignStmt {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return target + " " + op.symbol() + " " + value;
    }
}
