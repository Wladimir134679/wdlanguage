package ru.wds.wdl.debug;

/**
 * Почему поток стоит. Клиенту это нужно так же, как место: «сработала точка»
 * и «дошёл шаг» рисуются в отладчике по-разному.
 */
public enum StopReason {

    /** Сработала точка останова. */
    BREAKPOINT("точка останова"),

    /** Закончился шаг: {@code into}, {@code over} или {@code out}. */
    STEP("шаг"),

    /** Попросили встать — кнопкой «Пауза» или остановкой соседнего потока. */
    PAUSE("пауза");

    private final String title;

    StopReason(String title) {
        this.title = title;
    }

    /** Название по-русски — для вывода человеку. */
    public String title() {
        return title;
    }
}
