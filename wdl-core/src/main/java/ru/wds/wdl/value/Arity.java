package ru.wds.wdl.value;

/**
 * Сколько аргументов принимает функция.
 * <p>
 * Проверка числа аргументов делается в одном месте — до вызова, — поэтому сама
 * функция может рассчитывать на то, что список уже правильной длины, и не начинается
 * с трёх строк проверок. Сообщение об ошибке при этом получается одинаковым
 * для всех функций.
 *
 * @param min минимальное число аргументов
 * @param max максимальное; {@link Integer#MAX_VALUE} означает «сколько угодно»
 */
public record Arity(int min, int max) {

    public Arity {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("некорректная арность: " + min + ".." + max);
        }
    }

    public static Arity exactly(int count) {
        return new Arity(count, count);
    }

    public static Arity atLeast(int count) {
        return new Arity(count, Integer.MAX_VALUE);
    }

    public static Arity any() {
        return new Arity(0, Integer.MAX_VALUE);
    }

    public static Arity between(int min, int max) {
        return new Arity(min, max);
    }

    public boolean accepts(int count) {
        return count >= min && count <= max;
    }

    /** Описание для сообщения об ошибке: «ровно 2», «не меньше 1», «от 1 до 3». */
    public String describe() {
        if (max == Integer.MAX_VALUE) {
            return min == 0 ? "любое число" : "не меньше " + min;
        }
        if (min == max) {
            return "ровно " + min;
        }
        return "от " + min + " до " + max;
    }

    @Override
    public String toString() {
        return describe();
    }
}
