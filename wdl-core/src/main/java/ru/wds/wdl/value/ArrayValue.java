package ru.wds.wdl.value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Массив: упорядоченный изменяемый список значений с доступом по целому индексу.
 * <p>
 * Изменяемый и потому сравнивается по ссылке: {@code [1] == [1]} — ложь, это
 * два разных массива. Иначе пришлось бы решать, что делать с массивом, который
 * содержит сам себя, и платить обходом всей структуры за каждое сравнение.
 * <p>
 * Границы здесь не проверяются: сообщение об ошибке должно указывать на место
 * в скрипте, а {@link ru.wds.wdl.source.Span} знает только интерпретатор. Его дело —
 * спросить {@link #size()} и объяснить человеку, что не так.
 */
public final class ArrayValue implements Value {

    private final List<Value> items;

    public ArrayValue() {
        this.items = new ArrayList<>();
    }

    private ArrayValue(List<Value> items) {
        this.items = items;
    }

    /** Массив поверх копии переданного списка. */
    public static ArrayValue of(List<Value> items) {
        return new ArrayValue(new ArrayList<>(items));
    }

    public static ArrayValue of(Value... items) {
        return new ArrayValue(new ArrayList<>(List.of(items)));
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Элемент по индексу; вызывающий обязан заранее проверить границы. */
    public Value get(int index) {
        return items.get(index);
    }

    public void set(int index, Value value) {
        items.set(index, Objects.requireNonNull(value, "value"));
    }

    public void add(Value value) {
        items.add(Objects.requireNonNull(value, "value"));
    }

    /** Только для чтения: менять массив можно лишь через методы этого класса. */
    public List<Value> items() {
        return Collections.unmodifiableList(items);
    }

    /** Новый массив из элементов двух: {@code [1, 2] + [3]}. */
    public static ArrayValue concat(ArrayValue left, ArrayValue right) {
        List<Value> result = new ArrayList<>(left.size() + right.size());
        result.addAll(left.items);
        result.addAll(right.items);
        return new ArrayValue(result);
    }

    @Override
    public ValueType type() {
        return ValueType.ARRAY;
    }

    /** Элементы печатаются отладочным видом: строки внутри массива — в кавычках. */
    @Override
    public String display() {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (Value item : items) {
            joiner.add(item == this ? "[...]" : item.toString());
        }
        return joiner.toString();
    }

    @Override
    public String toString() {
        return display();
    }
}
