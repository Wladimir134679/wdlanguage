package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Распаковка: {@code x, y = *point}, {@code host, port, **rest = **config},
 * {@code a, b = b, a}.
 * <p>
 * <b>Отдельная инструкция, а не {@link AssignStmt} со списком целей.</b> У обычного
 * присваивания одна цель и одно значение, и почти весь его код — про это; здесь же
 * целей список, у целей есть виды ({@link UnpackTarget}), а значений может не быть
 * ни одного отдельного — их ещё предстоит достать из источника. Общим у двух
 * инструкций остаётся ровно то, что и должно, — понятие цели записи: проверяет её
 * разбор одним и тем же правилом, а пишет выполнение одним и тем же местом записи.
 * <p>
 * <b>Маркер стоит на источнике, а не на списке целей</b>, и это главное решение формы:
 * {@code x, y = *point} читается теми же двумя знаками, что и {@code f(*args)} —
 * «разложи по позициям» — и {@code f(**named)} — «разложи по именам». Словарь языка
 * не растёт ни на слово, а список справа без звёздочки остаётся тем, чем выглядит:
 * списком значений попарно. Обоснование целиком — в {@code docs/statements.md}.
 *
 * @param targets цели в порядке записи; остаток, если он есть, — последний
 * @param sources правая часть. У {@link Style#POSITIONAL} и {@link Style#NAMED}
 *                ровно один элемент, и это {@linkplain Argument.Kind#SPREAD раскрытие};
 *                у {@link Style#PAIRWISE} — список значений, среди которых
 *                раскрытие массива разрешено наравне с обычным выражением
 * @param style   как записана правая часть
 * @param span    место в исходнике целиком
 */
public record UnpackStmt(List<UnpackTarget> targets, List<Argument> sources,
                         Style style, Span span) implements Stmt {

    /**
     * Как записана правая часть — и, следовательно, откуда берутся значения.
     * <p>
     * Формально вид выводится из {@link #sources()}, но лежит он полем: его читают
     * и разбор, и выполнение, и печать дерева, а выводить одно и то же правило в трёх
     * местах — верный способ получить три слегка разных правила.
     */
    public enum Style {
        /** {@code x, y = *point} — источник раскладывается по позициям. */
        POSITIONAL,
        /** {@code x, y = **config} — источник раскладывается по именам целей. */
        NAMED,
        /** {@code a, b = b, a} — попарно, значение к значению. Раскрытие в списке разрешено. */
        PAIRWISE
    }

    public UnpackStmt {
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(span, "span");
        if (style != Style.PAIRWISE && sources.size() != 1) {
            throw new IllegalArgumentException("у распаковки ровно один источник");
        }
    }

    /** Единственный источник {@code *}- и {@code **}-формы. */
    public Argument source() {
        return sources.get(0);
    }

    /** Остаток {@code *rest} или {@code **rest}, если он написан, иначе {@code null}. */
    public UnpackTarget rest() {
        for (UnpackTarget target : targets) {
            if (target.isRest()) {
                return target;
            }
        }
        return null;
    }

    /** Сколько целей занимают позиции — то есть сколько значений берётся поимённо. */
    public int positions() {
        int count = 0;
        for (UnpackTarget target : targets) {
            if (target.positional()) {
                count++;
            }
        }
        return count;
    }

    /** Список целей текстом: {@code x, y, *rest}. Нужен сообщениям, которые предлагают запись. */
    public String targetsText() {
        return targets.stream().map(UnpackTarget::toString).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return targetsText() + " = "
                + sources.stream().map(Argument::toString).collect(Collectors.joining(", "));
    }
}
