package ru.wds.wdl.value;

import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;

/**
 * Тип значения времени выполнения — с точки зрения языка, а не реализации.
 * <p>
 * Целое и вещественное число — это один тип {@link #NUMBER}: скрипт не должен
 * знать, что внутри {@code long} или {@code double}. Разница видна только
 * реализации ({@link IntValue} / {@link FloatValue}) и нужна ей, чтобы
 * {@code 2 + 2} оставалось {@code 4}, а не превращалось в {@code 4.0}.
 */
public enum ValueType {

    NULL("null", "null"),
    BOOL("bool", "логическое"),
    NUMBER("number", "число"),
    STRING("string", "строка"),
    ARRAY("array", "массив"),
    OBJECT("object", "объект"),
    FUNCTION("function", "функция"),
    CLASS("class", "класс"),
    TRAIT("trait", "трейт"),
    MODULE("module", "модуль"),
    RANGE("range", "диапазон");

    private final String id;
    private final String title;

    ValueType(String id, String title) {
        this.id = id;
        this.title = title;
    }

    /** Имя типа для самого языка: то, что вернёт будущий {@code typeof}. */
    public String id() {
        return id;
    }

    /** Имя типа для сообщений человеку. */
    public String title() {
        return title;
    }

    @Override
    public String toString() {
        return id;
    }
}
