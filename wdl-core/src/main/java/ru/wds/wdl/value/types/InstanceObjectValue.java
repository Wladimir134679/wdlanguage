package ru.wds.wdl.value.types;

import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Value;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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

    /**
     * Замок {@code synchronized}-методов этого объекта. Заводится лениво: экземпляров
     * в скрипте много, а методов с модификатором — единицы.
     * <p>
     * <b>Замок принадлежит экземпляру, а не методу</b>, и это то же правило, что в Java,
     * и то же, чего ждёт интуиция: метод защищает поля своего объекта, поэтому два
     * {@code synchronized}-метода одного объекта взаимно исключаются, а два разных
     * объекта друг другу не мешают. Замок на методе давал бы обратное — и был бы почти
     * всегда не тем, что нужно.
     * <p>
     * {@link ReentrantLock}, а не {@code synchronized}: вход в него обязан быть
     * прерываемым, иначе {@code t.interrupt()} не снял бы поток, застрявший на замке,
     * и остановка зациклившегося скрипта перестала бы работать.
     */
    private volatile ReentrantLock guard;

    /**
     * Скрытые поля свойств: {@code property x = 0}.
     * <p>
     * <b>Отдельно от карты пар, а не рядом с ней.</b> Лежи такое поле среди обычных —
     * и правило чтения «сначала собственное поле» побеждало бы, а getter не вызывался
     * бы никогда; вдобавок {@code x} попадал бы в перебор, в {@code len} и в печать,
     * то есть скрытым бы не был. Здесь его не видит ничто, кроме аксессоров самого
     * свойства, где оно доступно под именем {@code field}.
     * <p>
     * Заводится лениво и по той же причине, что {@link #guard}: экземпляров в скрипте
     * много, а свойств со скрытым полем — единицы, и платить за них полем в каждом
     * объекте было бы неправильной сделкой.
     */
    private volatile Map<String, Value> hidden;

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

    /**
     * Замок {@code synchronized}-методов — общий на объект и на все виды {@code super}
     * над ним.
     * <p>
     * Спрашивается у {@link #identity()}, а не у {@code this}: вид {@code super} — это
     * тот же объект, и защищать он обязан те же поля. Отдельный замок у вида означал бы,
     * что {@code super.push()} и {@code push()} могут идти одновременно поверх одного
     * массива.
     */
    public ReentrantLock guard() {
        InstanceObjectValue self = identity();
        return self == this ? ownGuard() : self.guard();
    }

    private ReentrantLock ownGuard() {
        ReentrantLock known = guard;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (guard == null) {
                guard = new ReentrantLock();
            }
            return guard;
        }
    }

    /**
     * Значение скрытого поля свойства или {@code null}, если его ещё не записали.
     * <p>
     * Спрашивается у {@link #identity()}, как и замок: вид {@code super} — тот же
     * объект, и скрытое поле у него то же самое. Иначе {@code super.x} и {@code x}
     * читали бы две разные ячейки одного имени.
     */
    public Value hidden(String name) {
        InstanceObjectValue self = identity();
        return self == this ? self.ownHidden(name, null, false) : self.hidden(name);
    }

    /** Записывает скрытое поле свойства. */
    public void hidden(String name, Value value) {
        InstanceObjectValue self = identity();
        if (self == this) {
            ownHidden(name, Objects.requireNonNull(value, "value"), true);
        } else {
            self.hidden(name, value);
        }
    }

    /**
     * Одно место на чтение и запись: таблица заводится лениво, и делать это дважды
     * двумя почти одинаковыми методами было бы приглашением развести их со временем.
     */
    private Value ownHidden(String name, Value value, boolean write) {
        Map<String, Value> known = hidden;
        if (known == null) {
            if (!write) {
                return null;
            }
            synchronized (this) {
                if (hidden == null) {
                    // Порядок вставки не важен — снаружи эту таблицу не перебирают,
                    // — а конкурентная карта снимает замок с каждого чтения свойства.
                    hidden = new ConcurrentHashMap<>();
                }
                known = hidden;
            }
        }
        if (!write) {
            return known.get(name);
        }
        known.put(name, value);
        return value;
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
