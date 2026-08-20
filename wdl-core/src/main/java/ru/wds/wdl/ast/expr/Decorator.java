package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Один декоратор в записи {@code @[expression](args)}.
 * <p>
 * <b>Декоратор — не выражение</b>, и это то же решение, что у {@link Argument}:
 * запись осмысленна ровно в одном месте — перед объявлением, — а будь она выражением,
 * {@code x = @[log] + 1} пришлось бы запрещать проверкой в каждом посетителе.
 * Отдельный тип запрещает по построению, и следит за этим компилятор.
 * <p>
 * <b>Почему выражение стоит в квадратных скобках.</b> Без них {@code @t.deco()}
 * неразличимо: то ли декоратор {@code deco}, которому не передали аргументов, то ли
 * вызов, результат которого станет декоратором. Скобки разводят эти два написания —
 * {@code @[t.deco]()} и {@code @[t.deco()]}, — и заодно снимают ограничение на форму:
 * внутри лежит обычное выражение, любое. Python до версии 3.9 жил с урезанной
 * грамматикой ровно из-за этой неоднозначности.
 * <p>
 * Скобки с аргументами необязательны: {@code @[reg]} и {@code @[reg]()} — одна и та же
 * запись. Второй список аргументов декоратору взять неоткуда, так что двух толкований
 * тут не бывает.
 *
 * @param callee    выражение внутри {@code @[...]}; должно дать функцию — проверяется
 *                  при выполнении, потому что до него это неизвестно в принципе
 * @param arguments собственные аргументы декоратора; цель придёт нулевым аргументом
 *                  и здесь не лежит
 * @param span      место записи целиком: от {@code @} до закрывающей скобки
 */
public record Decorator(Expr callee, List<Argument> arguments, Span span) {

    public Decorator {
        Objects.requireNonNull(callee, "callee");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("@[").append(callee).append(']');
        if (!arguments.isEmpty()) {
            sb.append('(');
            for (int i = 0; i < arguments.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(arguments.get(i));
            }
            sb.append(')');
        }
        return sb.toString();
    }
}
