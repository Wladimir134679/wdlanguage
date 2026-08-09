package ru.wds.wdl.embed;

import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.types.InstanceObjectValue;

/**
 * Экземпляр класса, встроенного приложением.
 * <p>
 * Для скрипта это обычный экземпляр: {@code typeof} даёт {@code object}, поля
 * читаются точкой и перебираются циклом, печать показывает имя класса. Отличие
 * одно — {@link #state()}: место для Java-объекта, который значениями языка
 * не выражается, вроде открытого потока, соединения или генератора чисел.
 * <p>
 * <b>Что выразимо — лежит полями.</b> Путь к файлу это строка, значит и хранить
 * его надо полем: тогда {@code f.path}, {@code println(f)} и перебор работают
 * сами собой, без единой строчки в библиотеке. Состояние остаётся для того,
 * что полем не сделать.
 */
public class NativeInstance extends InstanceObjectValue {

    private Object state;

    NativeInstance(ClassValue owner) {
        super(owner);
    }

    /** Java-объект, который держит этот экземпляр, или {@code null}. */
    public Object state() {
        return state;
    }

    /**
     * Состояние заданного типа.
     * <p>
     * Ошибка приведения здесь означала бы, что метод одного класса позвали
     * на экземпляре другого, — а это невозможно: метод ищется в классе объекта.
     */
    public <T> T state(Class<T> type) {
        return type.cast(state);
    }

    public void state(Object newState) {
        this.state = newState;
    }
}
