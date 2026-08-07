package ru.wds.wdl.value;

import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;

/**
 * Число: целое ({@link IntValue}) или вещественное ({@link FloatValue}).
 * <p>
 * Для скрипта это один тип — {@link ValueType#NUMBER}. Разделение внутри нужно
 * ровно за тем, чтобы целочисленная арифметика оставалась точной: счётчики, индексы
 * и битовые операции не имеют права терять младшие разряды на больших значениях,
 * а {@code double} их теряет уже после 2^53.
 * <p>
 * Правило продвижения одно: пока оба операнда целые — результат целый; появился
 * вещественный — весь пример считается в {@code double}. Переполнение {@code long}
 * тоже переводит результат в {@code double}, а не молча заворачивает разряды.
 */
public sealed interface NumberValue extends Value permits IntValue, FloatValue {

    @Override
    default ValueType type() {
        return ValueType.NUMBER;
    }

    double asDouble();

    /** Целое представление; для {@link FloatValue} дробная часть отбрасывается. */
    long asLong();

    /** Целое ли это число по типу (а не по значению): {@code 2.0} — не целое. */
    boolean isInteger();
}
