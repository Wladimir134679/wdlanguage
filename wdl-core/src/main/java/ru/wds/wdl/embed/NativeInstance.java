package ru.wds.wdl.embed;

import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.StringValue;

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

    public NativeInstance(ClassValue owner) {
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

    /**
     * Поле строкой.
     * <p>
     * Проверять приходится на каждом чтении, а не один раз при создании: поле обычное,
     * и {@code f.path = 5} — законная запись, о которой библиотека узнаёт только здесь.
     * Сообщение той же формы, что у {@link Args}, и с тем же классом ошибки.
     */
    public String string(String field, Span span) {
        Value value = get(field);
        if (value instanceof StringValue text) {
            return text.value();
        }
        throw wrong(field, span, "ожидалась строка");
    }

    /**
     * Ошибка «в поле не то» — для проверок, которых здесь нет.
     * <p>
     * Возвращает, а не бросает: {@code throw} на месте использования видно лучше.
     */
    public WdlRuntimeError wrong(String field, Span span, String expected) {
        return new WdlRuntimeError(ErrorKind.TYPE, span,
                Args.because(owner().name() + "." + field, expected, get(field)));
    }
}
