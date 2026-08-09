package ru.wds.wdl.value.types;

import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Value;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Экземпляр класса: тот же набор пар «ключ — значение», но собранный по
 * {@linkplain ClassValue классу} и знающий, какому классу принадлежит.
 * <p>
 * <b>Не восьмой тип значения.</b> {@code typeof(p)} даёт {@code object}, и всё, что
 * умеет {@link MapValue}, экземпляр умеет тоже — обращение по вычисленному ключу,
 * перебор полей, {@code len}, запись нового ключа. Класс добавляет к этому ровно
 * три вещи: имя в печати, ответ оператору {@code is} и таблицу методов, в которой
 * ищут после полей.
 * <p>
 * Поля пишутся при создании по плоской таблице класса — родительские, из трейтов,
 * свои, — каждое ровно один раз. Что завёл конструктор через {@code this}, идёт
 * после них: порядок пар у объекта это порядок вставки, отдельного правила
 * для экземпляра нет.
 */
public non-sealed class InstanceObjectValue extends MapValue {

    private final ClassValue owner;

    /** С какого класса искать метод. Совпадает с {@link #owner}, кроме вида {@code super}. */
    private final ClassValue lookupFrom;

    /** Корневой экземпляр, если это вид {@code super}, иначе {@code null}. */
    private final InstanceObjectValue root;

    private InstanceObjectValue(Map<Value, Value> entries, ClassValue owner,
                                ClassValue lookupFrom, InstanceObjectValue root) {
        super(entries);
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lookupFrom = lookupFrom != null ? lookupFrom : owner;
        this.root = root;
    }

    /**
     * Экземпляр класса, встроенного приложением.
     * <p>
     * Открыто для наследования ровно затем, чтобы такой экземпляр мог носить
     * Java-состояние — открытый поток, соединение, генератор, — которое значениями
     * языка не выражается. Всё, что выразимо, лежит обычными полями.
     */
    protected InstanceObjectValue(ClassValue owner) {
        this(new LinkedHashMap<>(), owner, null, null);
    }

    /** Пустой экземпляр класса: поля в него запишет создание. */
    public static InstanceObjectValue of(ClassValue owner) {
        return new InstanceObjectValue(new LinkedHashMap<>(), owner, null, null);
    }

    /**
     * Вид {@code super}: <b>тот же</b> экземпляр, но поиск методов начинается
     * с переданного класса.
     * <p>
     * Карта полей общая, а не копия: {@code super.имя = 1} меняет то же самое поле.
     * Вид равен своему экземпляру ({@code super == this} истинно) и печатается как он,
     * потому что это он и есть — отличается только точка отсчёта для метода.
     */
    public static InstanceObjectValue viewOf(InstanceObjectValue instance, ClassValue from) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(from, "from");
        return new InstanceObjectValue(instance.entries, instance.owner, from, instance.identity());
    }

    /** Класс экземпляра. */
    public ClassValue owner() {
        return owner;
    }

    /** Класс, с которого начинается поиск метода: у вида {@code super} — родительский. */
    public ClassValue lookupFrom() {
        return lookupFrom;
    }

    /**
     * Сам экземпляр, а не вид на него.
     * <p>
     * Метод связывается именно с ним, даже если прочитан через {@code super}: иначе
     * внутри полученного метода {@code area()} (виртуально) и {@code this.area()}
     * (от родителя) дали бы разное, а язык обещает, что это один и тот же вызов.
     */
    public InstanceObjectValue identity() {
        return root != null ? root : this;
    }

    @Override
    String prefix() {
        return owner.name();
    }

    /**
     * Экземпляры, как объекты и массивы, сравниваются по ссылке: сравнение по полям
     * стоит обхода всей структуры и требует решать, что делать с объектом, ссылающимся
     * сам на себя.
     * <p>
     * Единственная поправка — вид {@code super}: он равен своему экземпляру, потому
     * что это тот же самый объект, а не копия.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof InstanceObjectValue instance && identity() == instance.identity();
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(identity());
    }
}
