package ru.wds.wdl.embed;

import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;

/**
 * Аргументы вызова: список значений, умеющий отвечать за их тип.
 * <p>
 * Библиотеке почти никогда не нужен просто {@code Value} — нужен путь строкой, размер
 * целым, опции объектом. Раньше эту проверку писали руками в каждом теле, и текст ошибки
 * у каждой библиотеки получался свой; здесь она написана один раз, вместе с сообщением
 * и классом ошибки.
 * <pre>{@code
 * .method("write", Arity.exactly(1), (self, context, args, span) -> {
 *     String data = args.string(0, "содержимое");
 *     ...
 * })
 * }</pre>
 * <b>Это обычный {@code List<Value>}</b>: {@code args.get(0)}, {@code args.size()}
 * и перебор работают как прежде, поэтому тело, которому проверка не нужна, ничего
 * о существовании этого класса знать не обязано.
 * <p>
 * Число аргументов проверено до входа в тело, по {@link ru.wds.wdl.value.Arity}. Отсюда
 * разделение: {@link #get(int)} следует договору списка и падает за границей, а
 * {@link #at(int)} отвечает {@code null}-значением — необязательный аргумент в конце
 * законен, и спрашивать про него можно спокойно.
 */
public final class Args extends AbstractList<Value> {

    private final String callee;
    private final List<Value> values;
    private final CallContext context;
    private final Span span;

    private Args(String callee, List<Value> values, CallContext context, Span span) {
        this.callee = Objects.requireNonNull(callee, "callee");
        this.values = Objects.requireNonNull(values, "values");
        this.context = Objects.requireNonNull(context, "context");
        this.span = Objects.requireNonNull(span, "span");
    }

    /**
     * Аргументы вызова.
     *
     * @param callee  имя для сообщений: {@code "pow"}, {@code "File.read"}, {@code "new File"}
     * @param values  значения по порядку
     * @param context среда вызова — нужна затем, чтобы аргумент-функцию можно было
     *                не только опознать, но и позвать: см. {@link #callback}
     * @param span    место вызова в исходнике
     */
    public static Args of(String callee, List<Value> values, CallContext context, Span span) {
        return new Args(callee, values, context, span);
    }

    /** Среда вызова: вывод и глубина. То же, что приходит в тело вторым аргументом. */
    public CallContext context() {
        return context;
    }

    /** Имя вызываемого — то, с чего начинается сообщение об ошибке. */
    public String callee() {
        return callee;
    }

    /** Место вызова в исходнике: то же, что приходит третьим аргументом в тело. */
    public Span span() {
        return span;
    }

    @Override
    public Value get(int index) {
        return values.get(index);
    }

    @Override
    public int size() {
        return values.size();
    }

    /**
     * Значение аргумента без проверки типа; за концом списка — {@code null} языка.
     * <p>
     * Именно {@code null}, а не исключение: необязательный аргумент отличается
     * от переданного {@code null} только тем, что его не написали, — и библиотеке
     * это различие почти никогда не нужно.
     */
    public Value at(int index) {
        return index >= 0 && index < values.size() ? values.get(index) : NullValue.NULL;
    }

    /** Передан ли аргумент и не {@code null} ли он — вопрос про необязательные. */
    public boolean has(int index) {
        return at(index) != NullValue.NULL;
    }

    public String string(int index, String role) {
        Value value = at(index);
        if (value instanceof StringValue string) {
            return string.value();
        }
        throw wrong(index, role, "ожидалась строка");
    }

    public String string(int index) {
        return string(index, null);
    }

    /** Строка или значение по умолчанию, если аргумента нет. */
    public String string(int index, String role, String fallback) {
        return has(index) ? string(index, role) : fallback;
    }

    public NumberValue number(int index, String role) {
        Value value = at(index);
        if (value instanceof NumberValue number) {
            return number;
        }
        throw wrong(index, role, "ожидалось число");
    }

    public NumberValue number(int index) {
        return number(index, null);
    }

    /** Число как {@code double} — когда целое от вещественного не отличают. */
    public double real(int index, String role) {
        return number(index, role).asDouble();
    }

    public double real(int index, String role, double fallback) {
        return has(index) ? real(index, role) : fallback;
    }

    /**
     * Целое число. {@code 2.5} и {@code 2.0} не подходят: там, где нужен индекс,
     * размер или счётчик, вещественное — это ошибка автора скрипта, а не повод
     * молча отбросить дробную часть.
     */
    public long integer(int index, String role) {
        Value value = at(index);
        if (value instanceof NumberValue number && number.isInteger()) {
            return number.asLong();
        }
        throw wrong(index, role, "ожидалось целое число");
    }

    public long integer(int index) {
        return integer(index, null);
    }

    public long integer(int index, String role, long fallback) {
        return has(index) ? integer(index, role) : fallback;
    }

    /**
     * Логическое значение — именно оно, а не истинность чего угодно.
     * <p>
     * Истинность в языке есть у любого значения, но параметр вроде {@code sorted}
     * от того, что ему передали строку, понятнее не становится.
     */
    public boolean flag(int index, String role) {
        Value value = at(index);
        if (value instanceof BoolValue bool) {
            return bool.value();
        }
        throw wrong(index, role, "ожидалось логическое значение");
    }

