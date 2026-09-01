package ru.wds.wdl.bridge;

import ru.wds.wdl.runtime.Args;
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

    /**
     * {@code volatile}, потому что состояние заводит один поток, а читает почти всегда
     * другой: сокет пишет его в {@code init}, а колбэк {@code onLine} читает из своего
     * потока; окно Swing — из EDT. Без этого чужой поток вправе увидеть {@code null}
     * там, где объект давно создан.
     */
    private volatile Object state;

    public NativeInstance(ClassValue owner) {
        super(owner);
    }

    /**
     * Экземпляр, на котором зовут член своего класса.
     * <p>
     * Проверка здесь формальная и никогда не срабатывает: метод и свойство ищутся
     * <b>в классе объекта</b>, а экземпляры этого класса создаёт только его же
     * {@code instantiate}. Написана она один раз и в одном месте потому, что нужна
     * в пяти — у метода нативного класса, у его свойства, у поля и свойства моста, —
     * и пять одинаковых проверок с пятью одинаковыми текстами уже начинали расходиться.
     *
     * @throws IllegalStateException если экземпляр собрали в обход {@code instantiate}
     */
    public static NativeInstance receiverOf(Value receiver, String className) {
        if (receiver instanceof InstanceObjectValue instance
                && instance.identity() instanceof NativeInstance self) {
            return self;
        }
        throw new IllegalStateException("экземпляр класса '" + className
                + "' создан в обход instantiate");
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
     * Печать: у обёртки — {@code toString()} объекта, у своего класса — как у всех.
     * <p>
     * {@code println(date)} должен показать {@code 2026-08-29}, а не перечень полей,
     * которых у обёртки нет ({@link NativeClass.Builder#wrapper}): {@code toString}
     * для того и существует. Исключение из чужого {@code toString} подменяется именем
     * класса с хешем — падение печати это худший способ узнать о чужом баге.
     * <p>
     * {@code StackOverflowError} ловится наравне с обычными исключениями, потому что
     * это самая частая поломка именно в {@code toString} — объект, который печатает
     * сам себя по кругу. Остальные {@code Error} идут наверх: подменять отказ
     * виртуальной машины строкой значит скрыть его.
     */
    @Override
    public String display() {
        if (!(owner() instanceof NativeClass declared) || !declared.isWrapper()) {
            return super.display();
        }
        Object object = state;
        try {
            String text = String.valueOf(object);
            return text == null ? owner().name() : text;
        } catch (RuntimeException | StackOverflowError failure) {
            return object == null ? owner().name() : object.getClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(object));
        }
    }

    @Override
    public String toString() {
        return display();
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
