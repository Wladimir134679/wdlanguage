package ru.wds.wdl.stdlib;

import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;

import java.util.Random;

/**
 * Генератор случайных чисел — обычный Java-класс, а не построитель лямбд.
 * <p>
 * Скрипт видит ровно то же, что видел раньше: {@code new Random(42)},
 * {@code r.next()}, {@code r.int(100)}, {@code r.pick(a)}, {@code r.seed}.
 * Изменилось только то, как это написано: методы — методы, конструкторы —
 * конструкторы, и никакого описания в третьем месте. Что из этого видно скрипту
 * и под какими именами, говорит схема в {@link Randoms}.
 *
 * <h2>Почему именно этот класс переписан</h2>
 * Он весь состоит из вызовов чужого кода ({@link Random}) и не знает ничего
 * про запуск: ни вывода, ни трекера, ни модулей. Такому классу построитель
 * не даёт ничего, кроме лишнего слоя, — а {@code File} и {@code Window},
 * наоборот, на построителе и держатся ({@code docs/java-interop.md}).
 *
 * <h2>Ошибки пишутся на языке движка</h2>
 * {@link WdlRuntimeError} со {@link Span#NONE}: библиотека знает, <b>что</b> не так,
 * но не знает, на какой строке скрипта это случилось, — место проставит граница
 * вызова ({@code runtime.Foreign}). Поэтому сообщение остаётся тем же, каким было
 * у лямбды, и {@code catch (e is ValueError)} по-прежнему его ловит.
 */
public final class ScriptRandom {

    private final Random random;

    /** Зерно или {@code null}, если генератор создан без него. */
    private final Long seed;

    public ScriptRandom() {
        this.random = new Random();
        this.seed = null;
    }

    /**
     * Генератор с зерном: одно зерно — одна последовательность.
     * <p>
     * Ради этого зерно и задают, поэтому оно ещё и читается — {@code r.seed}.
     */
    public ScriptRandom(long seed) {
        this.random = new Random(seed);
        this.seed = seed;
    }

    /** Зерно, с которым создан генератор, или {@code null}. Схема делает это свойством. */
    public Long getSeed() {
        return seed;
    }

    /** Следующее дробное в промежутке [0, 1). */
    public double next() {
        return random.nextDouble();
    }

    /**
     * Целое от нуля до границы, не включая её.
     * <p>
     * В скрипте зовётся {@code int} — имя, которое в Java методу дать нельзя,
     * поэтому схема открывает его под другим именем. Это ровно то, для чего
     * в схеме есть переименование.
     */
    public long nextInt(long limit) {
        if (limit <= 0) {
            throw new WdlRuntimeError(ErrorKind.VALUE, Span.NONE,
                    "Random.int(): граница: ожидалось положительное число, а здесь " + limit);
        }
        return limit <= Integer.MAX_VALUE
                ? random.nextInt((int) limit)
                : Math.floorMod(random.nextLong(), limit);
    }

    /**
     * Случайный элемент массива.
     * <p>
     * Параметр — {@link ArrayValue}, а не {@code List}: массив языка попадает сюда
     * как есть, без копии и без перевода элементов. Мост так и задуман — библиотеке,
     * которой нужны значения языка, достаточно попросить их типом.
     */
    public Value pick(ArrayValue items) {
        if (items.isEmpty()) {
            throw new WdlRuntimeError(ErrorKind.VALUE, Span.NONE,
                    "Random.pick(): откуда выбирать: ожидался непустой массив");
        }
        return items.get(random.nextInt(items.size()));
    }

    @Override
    public String toString() {
        return seed == null ? "Random" : "Random(seed: " + seed + ")";
    }
}
