package ru.wds.wdl.interop;

import ru.wds.wdl.embed.NativeInstance;

import java.util.Objects;

/**
 * Java-объект в скрипте.
 * <p>
 * Для языка это обычный экземпляр класса: {@code typeof} даёт {@code object},
 * {@code is} отвечает, члены ищутся тем же путём, что у всех. Нового типа значения
 * мост не заводит намеренно — новый элемент в {@code ValueType} означал бы ветку
 * «а если это Java» в каждом посетителе ядра, и отвечала бы она ровно то же, что
 * отвечает объект.
 * <p>
 * Наследуется от {@link NativeInstance} тоже не ради экономии: там уже сделано всё,
 * что нужно, — {@code volatile} состояние (объект заводит один поток, а читает
 * почти всегда другой) и сообщения об ошибках той же формы, что у классов
 * от приложения.
 *
 * <h2>Полей у него нет</h2>
 * Ни одного: {@code x.name} у обёртки — это <b>свойство</b> над Java-полем, а не
 * записанное значение. Правило то же, что у {@code embed.NativeGetter}: значение
 * живёт в Java-объекте и меняется за спиной скрипта, а поле, записанное однажды,
 * к следующему обращению устареет.
 */
public final class JavaInstance extends NativeInstance {

    JavaInstance(JavaClass owner, Object object) {
        super(owner);
        state(Objects.requireNonNull(object, "object"));
    }

    /** Java-объект за этой обёрткой. */
    public Object object() {
        return state();
    }

    /**
     * Печать — это {@code toString()} объекта.
     * <p>
     * {@code println(date)} должен показать {@code 2026-08-29}, а не перечень полей,
     * которых у обёртки нет: {@code toString} для того и существует. Исключение
     * из чужого {@code toString} подменяется на имя класса с хешем — падение печати
     * это худший способ узнать о чужом баге.
     * <p>
     * {@code StackOverflowError} ловится наравне с обычными исключениями, потому что
     * это самая частая поломка именно в {@code toString} — объект, который печатает
     * сам себя по кругу. Остальные {@code Error} идут наверх: подменять отказ
     * виртуальной машины строкой значит скрыть его.
     */
    @Override
    public String display() {
        Object object = state();
        try {
            String text = String.valueOf(object);
            return text == null ? owner().name() : text;
        } catch (RuntimeException | StackOverflowError failure) {
            return object.getClass().getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(object));
        }
    }

    @Override
    public String toString() {
        return display();
    }
}
