package ru.wds.wdl.ast;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Литерал массива: {@code [1, 2, "три", [4]]}.
 * <p>
 * Каждое вычисление такого выражения создаёт новый массив — литерал описывает,
 * как построить значение, а не само значение. Поэтому в дереве он остаётся
 * узлом даже когда все элементы константны: свёртка констант не имеет права
 * превратить его в один общий разделяемый массив.
 *
 * @param elements элементы в порядке записи
 * @param span     место в исходнике вместе со скобками
 */
public record ArrayExpr(List<Expr> elements, Span span) implements Expr {

    public ArrayExpr {
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        elements.forEach(element -> joiner.add(element.toString()));
        return joiner.toString();
    }
}
