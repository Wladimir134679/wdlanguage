package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Аргумент в скобках: {@code f(2)} или {@code f(count: 2)}.
 * <p>
 * <b>Аргумент — не выражение</b>, и это то же решение, что «присваивание — инструкция».
 * Запись {@code count: 2} осмысленна ровно в одном месте — непосредственно в списке
 * аргументов; будь она выражением, {@code g(x: 1) + 1} пришлось бы запрещать проверкой
 * в каждом посетителе. Отдельный тип запрещает по построению, и компилятор следит
 * за этим вместо человека.
 * <p>
 * Один вид узла на обе формы, а не два: позиционный аргумент — это тот же аргумент
 * без имени. Так же устроено {@linkplain AccessExpr обращение}, где {@code a.b}
 * и {@code a["b"]} — один узел с пометкой о форме записи.
 * <p>
 * Имя хранится вместе со своим местом в исходнике: сообщение «функция 'greet'
 * не принимает параметра 'greetng'» обязано подчеркнуть имя, а не весь вызов.
 *
 * @param name     имя параметра или {@code null} у позиционного аргумента
 * @param nameSpan место имени в исходнике; {@code null}, если имени нет
 * @param value    выражение аргумента
 */
public record Argument(String name, Span nameSpan, Expr value) {

    public Argument {
        Objects.requireNonNull(value, "value");
        if (name == null ^ nameSpan == null) {
            throw new IllegalArgumentException("имя аргумента и его место задаются вместе");
        }
    }

    /** Позиционный аргумент — без имени. */
    public static Argument positional(Expr value) {
        return new Argument(null, null, value);
    }

    /** Именованный аргумент: {@code count: 2}. */
    public static Argument named(String name, Span nameSpan, Expr value) {
        return new Argument(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(nameSpan, "nameSpan"), value);
    }

    public boolean isNamed() {
        return name != null;
    }

    /** Место всего аргумента: от имени, если оно есть, до конца выражения. */
    public Span span() {
        return nameSpan == null ? value.span() : nameSpan.to(value.span());
    }

    @Override
    public String toString() {
        return name == null ? value.toString() : name + ": " + value;
    }
}
