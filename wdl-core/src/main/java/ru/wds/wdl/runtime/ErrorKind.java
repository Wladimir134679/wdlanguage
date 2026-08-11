package ru.wds.wdl.runtime;

/**
 * Класс ошибки движка: то, что стоит перед сообщением и то, что ловит {@code catch}.
 * <p>
 * Дробление нужно ради одного — осмысленной реакции. {@code catch (e is IndexError)}
 * значит «взять значение по умолчанию», {@code catch (e is RuntimeError)} — «любая
 * ошибка движка», {@code catch (e)} — «вообще всё». Три уровня грубости вместо одного,
 * и ни один не требует разбирать строку сообщения.
 * <p>
 * Перечисление, а не строка, потому что имена здесь — не текст, а часть языка:
 * им соответствуют классы {@linkplain Prelude прелюдии}, и разойтись эти два списка
 * не должны. Проверяет соответствие {@link PreludeTypes#captureFrom}.
 * <p>
 * Библиотеки заводят свои классы ошибок сами и сюда не попадают: {@code IoError}
 * принадлежит {@code sys.io}, а не движку.
 */
public enum ErrorKind {

    /** Корень всего ловимого: {@code catch (e is Exception)} и {@code catch (e)}. */
    EXCEPTION("Exception", null),

    /** Любая ошибка движка. Класс по умолчанию для броска, у которого вид не проставлен. */
    RUNTIME("RuntimeError", EXCEPTION),

    /** {@code "a" - 1}, {@code for (x in 5)}, обращение к числу через точку, {@code len(true)}. */
    TYPE("TypeError", RUNTIME),

    /** Имени нет в области видимости. */
    NAME("NameError", RUNTIME),

    /** Индекс вне границ, нецелый индекс, запись в строку. */
    INDEX("IndexError", RUNTIME),

    /** Деление на ноль, остаток от нуля. */
    ARITHMETIC("ArithmeticError", RUNTIME),

    /** Значение недопустимо для операции: неверный путь, неразбираемый JSON. */
    VALUE("ValueError", RUNTIME),

    /** Вызов не-функции, неверное число аргументов, {@code new} от не-класса. */
    CALL("CallError", RUNTIME),

    /** Присваивание константе, невыполненное требование трейта, {@code new} от трейта. */
    DECLARATION("DeclarationError", RUNTIME),

    /** Модуль не найден, ошибка разбора модуля, упавшая фабрика библиотеки. */
    IMPORT("ImportError", RUNTIME),

    /**
     * То, что прилетело из Java и не стало ошибкой скрипта само.
     * <p>
     * Не под {@link #RUNTIME}: ошибся не движок, а библиотека, которую положило
     * в область видимости приложение, — и {@code catch (e is RuntimeError)} не должен
     * ловить чужой {@code NullPointerException} заодно с делением на ноль.
     */
    JAVA("JavaException", EXCEPTION);

    private final String title;
    private final ErrorKind parent;

    ErrorKind(String title, ErrorKind parent) {
        this.title = title;
        this.parent = parent;
    }

    /** Имя класса так, как оно написано в прелюдии и как его видит скрипт. */
    public String title() {
        return title;
    }

    /** Родительский класс или {@code null} у корня иерархии. */
    public ErrorKind parent() {
        return parent;
    }

    @Override
    public String toString() {
        return title;
    }
}
