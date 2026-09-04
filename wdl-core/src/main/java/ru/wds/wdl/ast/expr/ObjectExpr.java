package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Литерал объекта: {@code {"ключ": "значение", число: 7}} и {@code {**defaults, timeout: 60}}.
 * <p>
 * Имя поля без кавычек — тот же сахар, что и точка в обращении: {@code {x: 1}}
 * разбирается в ключ-строку {@code "x"}, и результат неотличим от {@code {"x": 1}}.
 * Обе записи дают объект, к которому потом можно обратиться и через {@code .x},
 * и через {@code ["x"]} — см. {@link AccessExpr}.
 * <p>
 * Ключ — выражение, а не имя: {@code {(a + b): 1}} допустим и складывает значение
 * по вычисленному ключу. Ограничивать ключи строками нет причин, раз объект
 * закрывает и роль словаря.
 *
 * @param entries пары в порядке записи; порядок сохраняется и во время выполнения
 * @param span    место в исходнике вместе с фигурными скобками
 */
public record ObjectExpr(List<Entry> entries, Span span) implements Expr {

    public ObjectExpr {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        Objects.requireNonNull(span, "span");
    }

    /**
     * Одна запись литерала: пара или раскрытие {@code **options}.
     * <p>
     * Вид записан {@linkplain Kind перечислением}, а соответствие «у раскрытия ключа
     * нет» проверяется в компактном конструкторе — образец здесь {@link Argument},
     * и причина та же: неверная комбинация полей не должна доживать до посетителя.
     *
     * @param kind     форма записи
     * @param key      выражение ключа; {@code null} у раскрытия
     * @param starSpan место {@code **} в исходнике или {@code null} у пары
     * @param value    выражение значения; у раскрытия — выражение объекта
     */
    public record Entry(Kind kind, Expr key, Span starSpan, Expr value) {

        /** Форма записи. */
        public enum Kind {
            /** {@code {a: 1}} — пара «ключ-значение». */
            PAIR,
            /** {@code {**options}} — объект, раскрываемый в пары. */
            SPREAD
        }

        public Entry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
            if ((key == null) != (kind == Kind.SPREAD)) {
                throw new IllegalArgumentException("ключ есть у пары и только у неё");
            }
            if ((starSpan == null) != (kind == Kind.PAIR)) {
                throw new IllegalArgumentException("место '**' задаётся только у раскрытия");
            }
        }

        /** Обычная пара: {@code ключ: значение}. */
        public static Entry pair(Expr key, Expr value) {
            return new Entry(Kind.PAIR, Objects.requireNonNull(key, "key"), null, value);
        }

        /** Раскрытие: {@code **options}. */
        public static Entry spread(Span starSpan, Expr value) {
            return new Entry(Kind.SPREAD, null, Objects.requireNonNull(starSpan, "starSpan"), value);
        }

        /** Раскрывается ли здесь объект. */
        public boolean isSpread() {
            return kind == Kind.SPREAD;
        }

        /** Место записи целиком: от ключа или {@code **} до конца выражения. */
        public Span span() {
            return (key != null ? key.span() : starSpan).to(value.span());
        }

        @Override
        public String toString() {
            return kind == Kind.SPREAD ? "**" + value : key + ": " + value;
        }
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        entries.forEach(entry -> joiner.add(entry.toString()));
        return joiner.toString();
    }
}
