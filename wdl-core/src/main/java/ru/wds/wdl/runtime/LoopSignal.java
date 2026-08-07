package ru.wds.wdl.runtime;

/**
 * Сигнал управления циклом: {@code break} и {@code continue}.
 * <p>
 * Это не ошибка, а способ выйти из середины обхода дерева. Тело цикла может быть
 * вложено сколь угодно глубоко — блок в ветвлении внутри другого блока, — и передавать
 * оттуда «прерваться» возвратным значением значит проверять его в каждом узле каждого
 * посетителя. Исключение проходит этот путь само, а поймать его есть кому: ровно те
 * методы интерпретатора, что выполняют циклы. Так же сюда придёт и {@code return},
 * когда появятся функции, — только со значением.
 * <p>
 * Экземпляры — синглтоны без стека вызовов ({@code super(null, null, false, false)}):
 * заполнение стектрейса стоит дороже самого прохода цикла, а описывал бы он путь
 * по методам интерпретатора, который никому не интересен.
 */
abstract sealed class LoopSignal extends RuntimeException {

    private LoopSignal() {
        super(null, null, false, false);
    }

    /** Прервать цикл целиком. */
    static final class Break extends LoopSignal {

        static final Break INSTANCE = new Break();

        private Break() {
        }
    }

    /** Перейти к следующему проходу. */
    static final class Continue extends LoopSignal {

        static final Continue INSTANCE = new Continue();

        private Continue() {
        }
    }
}
