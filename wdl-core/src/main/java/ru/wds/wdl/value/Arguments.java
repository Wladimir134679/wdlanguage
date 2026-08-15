package ru.wds.wdl.value;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Аргументы, разложенные по позициям параметров.
 * <p>
 * До именованных аргументов такого понятия не требовалось: «аргумент не передан»
 * означало «список короче», и этого хватало. {@code greet("мир", punct: "?")} ломает
 * это правило — пропуск оказывается <b>в середине</b>, и его нечем обозначить в списке.
 * Отсюда маска: значения плюс ответ на вопрос «эту позицию заполнили?».
 * <p>
 * <b>Пропуск доживает до вызываемого только у отложенного значения по умолчанию</b>
 * ({@link Signature.Param#lazy(String)}): выражение по умолчанию считается в области
 * вызова и видит параметры левее себя, поэтому вычислить его может лишь сам вызываемый.
 * Готовое значение ({@link Signature.Param#optional}) связыватель подставляет сам,
 * а хвост из непереданных просто обрезает — поэтому все, кто про пропуски не знает,
 * получают привычный плотный список через {@link #asList()} и ничего не замечают.
 * <p>
 * Неизменяем: значение-функция живёт в нескольких потоках, и набор аргументов,
 * который можно поправить после проверки, был бы дырой ровно в том месте, где вызов
 * уже признан правильным.
 */
public final class Arguments {

    private final Value[] values;
    /** {@code null}, если пропусков нет, — самый частый случай не платит за массив. */
    private final boolean[] present;

    private Arguments(Value[] values, boolean[] present) {
        this.values = values;
        this.present = present;
    }

    /**
     * Все аргументы переданы подряд — обычный позиционный вызов и вызов из приложения.
     */
    public static Arguments positional(List<Value> values) {
        Objects.requireNonNull(values, "values");
        return new Arguments(values.toArray(new Value[0]), null);
    }

    /** Пустой набор — вызов без аргументов. */
    public static Arguments none() {
        return new Arguments(new Value[0], null);
    }

    /**
     * Сборщик для связывателя: позиции заполняются в произвольном порядке, потому что
     * именованный аргумент попадает туда, куда указывает его имя.
     */
    public static Builder builder(int size) {
        return new Builder(size);
    }

    /** Сколько позиций в наборе, считая пропущенные. */
    public int size() {
        return values.length;
    }

    /**
     * Заполнена ли позиция. Пропуск обязан заполнить сам вызываемый.
     * <p>
     * Позиция за концом набора — тоже незаполненная, а не выход за границы: вызываемый
     * идёт по <b>своим</b> параметрам, а их всегда не меньше, чем переданных аргументов.
     * Обрезанный хвост и пропуск в середине для него должны выглядеть одинаково.
     */
    public boolean has(int index) {
        return index < values.length && (present == null || present[index]);
    }

    /**
     * Значение позиции.
     *
     * @throws IllegalStateException если позиция пропущена — спрашивать её значение
     *                               можно только после {@link #has(int)}
     */
    public Value get(int index) {
        if (!has(index)) {
            throw new IllegalStateException("аргумент " + index + " не передан: "
                    + "значение по умолчанию вычисляет сам вызываемый");
        }
        return values[index];
    }

    /**
     * Плотный список — для тех, кто про пропуски не знает: встроенных функций,
     * функций приложения, {@code Args}.
     *
     * @throws IllegalStateException если в наборе есть пропуск. Попасть сюда таким
     *                               набором нельзя: пропуски бывают только у контракта
     *                               с отложенным значением, а такой контракт объявляют
     *                               те, кто умеет их заполнять
     */
    public List<Value> asList() {
        if (present != null) {
            for (int i = 0; i < present.length; i++) {
                if (!present[i]) {
                    throw new IllegalStateException("аргумент " + i + " не передан: "
                            + "набор с пропуском нельзя отдать плотным списком");
                }
            }
        }
        return List.of(values);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(has(i) ? values[i].display() : "...");
        }
        return sb.append(')').toString();
    }

    /** Сборщик набора: позиции заполняются в любом порядке, пропуски остаются пропусками. */
    public static final class Builder {

        private final Value[] values;
        private final boolean[] present;

        private Builder(int size) {
            this.values = new Value[size];
            this.present = new boolean[size];
        }

        public boolean isSet(int index) {
            return present[index];
        }

        public Builder set(int index, Value value) {
            values[index] = Objects.requireNonNull(value, "value");
            present[index] = true;
            return this;
        }

        /**
         * Собирает набор, обрезав хвост из непереданных позиций.
         * <p>
         * Хвост обрезается, а не остаётся пропуском, чтобы позиционный и именованный
         * вызовы приходили к вызываемому в одном и том же виде: {@code greet("мир")}
         * и {@code greet(name: "мир")} — это один список из одного значения, и ни одна
         * встроенная функция не обязана различать, как её позвали.
         */
        public Arguments build() {
            int size = values.length;
            while (size > 0 && !present[size - 1]) {
                size--;
            }
            Value[] kept = Arrays.copyOf(values, size);
            for (int i = 0; i < size; i++) {
                if (!present[i]) {
                    return new Arguments(kept, Arrays.copyOf(present, size));
                }
            }
            return new Arguments(kept, null);
        }
    }
}
