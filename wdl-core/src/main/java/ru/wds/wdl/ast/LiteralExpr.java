package ru.wds.wdl.ast;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;

import java.util.Objects;

/**
 * Литерал: число, строка, {@code true}, {@code false}, {@code null}.
 * <p>
 * Текст токена разбирается в {@link Value} один раз, при построении дерева. Иначе
 * {@code Long.parseLong} пришлось бы звать на каждой итерации цикла — и по той же
 * причине здесь лежит готовое значение, а не строка.
 * <p>
 * Это единственное место, где дерево знает о типах времени выполнения. Зависимость
 * осознанная: свёртка констант и любой другой проход по дереву должны уметь
 * подставить в него вычисленное значение, не выдумывая для этого второй набор типов.
 *
 * @param value готовое значение литерала
 * @param span  место в исходнике
 */
public record LiteralExpr(Value value, Span span) implements Expr {

    public LiteralExpr {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
