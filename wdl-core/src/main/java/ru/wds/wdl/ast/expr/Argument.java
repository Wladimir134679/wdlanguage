package ru.wds.wdl.ast.expr;

import ru.wds.wdl.ast.Fragment;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Аргумент в скобках: {@code f(2)}, {@code f(count: 2)}, {@code f(*values)}, {@code f(**options)}.
 * <p>
 * <b>Аргумент — не выражение</b>, и это то же решение, что «присваивание — инструкция».
 * Запись {@code count: 2} осмысленна ровно в одном месте — непосредственно в списке
 * аргументов; будь она выражением, {@code g(x: 1) + 1} пришлось бы запрещать проверкой
 * в каждом посетителе. Отдельный тип запрещает по построению, и компилятор следит
 * за этим вместо человека.
 * <p>
 * Один вид узла на все четыре формы, а вид записан {@linkplain Kind перечислением}:
 * так же устроено {@linkplain AccessExpr обращение}, где {@code a.b} и {@code a["b"]} —
 * один узел с пометкой о форме. Перечисление, а не набор признаков, — чтобы
 * {@code switch} без {@code default} у связывателя и у печати дерева компилятор
 * проверил на полноту, когда форм станет пять.
 * <p>
 * <b>Раскрытие — это группа аргументов, а не один.</b> Сколько именно их в {@code *values},
 * известно только при выполнении, поэтому в дереве оно остаётся одним узлом, а
 * разворачивается в {@code runtime.Binder} — там, где значения уже есть.
 * <p>
 * Имя хранится вместе со своим местом в исходнике: сообщение «функция 'greet'
 * не принимает параметра 'greetng'» обязано подчеркнуть имя, а не весь вызов.
 * У раскрытия в этом же поле лежит место звёздочки — подчёркивать надо её.
 *
 * @param kind     форма записи
 * @param name     имя параметра или {@code null} у всех форм, кроме {@link Kind#NAMED}
 * @param nameSpan место имени или звёздочки в исходнике; {@code null} у позиционного
 * @param value    выражение аргумента; у раскрытия — выражение контейнера
 */
public record Argument(Kind kind, String name, Span nameSpan, Expr value) implements Fragment {

    /** Форма записи аргумента. */
    public enum Kind {
        /** {@code f(2)} — значение по позиции. */
        POSITIONAL,
        /** {@code f(count: 2)} — значение по имени параметра. */
        NAMED,
        /** {@code f(*values)} — массив, раскрываемый в позиционные аргументы. */
        SPREAD,
        /** {@code f(**options)} — объект, раскрываемый в именованные аргументы. */
        NAMED_SPREAD
    }

    public Argument {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if ((name != null) != (kind == Kind.NAMED)) {
            throw new IllegalArgumentException("имя есть только у именованного аргумента");
        }
        if ((nameSpan == null) != (kind == Kind.POSITIONAL)) {
            throw new IllegalArgumentException(
                    "место имени или звёздочки задаётся у всех форм, кроме позиционной");
        }
    }

    /** Позиционный аргумент — без имени. */
    public static Argument positional(Expr value) {
        return new Argument(Kind.POSITIONAL, null, null, value);
    }

    /** Именованный аргумент: {@code count: 2}. */
    public static Argument named(String name, Span nameSpan, Expr value) {
        return new Argument(Kind.NAMED, Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(nameSpan, "nameSpan"), value);
    }

    /** Раскрытие массива в позиционные аргументы: {@code *values}. */
    public static Argument spread(Span starSpan, Expr value) {
        return new Argument(Kind.SPREAD, null, Objects.requireNonNull(starSpan, "starSpan"), value);
    }

    /** Раскрытие объекта в именованные аргументы: {@code **options}. */
    public static Argument namedSpread(Span starSpan, Expr value) {
        return new Argument(Kind.NAMED_SPREAD, null,
                Objects.requireNonNull(starSpan, "starSpan"), value);
    }

    /** Написано ли имя буквально. Раскрытие объекта именованным в этом смысле не является. */
    public boolean isNamed() {
        return kind == Kind.NAMED;
    }

    /** Раскрывается ли здесь контейнер — массив или объект. */
    public boolean isSpread() {
        return kind == Kind.SPREAD || kind == Kind.NAMED_SPREAD;
    }

    /**
     * Относится ли аргумент к именованной группе.
     * <p>
     * Позиционная группа целиком идёт перед именованной, и {@code **options} — часть
     * второй: имена в нём хоть и неизвестны до выполнения, но позиции не занимают.
     */
    public boolean inNamedGroup() {
        return kind == Kind.NAMED || kind == Kind.NAMED_SPREAD;
    }

    /** Место всего аргумента: от имени или звёздочки, если они есть, до конца выражения. */
    public Span span() {
        return nameSpan == null ? value.span() : nameSpan.to(value.span());
    }

    @Override
    public String toString() {
        return switch (kind) {
            case POSITIONAL -> value.toString();
            case NAMED -> name + ": " + value;
            case SPREAD -> "*" + value;
            case NAMED_SPREAD -> "**" + value;
        };
    }
}
