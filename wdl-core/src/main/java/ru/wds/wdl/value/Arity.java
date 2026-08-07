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

    /**
     * То же с существительным в согласованной форме: «ровно 1 аргумент»,
     * «ровно 2 аргумента», «не меньше 1 аргумента», «от 1 до 3 аргументов».
     * <p>
     * Согласование живёт здесь, а не в интерпретаторе: сообщение об ошибке вызова
     * собирается в одном месте на все функции, и склеивать его из «ровно 1» и
     * «аргументов» значит получать «ровно 1 аргументов» в самом частом случае.
     */
    public String describeArguments() {
        if (max == Integer.MAX_VALUE) {
            return min == 0
                    ? "любое число аргументов"
                    // «не меньше» требует родительного падежа: одного аргумента, двух аргументов.
                    : "не меньше " + min + (min == 1 ? " аргумента" : " аргументов");
        }
        if (min == max) {
            return "ровно " + min + " " + argumentWord(min);
        }
        return "от " + min + " до " + max + " аргументов";
    }

    /** Форма слова «аргумент» при числе: 1 — аргумент, 2–4 — аргумента, иначе — аргументов. */
    private static String argumentWord(int count) {
        int tens = count % 100;
        if (tens >= 11 && tens <= 14) {
            return "аргументов";
        }
        return switch (count % 10) {
            case 1 -> "аргумент";
            case 2, 3, 4 -> "аргумента";
            default -> "аргументов";
        };
    }

    @Override
    public String toString() {
        return describe();
    }
}
