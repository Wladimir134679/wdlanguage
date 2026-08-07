package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Литерал объекта: {@code {"ключ": "значение", число: 7}}.
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
     * Одна пара литерала объекта.
     *
     * @param key   выражение ключа
     * @param value выражение значения
     */
    public record Entry(Expr key, Expr value) {

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        entries.forEach(entry -> joiner.add(entry.key() + ": " + entry.value()));
        return joiner.toString();
    }
}
