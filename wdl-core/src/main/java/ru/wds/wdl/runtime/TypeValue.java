package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;

/**
 * Дескриптор типа значения: {@code Number}, {@code String}, {@code Object}, …
 * <p>
 * Типы языка ({@link ValueType}) теперь видны и самому скрипту — не только
 * строкой из {@code typeof}, но и значением, стоящим в корневой области обычным
 * именем: {@code x is Number}, {@code typeof(1) == Number}. Каждому элементу
 * {@link ValueType} соответствует ровно один такой дескриптор — их держит
 * {@link Types}.
 * <p>
 * <b>Реализация {@link ClassValue}, а не восьмой тип значения.</b> {@code ClassValue}
 * — {@code non-sealed} именно ради таких случаев: список типов языка ({@link ValueType})
 * остаётся закрытым, а дескриптор при этом получает даром всё, что уже умеет класс —
 * {@code is}, обращение по ключу через {@code statics()}, печать. Отдельная реализация,
 * а не {@link WdlClass} или {@code bridge.NativeClass}: тем двум положено создавать
 * экземпляры и пускать запись в свою статику, а дескриптору запрещено и то и другое —
 * втискивать этот запрет в реализации, для которых он не норма, значило бы городить
 * в них специальный случай.
 * <p>
 * <b>Живёт в {@code runtime}, а не в {@code value}.</b> Отказ {@link #instantiate}
 * и {@link #staticsWritable()} выражается {@link WdlRuntimeError}, а пакет
 * {@code value} от {@code runtime} не зависит и зависеть не должен — тот же довод,
 * по которому {@link WdlClass} лежит здесь же.
 * <p>
 * <b>Один экземпляр на тип, статикой.</b> Дескриптор неизменяем и состояния запуска
 * не несёт, а значит это тот же случай, что таблицы {@code parser.Operators}:
 * статическая карта в {@link Types}, а не сборка заново на каждый запуск. Инвариант
 * «никакой изменяемой статики в ядре» этим не нарушается — под запретом изменяемая
 * статика, а дескриптор неизменяем. Отсюда бесплатно следует и то, что
 * {@code typeof(a) == typeof(b)} работает: {@code Operations.equal} для не-чисел
 * сравнивает {@code equals}, а у дескриптора это тождество ссылок, потому что
 * {@code equals} не переопределён.
 * <p>
 * <b>Цена статики.</b> {@link #statics()} у дескриптора общая на весь процесс,
 * а не на запуск, — поэтому запись в неё {@linkplain #staticsWritable() запрещена}:
 * разреши её, и одна строка одного скрипта меняла бы дескриптор всем остальным
 * интерпретаторам в этом же процессе.
 * <p>
 * <b>Имя — с большой буквы.</b> {@code Number}, а не {@code number}: строчное имя
 * слишком похоже на обычную переменную в чужих скриптах. При этом дескриптор — не
 * константа, а обычное значение в обычной переменной корневой области, ровно как
 * {@code println} и классы прелюдии: пространство имён одно, и скрипт вправе
 * его перекрыть — {@code Number = 5} ломает только тот скрипт, который это сделал.
 * <p>
 * <b>{@link #display()} и {@link #name()} расходятся осознанно.</b> {@code display()}
 * — идентификатор типа ({@code "number"}), тот же, что был у {@code typeof} строкой:
 * все места, которые печатают результат {@code typeof}, продолжают печатать то же
 * самое. {@code name()} — имя значения для сообщений об ошибках, оно с большой буквы,
 * как и стоит в корневой области ({@code "Number"}).
 */
public final class TypeValue implements ClassValue {

    /**
     * Единственный ключ {@link #statics()}: справка о самом типе, а не о его членах.
     * Правило из большого плана — «сведения о типе и члены типа не лежат в одной
     * карте», иначе будущий член {@code Array.name} и имя типа {@code Array.name}
     * стали бы неразрешимым конфликтом.
     */
    private static final String INFO = "info";

    private final ValueType valueType;
    private final String name;
    private final MapValue statics;

    TypeValue(ValueType valueType) {
        this.valueType = valueType;
        this.name = capitalize(valueType.id());
        MapValue info = new MapValue();
        info.put("name", StringValue.of(valueType.id()));
        info.put("title", StringValue.of(valueType.title()));
        MapValue table = new MapValue();
        table.put(INFO, info);
        this.statics = table;
    }

    private static String capitalize(String id) {
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    @Override
    public String name() {
        return name;
    }

    /** Тип, о котором этот дескриптор: он же ключ таблицы членов. */
    public ValueType valueType() {
        return valueType;
    }

    /**
     * Ключ в таблице членов — сам тип, а не значение-дескриптор: {@code extend Array}
     * добавляет член всем массивам, а не одному значению, стоящему под именем
     * {@code Array}.
     */
    @Override
    public Object memberKey() {
        return valueType;
    }

    /**
     * {@code new Number()} обязан дойти до тела, а не остановиться на проверке числа
     * аргументов, — поэтому арность принимает ровно ноль, и отказ формулирует сам
     * {@link #instantiate}, а не безликое сравнение количеств.
     */
    @Override
    public Arity arity() {
        return Arity.exactly(0);
    }

    /**
     * Дескриптор — тип, а не класс: у него нет заголовка, и создавать им нечего.
     * Сообщение говорит именно это, а не «нет такого конструктора», потому что причина
     * не в отсутствии конструктора, а в самой природе значения.
     */
    @Override
    public Value instantiate(List<Value> arguments, CallContext context, Span span) {
        throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "'" + name
                + "' — тип, а не класс: типом нельзя создать экземпляр");
    }

    /** У дескриптора нет методов: он ничего не знает про экземпляры этого типа. */
    @Override
    public FunctionValue method(InstanceObjectValue instance, String memberName) {
        return null;
    }

    @Override
    public MapValue statics() {
        return statics;
    }

    /**
     * Дескриптор один на процесс — запись в него была бы состоянием, которое
     * переживает свой запуск. См. {@link ClassValue#staticsWritable()}.
     */
    @Override
    public boolean staticsWritable() {
        return false;
    }

    /**
     * Единственное место, где живёт ответ «значение этого типа»: у обычного класса
     * вопрос решает цепочка предков, у дескриптора — совпадение {@link ValueType}.
     * Иерархии у типов нет, поэтому сравнение — просто равенство перечисления.
     */
    @Override
    public boolean matches(Value value) {
        return value.type() == valueType;
    }

    /**
     * Дескриптор ничему не наследует и никем не подмешан: сам себе тип и предок.
     * {@code Number is Number} поэтому отвечает {@code false} — «is» спрашивает
     * дескриптор {@code Number}, а слева стоит он сам, не значение типа {@code number}.
     */
    @Override
    public boolean conformsTo(Value classOrTrait) {
        return classOrTrait == this;
    }

    /** То же, что вернёт {@code typeof} у значения этого типа: {@code "number"}. */
    @Override
    public String display() {
        return valueType.id();
    }

    @Override
    public String toString() {
        return display();
    }
}