    public boolean flag(int index, String role, boolean fallback) {
        return has(index) ? flag(index, role) : fallback;
    }

    public ArrayValue array(int index, String role) {
        Value value = at(index);
        if (value instanceof ArrayValue array) {
            return array;
        }
        throw wrong(index, role, "ожидался массив");
    }

    public ArrayValue array(int index) {
        return array(index, null);
    }

    /**
     * Объект: и словарь {@code {a: 1}}, и экземпляр класса — для языка это один тип.
     */
    public MapValue object(int index, String role) {
        Value value = at(index);
        if (value instanceof MapValue object) {
            return object;
        }
        throw wrong(index, role, "ожидался объект");
    }

    public MapValue object(int index) {
        return object(index, null);
    }

    /** Объект или пустой, если аргумента нет: обычный случай для опций вызова. */
    public MapValue object(int index, String role, MapValue fallback) {
        return has(index) ? object(index, role) : fallback;
    }

    /**
     * Функция — то, что библиотека сможет позвать: {@code value.call(context, args, span)}.
     */
    public FunctionValue function(int index, String role) {
        Value value = at(index);
        if (value instanceof FunctionValue function) {
            return function;
        }
        throw wrong(index, role, "ожидалась функция");
    }

    public FunctionValue function(int index) {
        return function(index, null);
    }

    /**
     * Аргумент-функция, уже готовая к вызову: {@code handler.call(item)}.
     * <p>
     * То же, что {@link #function}, только без обязанности таскать за собой контекст
     * и место вызова — они у этого объекта уже есть. Обычной библиотеке нужен именно
     * этот метод: обработчик, переданный скриптом, надо звать, а не разглядывать.
     * <pre>{@code
     * Callback handler = args.callback(0, "обработчик");
     * handler.call(StringValue.of(line));
     * }</pre>
     */
    public Callback callback(int index, String role) {
        return Callback.of(function(index, role), context, span);
    }

    public Callback callback(int index) {
        return callback(index, null);
    }

    /**
     * Экземпляр заданного класса или трейта — то же, что {@code is} в скрипте:
     * подходит и экземпляр наследника, и класс, подмешавший трейт.
     * <p>
     * Спрашивать про трейт — обычное дело для библиотеки, которая отдала скрипту
     * контракт ({@link NativeTrait}) и теперь получает то, что его выполняет.
     *
     * @param classOrTrait {@link ClassValue} или {@link ru.wds.wdl.value.TraitValue}
     */
    public InstanceObjectValue instance(int index, String role, Value classOrTrait) {
        Objects.requireNonNull(classOrTrait, "classOrTrait");
        Value value = at(index);
        if (value instanceof InstanceObjectValue instance
                && instance.owner().conformsTo(classOrTrait)) {
            return instance;
        }
        throw wrong(index, role, "ожидался экземпляр " + describe(classOrTrait));
    }

    private static String describe(Value classOrTrait) {
        return switch (classOrTrait) {
            case ClassValue declared -> "класса '" + declared.name() + "'";
            case TraitValue trait -> "трейта '" + trait.name() + "'";
            default -> "типа " + classOrTrait.type().title();
        };
    }

    /**
     * Ошибка «не тот тип» — для проверок, которых здесь нет.
     * <p>
     * Возвращает, а не бросает, чтобы на месте использования стояло {@code throw}
     * и было видно, что выполнение здесь кончается.
     *
     * @param role     роль аргумента в вызове или {@code null} — тогда «аргумент N»
     * @param expected чего ждали: {@code "ожидался путь"} — с согласованием по роду,
     *                 потому что род знает только тот, кто пишет проверку
     */
    public WdlRuntimeError wrong(int index, String role, String expected) {
        return new WdlRuntimeError(ErrorKind.TYPE, span, message(index, role, expected));
    }

    /**
     * Ошибка «тип тот, а значение не годится»: отрицательный размер, пустой массив,
     * адрес без схемы. Отличается от {@link #wrong} только классом ошибки, и это
     * различие видно скрипту: {@code catch (e is ValueError)}.
     */
    public WdlRuntimeError bad(int index, String role, String expected) {
        return new WdlRuntimeError(ErrorKind.VALUE, span, message(index, role, expected));
    }

    private String message(int index, String role, String expected) {
        return because(callee + "(): " + (role == null ? "аргумент " + (index + 1) : role),
                expected, at(index));
    }

    /**
     * Общий вид сообщения о неподходящем значении: где, чего ждали, что пришло.
     * <p>
     * Один шаблон на все библиотеки — им же пользуются {@link NativeInstance} для полей
     * и сам этот класс для аргументов, — чтобы автор скрипта читал ошибку {@code sys.io}
     * и ошибку чужого модуля одинаково. Открыт наружу ради того, что сюда не поместится:
     * значение внутри переданного объекта, элемент массива, поле конфигурации.
     * <pre>{@code
     * throw new WdlRuntimeError(ErrorKind.TYPE, span,
     *         Args.because("http: 'headers'", "ожидался объект", given));
     * }</pre>
     */
    public static String because(String subject, String expected, Value actual) {
        return subject + ": " + expected + ", а здесь " + actual.type().title() + " (" + actual + ")";
    }
}
