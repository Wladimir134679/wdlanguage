package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Value;

import java.util.Objects;

/**
 * Сигнал управления выполнением: {@code break}, {@code continue}, {@code return}
 * и {@code yield}.
 * <p>
 * Это не ошибка, а способ выйти из середины обхода дерева. Тело цикла или функции может
 * быть вложено сколь угодно глубоко — блок в ветвлении внутри другого блока, — и передавать
 * оттуда «прерваться» возвратным значением значит проверять его в каждом узле каждого
 * посетителя. Исключение проходит этот путь само, а поймать его есть кому: ровно те
 * методы интерпретатора, что выполняют циклы и вызовы.
 * <p>
 * Отсюда же полезное свойство: {@code return} из глубины циклов не требует от циклов
 * ни строчки кода. {@code runLoopBody} ловит только {@link Break} и {@link Continue},
 * поэтому {@link Return} проходит сквозь любую вложенность до самого вызова функции.
 * <p>
 * {@code break} и {@code continue} — синглтоны без стека вызовов
 * ({@code super(null, null, false, false)}): заполнение стектрейса стоит дороже самого
 * прохода цикла, а описывал бы он путь по методам интерпретатора, который никому
 * не интересен. {@link Return} синглтоном быть не может — он несёт значение, — но стека
 * не заполняет по той же причине.
 */
abstract sealed class ControlSignal extends RuntimeException {

    private ControlSignal() {
        super(null, null, false, false);
    }

    /** Прервать цикл целиком. */
    static final class Break extends ControlSignal {

        static final Break INSTANCE = new Break();

        private Break() {
        }
    }

    /** Перейти к следующему проходу. */
    static final class Continue extends ControlSignal {

        static final Continue INSTANCE = new Continue();

        private Continue() {
        }
    }

    /** Выйти из функции со значением; у {@code return;} это {@code null}-значение языка. */
    static final class Return extends ControlSignal {

        private final Value value;

        Return(Value value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        Value value() {
            return value;
        }
    }

    /**
     * Отдать значение из ветки {@code case} и на этом ветку закончить.
     * <p>
     * Сигналом, а не хвостовым выражением блока, — и отсюда бесплатно берётся всё
     * остальное: {@code yield} работает из любой глубины внутри ветки (из {@code if},
     * из цикла), заканчивает ветку досрочно, а {@code defer} по пути наружу
     * выполняется сам — {@code visitBlock} ловит любой {@link RuntimeException}
     * и после отложенного бросает его дальше.
     * <p>
     * Ловит его ближайший {@code visitMatch}, поэтому вложенный {@code match}
     * забирает свой {@code yield} первым — искать «свою» ветку по глубине не нужно.
     */
    static final class Yield extends ControlSignal {

        private final Value value;

        Yield(Value value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        Value value() {
            return value;
        }
    }
}
