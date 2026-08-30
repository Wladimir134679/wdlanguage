package ru.wds.wdl.value.types;

import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.Objects;

/**
 * Диапазон чисел: {@code 1..5}. Границы включительны с обеих сторон — так это
 * и читается: «от 1 до 5».
 * <p>
 * <b>Полноценный тип значения, а не форма записи внутри {@code case}.</b> Пока
 * {@code ..} жил только справа от {@code in} в образце, диапазон можно было держать
 * частью синтаксиса; как только {@code in} стал работать везде, {@code age in 18..65}
 * обязано работать тоже, а значит {@code 18..65} обязано быть значением, которое
 * кладут в переменную и передают в функцию. Нативным классом прелюдии делать его
 * при этом нельзя: тогда {@code typeof(1..5)} ответил бы {@code object}, а
 * {@code typeof} врать не должен.
 * <p>
 * <b>{@code 5..1} пуст, а не идёт вниз.</b> Довод железный: {@code for (i in 0..n - 1)}
 * при {@code n == 0} даёт {@code 0..-1}, и это обязано быть нулём проходов, а не
 * тихим проходом в обратную сторону. Убывающий диапазон, если понадобится, будет
 * записан шагом, а не порядком границ.
 * <p>
 * {@code record} осознанно: диапазон неизменяем, и структурное равенство даёт
 * {@code 1..5 == 1..5} даром — в отличие от массива, который сравнивается по ссылке,
 * потому что его можно поменять.
 *
 * @param from нижняя граница, включительно
 * @param to   верхняя граница, включительно
 */
public record RangeValue(NumberValue from, NumberValue to) implements Value {

    public RangeValue {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public static RangeValue of(NumberValue from, NumberValue to) {
        return new RangeValue(from, to);
    }

    /**
     * Внутри ли число. Работает для любых чисел, включая вещественные границы:
     * вопрос «между ли» осмыслен и для {@code 0.5..2.5}, а целые границы нужны
     * только перебору, где шаг равен единице.
     */
    public boolean contains(NumberValue value) {
        if (from.isInteger() && to.isInteger() && value.isInteger()) {
            long x = value.asLong();
            return from.asLong() <= x && x <= to.asLong();
        }
        double x = value.asDouble();
        return from.asDouble() <= x && x <= to.asDouble();
    }

    /** Ни одного числа внутри: верхняя граница ниже нижней. */
    public boolean empty() {
        return from.isInteger() && to.isInteger()
                ? from.asLong() > to.asLong()
                : from.asDouble() > to.asDouble();
    }

    @Override
    public ValueType type() {
        return ValueType.RANGE;
    }

    @Override
    public String display() {
        return from.display() + ".." + to.display();
    }

    @Override
    public String toString() {
        return display();
    }
}
