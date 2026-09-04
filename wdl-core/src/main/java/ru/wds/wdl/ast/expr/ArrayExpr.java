package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Литерал массива: {@code [1, 2, "три", [4]]} и {@code [*head, 3]}.
 * <p>
 * Каждое вычисление такого выражения создаёт новый массив — литерал описывает,
 * как построить значение, а не само значение. Поэтому в дереве он остаётся
 * узлом даже когда все элементы константны: свёртка констант не имеет права
 * превратить его в один общий разделяемый массив.
 *
 * @param elements элементы в порядке записи
 * @param span     место в исходнике вместе со скобками
 */
public record ArrayExpr(List<Element> elements, Span span) implements Expr {

    public ArrayExpr {
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        Objects.requireNonNull(span, "span");
    }

    /**
     * Один элемент литерала: значение или раскрытие {@code *values}.
     * <p>
     * Вид записан {@linkplain Kind перечислением}, а не признаком, — по той же
     * причине, что у {@link Argument}: {@code switch} без {@code default} компилятор
     * проверит на полноту, когда форм станет три.
     * <p>
     * <b>Раскрытие — это группа элементов, а не один.</b> Сколько именно их
     * в {@code *values}, известно только при выполнении, поэтому в дереве оно
     * остаётся одним элементом, а разворачивается в {@code Interpreter.visitArray} —
     * там, где значение уже есть.
     *
     * @param kind      форма записи
     * @param starSpan  место звёздочки в исходнике или {@code null} у обычного элемента;
     *                  подчёркивать в сообщении о типе надо именно её
     * @param value     выражение элемента; у раскрытия — выражение контейнера
     */
    public record Element(Kind kind, Span starSpan, Expr value) {

        /** Форма записи элемента. */
        public enum Kind {
            /** {@code [1]} — одно значение. */
            ITEM,
            /** {@code [*values]} — массив или диапазон, раскрываемый в элементы. */
            SPREAD
        }

        public Element {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            if ((starSpan == null) != (kind == Kind.ITEM)) {
                throw new IllegalArgumentException("место звёздочки задаётся только у раскрытия");
            }
        }

        /** Обычный элемент. */
        public static Element item(Expr value) {
            return new Element(Kind.ITEM, null, value);
        }

        /** Раскрытие: {@code *values}. */
        public static Element spread(Span starSpan, Expr value) {
            return new Element(Kind.SPREAD, Objects.requireNonNull(starSpan, "starSpan"), value);
        }

        /** Раскрывается ли здесь контейнер. */
        public boolean isSpread() {
            return kind == Kind.SPREAD;
        }

        /** Место элемента целиком: от звёздочки, если она есть, до конца выражения. */
        public Span span() {
            return starSpan == null ? value.span() : starSpan.to(value.span());
        }

        @Override
        public String toString() {
            return kind == Kind.SPREAD ? "*" + value : value.toString();
        }
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        elements.forEach(element -> joiner.add(element.toString()));
        return joiner.toString();
    }
}
