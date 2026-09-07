package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.RangeValue;

/**
 * Одно правило числового ключа на весь язык: что значит число в квадратных скобках
 * и в аргументе члена, работающего с позицией.
 * <p>
 * <b>Дисциплины две, и разделяет их заданный вопрос.</b> Адресация — {@code a[i]},
 * {@code insert}, {@code remove} — спрашивает «какой элемент под этим номером»;
 * когда такого номера нет, ответа не существует, и промолчать значило бы спрятать
 * опечатку, поэтому здесь ошибка. Отрезок — {@code a[from..to]}, {@code slice} —
 * спрашивает «какая часть попадает в этот промежуток»; ответ есть всегда, иногда
 * пустой, поэтому здесь границы подрезаются. Правило не новое: так эти члены вели
 * себя и до появления отрицательных индексов, — новое только то, что оно записано
 * в одном месте, а не в четырёх.
 * <p>
 * <b>Отрицательное число считается от конца:</b> {@code -1} — последняя позиция,
 * {@code -size} — первая. Одинаково всюду, включая границы среза: иначе {@code -1}
 * значило бы в {@code a[-1]} и в {@code a[1..-1]} разное, а это две соседние строки
 * одного скрипта.
 * <p>
 * <b>Цена у нормализации в срезе одна и названа сразу.</b> Идиома {@code 0..n - 1}
 * при {@code n == 0} даёт {@code 0..-1}, и в срезе это после нормализации весь
 * контейнер, а не пустой отрезок. Принято сознательно: идиома живёт в {@code for},
 * где никакого контейнера рядом нет, а «первые n» в языке уже пишется
 * {@code a.slice(0, n)} — полуинтервалом, ровно затем, чтобы {@code n == 0} давало
 * пусто. Диагностикой это не лечится: {@code n} вычисляется, и отличить «имелся
 * в виду конец» от «имелось в виду пусто» движку нечем.
 */
public final class Indexes {

    private Indexes() {
    }

    /**
     * Отрезок как полуинтервал — в том виде, в каком его берут {@code subList}
     * и {@code substring}, без второго перевода на месте использования.
     *
     * @param from начало включительно
     * @param to   конец исключительно
     */
    public record Slice(int from, int to) {

        /** Пуст ли отрезок: {@code a[4..2]} и {@code a[9..100]} у короткого массива. */
        public boolean empty() {
            return from >= to;
        }
    }

    /**
     * Позиция существующего элемента по ключу-значению: проверка «целое число»,
     * счёт от конца для отрицательного, строгие границы.
     */
    public static int element(int size, Value key, String what, Span span) {
        return element(size, integer(key, what, span), what, span);
    }

    /**
     * То же для вызывающих, у которых число уже проверено (члены — через
     * {@link Args#integer}): повторная проверка типа сказала бы про аргумент
     * члена языком обращения по индексу.
     */
    public static int element(int size, long index, String what, Span span) {
        long normalized = normalize(size, index);
        if (normalized < 0 || normalized >= size) {
            throw outOfBounds(size, index, what, span);
        }
        return (int) normalized;
    }

    /**
     * Место вставки: то же, но верхняя граница включительна — вставить в конец
     * законно, и {@code insert(a.size, x)} обязан работать.
     */
    public static int position(int size, long index, String what, Span span) {
        long normalized = normalize(size, index);
        if (normalized < 0 || normalized > size) {
            throw outOfBounds(size, index, what, span);
        }
        return (int) normalized;
    }

    /**
     * Граница отрезка: счёт от конца и подрезка в {@code [0, size]}, без ошибок.
     */
    public static int cut(int size, long index) {
        return (int) Math.max(0, Math.min(normalize(size, index), size));
    }

    /**
     * Включительный диапазон — в полуинтервал. Обе границы обязаны быть целыми
     * тем же доводом, что и у {@code for (x in 0.5..2.5)}: какие позиции содержит
     * такой диапазон «по одной», языку решать не за что.
     */
    public static Slice of(int size, RangeValue range, String what, Span span) {
        if (!range.from().isInteger() || !range.to().isInteger()) {
            throw new WdlRuntimeError(ErrorKind.INDEX, span, "границы среза " + what
                    + " должны быть целыми числами, а здесь " + range.display());
        }
        // Подрезка после смещения на единицу, а не до неё: иначе 'a[3..100]'
        // у массива из пяти дал бы верхнюю границу 6 и вылет на subList.
        int from = cut(size, range.from().asLong());
        int to = (int) Math.max(0, Math.min(normalize(size, range.to().asLong()) + 1, size));
        return new Slice(from, Math.max(from, to));
    }

    /** Отрицательное — от конца; остальное как есть. */
    private static long normalize(int size, long index) {
        return index < 0 ? index + size : index;
    }

    private static long integer(Value key, String what, Span span) {
        if (!(key instanceof NumberValue number) || !number.isInteger()) {
            throw new WdlRuntimeError(ErrorKind.INDEX, span, "индекс " + what
                    + " должен быть целым числом, а здесь " + key.type().title() + " (" + key + ")");
        }
        return number.asLong();
    }

    /**
     * Сообщение называет исходное число, а не нормализованное, и у отрицательного
     * добавляет нижнюю границу: без неё промах по {@code -9} неотличим от промаха
     * по {@code 9}, а перепутать «от конца» и «от начала» — самая вероятная ошибка
     * в первый месяц после появления правила.
     */
    private static WdlRuntimeError outOfBounds(int size, long index, String what, Span span) {
        String hint = index < 0 && size > 0
                ? ": отрицательный индекс считается от конца, наименьший здесь " + (-size)
                : "";
        return new WdlRuntimeError(ErrorKind.INDEX, span,
                "индекс " + index + " вне границ " + what + " размером " + size + hint);
    }
}
