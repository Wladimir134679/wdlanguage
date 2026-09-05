package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Аннотации объявления: {@code @{route: "/users", method: "GET"}}.
 * <p>
 * <b>Аннотации — не выражение</b>, и это то же решение, что у {@link Decorator}
 * и {@link Argument}: запись осмысленна ровно в одном месте — перед объявлением, —
 * а будь она выражением, {@code x = @{a: 1} + 1} пришлось бы запрещать проверкой
 * в каждом посетителе. Отдельный тип запрещает по построению, и следит за этим
 * компилятор.
 * <p>
 * <b>Несколько блоков подряд сливаются в один объект.</b> Поэтому записи всех
 * блоков лежат здесь одним списком: склеивать их в трёх местах — в интерпретаторе,
 * в дампе дерева и в будущем форматтере — значило бы трижды написать одно правило.
 * Места самих блоков сохранены отдельно: они нужны тому, кто печатает дерево обратно,
 * и сообщению о дубликате ключа между блоками.
 * <p>
 * Внутри блока — обычные записи литерала объекта, {@link ObjectExpr.Entry}: и пара
 * с вычисляемым ключом, и раскрытие {@code **}. Отдельной грамматики у аннотаций нет,
 * и заводить её незачем — это ровно объектный литерал, написанный после {@code @}.
 *
 * @param entries записи всех блоков подряд, в порядке записи
 * @param blocks  места блоков {@code @{...}} в исходнике, по одному на блок
 */
public record Annotations(List<ObjectExpr.Entry> entries, List<Span> blocks) {

    /** Аннотаций не написано. Одно значение на все объявления: полей у него нет. */
    public static final Annotations NONE = new Annotations(List.of(), List.of());

    public Annotations {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (blocks.isEmpty() && !entries.isEmpty()) {
            throw new IllegalArgumentException("записи аннотаций есть, а блока нет");
        }
    }

    /**
     * Написан ли хоть один блок.
     * <p>
     * Не то же, что «список записей пуст»: пустая {@code @{}} разрешена и даёт пустой
     * объект. Для потребителя она неотличима от отсутствия аннотаций, а вот для
     * разбора и печати дерева — вполне: её человек написал.
     */
    public boolean written() {
        return !blocks.isEmpty();
    }

    /**
     * Место всех блоков вместе или {@code null}, если не написано ни одного.
     * <p>
     * Спрашивать его у ненаписанных незачем — и {@code null} здесь честнее пустого
     * места, которого в исходнике нет.
     */
    public Span span() {
        return blocks.isEmpty() ? null : blocks.get(0).to(blocks.get(blocks.size() - 1));
    }

    /**
     * Расширяет интервал объявления так, чтобы в него вошли написанные блоки.
     * <p>
     * Аннотация стоит <b>перед</b> объявлением, а место объявления считается от его
     * первого слова — {@code def}, {@code class}, имени параметра. Без этой поправки
     * записи аннотаций оказывались бы вне интервала того, кому они приписаны, то есть
     * вне своего родителя в дереве: обход по смещению до них бы не добрался,
     * а инвариант вложенности оказался бы нарушен.
     */
    public Span cover(Span span) {
        Span blocks = span();
        return blocks == null ? span : blocks.to(span);
    }

    @Override
    public String toString() {
        if (!written()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("@{");
        for (int i = 0; i < entries.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(entries.get(i));
        }
        return sb.append('}').toString();
    }
}
