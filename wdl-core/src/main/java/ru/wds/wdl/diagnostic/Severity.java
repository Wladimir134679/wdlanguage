package ru.wds.wdl.diagnostic;

/** Насколько серьёзна проблема, найденная в исходнике. */
public enum Severity {

    /** Разбор продолжается, но результат непригоден к выполнению. */
    ERROR("ошибка"),

    /** Код корректен, но что-то в нём выглядит подозрительно. */
    WARNING("предупреждение");

    private final String title;

    Severity(String title) {
        this.title = title;
    }

    /** Название для сообщений человеку. */
    public String title() {
        return title;
    }
}
