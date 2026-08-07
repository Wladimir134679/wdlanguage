package ru.wds.wdl.ast;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Вызов: {@code println("привет")}, {@code точка.строкой()}, {@code обработчики[0](x)}.
 * <p>
 * Вызывается <b>выражение</b>, а не имя. Это следствие того, что функция — обычное
 * значение: слева от скобок может стоять что угодно, что даёт функцию. В прошлой
 * реализации у вызова хранилось имя, и функции жили в отдельной от переменных
 * таблице — из-за этого функцию нельзя было ни передать аргументом, ни положить в поле.
 * <p>
 * Метода в языке нет как отдельного понятия: {@code точка.строкой()} — это обращение
 * по ключу, давшее функцию, и вызов результата. Два узла вместо особого случая.
 *
 * @param callee    выражение, дающее функцию
 * @param arguments аргументы в порядке записи
 * @param span      место в исходнике: от начала {@code callee} до закрывающей скобки
 */
public record CallExpr(Expr callee, List<Expr> arguments, Span span) implements Expr {

    public CallExpr {
        Objects.requireNonNull(callee, "callee");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", callee + "(", ")");
        arguments.forEach(argument -> joiner.add(argument.toString()));
        return joiner.toString();
    }
}
