package ru.wds.wdl.value.types;

import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

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
 *
 * <h2>Потоки</h2>
 * Правило то же, что у {@link MapValue}: короткий {@code synchronized} по самому
 * списку и <b>снимок</b> из {@link #items()}. Вьюха на живой {@code ArrayList} дала бы
 * {@code ConcurrentModificationException} посреди обычного перебора, стоило бы
 * соседнему потоку что-нибудь добавить.
 * <p>
 * Атомарен здесь ровно один доступ. {@code a[i] = a[i] + 1} и «проверил длину — взял
 * элемент» атомарными не становятся: между двумя обращениями массив вправе измениться,
 * и склеивает их {@code synchronized} на функции, а не сам массив.
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
        synchronized (items) {
            return items.size();
        }
    }

    public boolean isEmpty() {
        synchronized (items) {
            return items.isEmpty();
        }
    }

    /** Элемент по индексу; вызывающий обязан заранее проверить границы. */
    public Value get(int index) {
        synchronized (items) {
            return items.get(index);
        }
    }

    public void set(int index, Value value) {
        Objects.requireNonNull(value, "value");
        synchronized (items) {
            items.set(index, value);
        }
    }

    public void add(Value value) {
        Objects.requireNonNull(value, "value");
        synchronized (items) {
            items.add(value);
        }
    }

    /**
     * Вставляет элемент, сдвигая хвост вправо. Границы проверяет вызывающий —
     * по той же причине, что и у {@link #get}: место в скрипте знает интерпретатор.
     */
    public void insert(int index, Value value) {
        Objects.requireNonNull(value, "value");
        synchronized (items) {
            items.add(index, value);
        }
    }

    /** Удаляет элемент по индексу и отдаёт его. Границы проверяет вызывающий. */
    public Value removeAt(int index) {
        synchronized (items) {
            return items.remove(index);
        }
    }

    public void clear() {
        synchronized (items) {
            items.clear();
        }
    }

    /**
     * Заменяет содержимое целиком — то, чем перестановка на месте отличается
     * от нового массива.
     * <p>
     * Одним замком, а не «очистить и добавить по одному»: соседний поток не должен
     * увидеть массив пустым посреди сортировки.
     */
    public void replaceAll(List<Value> replacement) {
        List<Value> copy = new ArrayList<>(replacement);
        synchronized (items) {
            items.clear();
            items.addAll(copy);
        }
    }

    /** Снимок элементов — почему снимок, разобрано в javadoc класса. */
    public List<Value> items() {
        synchronized (items) {
            return Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    /** Новый массив из элементов двух: {@code [1, 2] + [3]}. */
    public static ArrayValue concat(ArrayValue left, ArrayValue right) {
        // По снимкам: два замка подряд, а не вложенно, — иначе 'a + b' и 'b + a'
        // из двух потоков встали бы намертво.
        List<Value> head = left.items();
        List<Value> tail = right.items();
        List<Value> result = new ArrayList<>(head.size() + tail.size());
        result.addAll(head);
        result.addAll(tail);
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
        // По снимку: печать не должна бросать посреди чужой записи и не должна держать
        // замок, пока считается display() вложенного значения.
        for (Value item : items()) {
            joiner.add(item == this ? "[...]" : item.toString());
        }
        return joiner.toString();
    }

    @Override
    public String toString() {
        return display();
    }
}
