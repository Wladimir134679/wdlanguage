package ru.wds.wdl.bridge.reflect;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Перевод значений через границу с Java — <b>под целевой тип</b>.
 * <p>
 * Этим он отличается от {@code api.Values}, которая переводит без цели: там
 * «число языка» становится {@code Long} и на этом всё, а здесь известно, что метод
 * принимает {@code int}, — и число обязано в него влезть. Отсюда же берётся
 * {@link #cost}: чтобы выбрать перегрузку, надо сперва уметь сказать, насколько
 * хорошо аргумент подходит каждой.
 *
 * <h2>Сужение не молчит</h2>
 * Число, не влезшее в {@code int}, — это ошибка {@code VALUE}, а не тихо усечённое
 * значение. Язык держит один тип {@code number} именно затем, чтобы автор скрипта
 * не думал про размер машинного слова; молчаливое усечение вернуло бы ему эту заботу
 * в самом неприятном виде — неверным результатом без единого сообщения.
 *
 * <h2>Коллекции копируются</h2>
 * По той же причине, что в {@code api.Values}: иначе скрипт правил бы коллекцию
 * приложения из своего потока — молча, в обход всякой синхронизации, — а список,
 * отданный в скрипт, продолжал бы жить чужой жизнью. Копия честнее: у каждой стороны
 * своё, и видно, где граница.
 *
 * <h2>{@code enum} — это строка</h2>
 * В обе стороны и по имени константы: {@code d.dayOfWeek == "MONDAY"} работает,
 * а метод, принимающий {@code DayOfWeek}, зовётся строкой. Обёртка вокруг константы
 * дала бы её методы, но отняла бы сравнение, печать и запись в файл — а этим
 * в скрипте пользуются на порядок чаще.
 */
public final class Marshal {

    /** Аргумент этому типу не подходит вовсе. */
    public static final int IMPOSSIBLE = -1;

    /** Точное совпадение: {@code String} → {@code String}, обёртка → свой объект. */
    public static final int EXACT = 0;

    /** Худшее из подходящих: {@code Object}, сужение дробного к целому. */
    public static final int WORST = 8;

    /**
     * Что делать с Java-объектом, которому перевода нет.
     * <p>
     * Точка расширения, а не ветка внутри: заворачивать объект в класс языка
     * умеет мост, а он знает про политику, кэш классов и запуск — всё то, о чём
     * переводчику значений знать незачем.
     */
    @FunctionalInterface
    public interface Wrapping {

        /**
         * Оборачивает объект или отказывает.
         *
         * @return значение языка; {@code null}, если оборачивать этот объект нельзя, —
         *         тогда переводчик сам скажет об этом ошибкой с именем класса
         */
        Value wrap(Object object, Span span);
    }

    private final Wrapping wrapping;

    private Marshal(Wrapping wrapping) {
        this.wrapping = Objects.requireNonNull(wrapping, "wrapping");
    }

    public static Marshal of(Wrapping wrapping) {
        return new Marshal(wrapping);
    }

    /**
     * Переводчик без обёрток: всё, чему нет прямого перевода, — отказ.
     * <p>
     * Таким он и нужен там, где классов ещё нет: в тестах самого перевода
     * и в мосте, пока политика не разрешила оборачивать неизвестное.
     */
    public static Marshal plain() {
        return new Marshal((object, span) -> null);
    }

    // ------------------------------------------------------------------
    // Java → язык
    // ------------------------------------------------------------------

    /**
     * Java-объект как значение языка.
     *
     * @param span место в исходнике: туда укажет отказ, если объект переводу
     *             не поддаётся и оборачивать его нельзя
     */
    public Value toValue(Object object, Span span) {
        return switch (object) {
            case null -> NullValue.NULL;
            case Value value -> value;
            case String text -> StringValue.of(text);
            case Boolean flag -> BoolValue.of(flag);
            case Character symbol -> StringValue.of(String.valueOf(symbol));
            case Long number -> IntValue.of(number);
            case Integer number -> IntValue.of(number);
            case Short number -> IntValue.of(number);
            case Byte number -> IntValue.of(number);
            case Double number -> FloatValue.of(number);
            case Float number -> FloatValue.of(number);
            // Имя, а не порядковый номер: номер меняется при вставке константы
            // в середину перечисления, и скрипт, сравнивавший с числом, ломается молча.
            case Enum<?> constant -> StringValue.of(constant.name());
            // Пустой Optional — это отсутствие значения, и в языке оно уже есть.
            // Оборачивать сам Optional значило бы заставить скрипт звать get().
            case Optional<?> maybe -> maybe.isPresent() ? toValue(maybe.get(), span) : NullValue.NULL;
            case CharSequence text -> StringValue.of(text.toString());
            case Collection<?> items -> collection(items, span);
            case Map<?, ?> entries -> map(entries, span);
            default -> object.getClass().isArray() ? array(object, span) : wrap(object, span);
        };
    }

    private Value wrap(Object object, Span span) {
        Value wrapped = wrapping.wrap(object, span);
        if (wrapped != null) {
            return wrapped;
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, span, "нечем представить в языке объект типа "
                + object.getClass().getName() + ": откройте этот тип мосту (JavaBridge.expose) "
                + "или разрешите обёртки (JavaPolicy.wrapUnknown)");
    }

    private Value collection(Collection<?> items, Span span) {
        List<Value> values = new ArrayList<>(items.size());
        for (Object item : items) {
            values.add(toValue(item, span));
        }
        return ArrayValue.of(values);
    }

    private Value map(Map<?, ?> entries, Span span) {
        Map<Value, Value> values = new LinkedHashMap<>();
        entries.forEach((key, value) -> values.put(toValue(key, span), toValue(value, span)));
        return MapValue.of(values);
    }

    private Value array(Object javaArray, Span span) {
        int length = Array.getLength(javaArray);
        List<Value> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            values.add(toValue(Array.get(javaArray, i), span));
        }
        return ArrayValue.of(values);
    }

    // ------------------------------------------------------------------
    // Язык → Java
    // ------------------------------------------------------------------

    /**
     * Значение языка как Java-объект нужного типа.
     *
     * @param subject что это за место — для сообщения: {@code "Date.plusDays(): аргумент 1"}
     * @throws WdlRuntimeError если значение этому типу не подходит
     */
    public Object toJava(Value value, Class<?> target, String subject, Span span) {
        return toJava(value, target, subject, null, span);
    }

    /**
     * То же самое, но с контекстом вызова: он нужен ровно одному переводу —
     * функции скрипта в интерфейс Java, потому что позвать её без контекста нечем.
     * <p>
     * Отдельный параметр, а не поле переводчика: контекст меняется на каждом кадре
     * (вывод, глубина, трассировка), а переводчик один на весь мост.
     */
    public Object toJava(Value value, Class<?> target, String subject,
                         CallContext context, Span span) {
        Object result = convert(value, target, subject, context, span);
        if (result == NO) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    Args.because(subject, expected(target), value));
        }
        return result;
    }

    /**
     * Насколько хорошо значение подходит этому типу: {@link #EXACT} — точно,
     * {@link #IMPOSSIBLE} — никак, между ними — «подходит, но с приведением».
     * <p>
     * Считается <b>по значению, а не по типу значения</b>: строка длиной 1 подходит
     * {@code char}, а строка длиннее — нет; целое подходит {@code int}, если влезает.
     * Иначе выбор перегрузки уводил бы в метод, который потом откажется работать.
     */
    public int cost(Value value, Class<?> target) {
        if (asValue(value, target)) {
            return EXACT;
        }
        return switch (value) {
            case NullValue ignored -> target.isPrimitive() ? IMPOSSIBLE : 1;
            case BoolValue ignored -> target == boolean.class || target == Boolean.class
                    ? EXACT : object(target);
            case IntValue number -> integerCost(number.value(), target);
            case FloatValue number -> realCost(number.value(), target);
            case StringValue text -> stringCost(text.value(), target);
            case ArrayValue ignored -> listCost(target);
            case FunctionValue ignored -> JavaProxy.sam(target) != null ? 2 : object(target);
            case InstanceObjectValue instance -> instanceCost(instance, target);
            case MapValue ignored -> Map.class.isAssignableFrom(target) ? 3 : object(target);
            default -> object(target);
        };
    }

    /** Подходит ли этому типу хоть как-нибудь. */
    public boolean accepts(Value value, Class<?> target) {
        return cost(value, target) != IMPOSSIBLE;
    }

    private static int object(Class<?> target) {
        return target == Object.class ? WORST : IMPOSSIBLE;
    }

    private static int integerCost(long number, Class<?> target) {
        if (target == long.class || target == Long.class) {
            return EXACT;
        }
        if (target == double.class || target == Double.class) {
            return 2;
        }
        if (target == float.class || target == Float.class) {
            return 3;
        }
        if (fits(number, target)) {
            // Сужение с проверкой: int дешевле short и byte — не потому, что безопаснее,
            // а потому, что метод с int почти всегда и есть тот, который имели в виду.
            return target == int.class || target == Integer.class ? 1 : 4;
        }
        return target == Number.class ? 5 : object(target);
    }

    private static int realCost(double number, Class<?> target) {
        if (target == double.class || target == Double.class) {
            return EXACT;
        }
        if (target == float.class || target == Float.class) {
            return 1;
        }
        // Дробное в целое — только когда дроби нет. Разрешено вообще потому, что
        // деление в языке даёт дробное даже там, где результат целый, и запрет
        // ломал бы 'items.get(n / 2)' на ровном месте. Дороже всего — чтобы
        // перегрузка с double побеждала всегда, когда она есть.
        if (number == Math.rint(number) && !Double.isInfinite(number)
                && fits((long) number, target)) {
            return WORST;
        }
        return target == Number.class ? 5 : object(target);
    }

    private static boolean fits(long number, Class<?> target) {
        if (target == long.class || target == Long.class) {
            return true;
        }
        if (target == int.class || target == Integer.class) {
            return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
        }
        if (target == short.class || target == Short.class) {
            return number >= Short.MIN_VALUE && number <= Short.MAX_VALUE;
        }
        if (target == byte.class || target == Byte.class) {
            return number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE;
        }
        if (target == char.class || target == Character.class) {
            return number >= Character.MIN_VALUE && number <= Character.MAX_VALUE;
        }
        return false;
    }

    private static int stringCost(String text, Class<?> target) {
        if (target == String.class || target == CharSequence.class) {
            return EXACT;
        }
        if ((target == char.class || target == Character.class) && text.length() == 1) {
            return 3;
        }
        if (target.isEnum() && constant(target, text) != null) {
            return 2;
        }
        return object(target);
    }

    private static int listCost(Class<?> target) {
        if (target.isArray()) {
            return 3;
        }
        if (target == List.class || target == Collection.class || target == Iterable.class
                || target == Set.class || target == ArrayList.class || target == LinkedHashSet.class) {
            return 3;
        }
        return object(target);
    }

    private static int instanceCost(InstanceObjectValue instance, Class<?> target) {
        Object state = state(instance);
        if (state != null && target.isInstance(state)) {
            // Разворачивание обёртки — то же точное совпадение: за экземпляром
            // стоит ровно тот объект, которого метод и просит.
            return EXACT;
        }
        return Map.class.isAssignableFrom(target) ? 3 : object(target);
    }

    /** Java-объект за экземпляром или {@code null}: обёртка моста и нативный класс. */
    static Object state(Value value) {
        return value instanceof InstanceObjectValue instance
                && instance.identity() instanceof NativeInstance native_
                ? native_.state()
                : null;
    }

    /**
     * Просит ли Java само значение языка: параметр объявлен как {@code Value}
     * или как один из его видов.
     * <p>
     * Спрашивается до всего остального и <b>не</b> через {@code target.isInstance}:
     * значение языка — тоже {@code Object}, и голая проверка объявила бы точным
     * совпадением любой параметр типа {@code Object}, куда на самом деле уходит
     * переведённая строка или число.
     */
    private static boolean asValue(Value value, Class<?> target) {
        return target == Value.class
                || (Value.class.isAssignableFrom(target) && target.isInstance(value));
    }

    /** Метка «перевода нет» — отдельно от {@code null}, который законный результат. */
    private static final Object NO = new Object();

    private Object convert(Value value, Class<?> target, String subject,
                           CallContext context, Span span) {
        if (asValue(value, target)) {
            return value;
        }
        return switch (value) {
            case NullValue ignored -> target.isPrimitive() ? NO : null;
            case BoolValue flag -> target == boolean.class || target == Boolean.class
                    || target == Object.class ? flag.value() : NO;
            case IntValue number -> number(number.value(), true, target, subject, span);
            case FloatValue number -> real(number.value(), target, subject, span);
            case StringValue text -> string(text.value(), target);
            case ArrayValue items -> list(items, target, subject, context, span);
            case FunctionValue function -> proxy(function, target, context);
            case InstanceObjectValue instance -> instance(instance, target, subject, context, span);
            case MapValue entries -> Map.class.isAssignableFrom(target) || target == Object.class
                    ? toMap(entries, subject, context, span) : NO;
            default -> target.isInstance(value) ? value : NO;
        };
    }

    private Object number(long number, boolean integer, Class<?> target,
                          String subject, Span span) {
        if (target == long.class || target == Long.class) {
            return number;
        }
        if (target == double.class || target == Double.class) {
            return (double) number;
        }
        if (target == float.class || target == Float.class) {
            return (float) number;
        }
        if (target == int.class || target == Integer.class) {
            return (int) checked(number, Integer.MIN_VALUE, Integer.MAX_VALUE, "int", subject, span);
        }
        if (target == short.class || target == Short.class) {
            return (short) checked(number, Short.MIN_VALUE, Short.MAX_VALUE, "short", subject, span);
        }
        if (target == byte.class || target == Byte.class) {
            return (byte) checked(number, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte", subject, span);
        }
        if (target == char.class || target == Character.class) {
            return (char) checked(number, Character.MIN_VALUE, Character.MAX_VALUE, "char", subject, span);
        }
        if (target == Object.class || target == Number.class) {
            return integer ? (Object) number : (Object) (double) number;
        }
        return NO;
    }

    private Object real(double number, Class<?> target, String subject, Span span) {
        if (target == double.class || target == Double.class
                || target == Object.class || target == Number.class) {
            return number;
        }
        if (target == float.class || target == Float.class) {
            return (float) number;
        }
        if (number != Math.rint(number) || Double.isInfinite(number) || Double.isNaN(number)) {
            // Отказ именно здесь, а не в проверке диапазона: сказать «ожидалось целое»
            // про 2.5 понятнее, чем «не влезает в int».
            return isIntegral(target)
                    ? refuse(subject, "ожидалось целое число, а здесь " + number, span)
                    : NO;
        }
        return isIntegral(target) ? number((long) number, false, target, subject, span) : NO;
    }

    private static boolean isIntegral(Class<?> target) {
        return target == long.class || target == Long.class
                || target == int.class || target == Integer.class
                || target == short.class || target == Short.class
                || target == byte.class || target == Byte.class
                || target == char.class || target == Character.class;
    }

    private static long checked(long number, long min, long max, String type,
                                String subject, Span span) {
        if (number < min || number > max) {
            refuse(subject, "число " + number + " не помещается в " + type
                    + " (от " + min + " до " + max + ")", span);
        }
        return number;
    }

    private static Object refuse(String subject, String reason, Span span) {
        throw new WdlRuntimeError(ErrorKind.VALUE, span, subject + ": " + reason);
    }

    private Object string(String text, Class<?> target) {
        if (target == String.class || target == CharSequence.class || target == Object.class) {
            return text;
        }
        if ((target == char.class || target == Character.class) && text.length() == 1) {
            return text.charAt(0);
        }
        if (target.isEnum()) {
            Object constant = constant(target, text);
            return constant == null ? NO : constant;
        }
        return NO;
    }

    private static Object constant(Class<?> target, String name) {
        for (Object constant : target.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    /**
     * Функция скрипта как интерфейс с одним методом.
     * <p>
     * Без контекста — отказ, а не тихое {@code null}: контекста нет только там,
     * куда обработчик передать и правда нечем, и молчание об этом кончилось бы
     * непонятным «не подошёл ни один метод».
     */
    private Object proxy(FunctionValue function, Class<?> target, CallContext context) {
        if (JavaProxy.sam(target) == null) {
            return target == Object.class ? function : NO;
        }
        return context == null ? NO : JavaProxy.of(target, function, this, context, Span.NONE);
    }

    private Object list(ArrayValue items, Class<?> target, String subject,
                        CallContext context, Span span) {
        if (target.isArray()) {
            Class<?> element = target.getComponentType();
            Object result = Array.newInstance(element, items.size());
            for (int i = 0; i < items.size(); i++) {
                Object item = convert(items.get(i), element, subject, context, span);
                if (item == NO) {
                    return NO;
                }
                Array.set(result, i, item);
            }
            return result;
        }
        if (target == Set.class || target == LinkedHashSet.class) {
            return new LinkedHashSet<>(elements(items, subject, context, span));
        }
        if (target == List.class || target == Collection.class || target == Iterable.class
                || target == ArrayList.class || target == Object.class) {
            return elements(items, subject, context, span);
        }
        return NO;
    }

    private List<Object> elements(ArrayValue items, String subject,
                                  CallContext context, Span span) {
        List<Object> result = new ArrayList<>(items.size());
        for (Value item : items.items()) {
            result.add(toJava(item, Object.class, subject, context, span));
        }
        return result;
    }

    private Object instance(InstanceObjectValue instance, Class<?> target,
                            String subject, CallContext context, Span span) {
        Object state = state(instance);
        if (state != null && target.isInstance(state)) {
            return state;
        }
        return Map.class.isAssignableFrom(target) || target == Object.class
                ? toMap(instance, subject, context, span) : NO;
    }

    private Map<Object, Object> toMap(MapValue entries, String subject,
                                      CallContext context, Span span) {
        Map<Object, Object> result = new LinkedHashMap<>();
        entries.entries().forEach((key, value) -> result.put(
                toJava(key, Object.class, subject, context, span),
                toJava(value, Object.class, subject, context, span)));
        return result;
    }

    /**
     * Ожидание словами — <b>фразой целиком</b>, а не именем типа.
     * <p>
     * Потому что род у типов разный: «ожидалась строка», но «ожидался массив»
     * и «ожидалось число». Склеивать «ожидалось» + имя, как делалось сначала,
     * значит писать «ожидалось массив» — и это ровно тот сорт мелочи, по которому
     * видно, что сообщение писала машина. Формы те же, что у {@code runtime.Args},
     * чтобы отказ моста и отказ встроенной функции звучали одинаково.
     */
    private static final Map<Class<?>, String> LANGUAGE_TYPES = Map.of(
            ArrayValue.class, "ожидался массив",
            MapValue.class, "ожидался объект",
            InstanceObjectValue.class, "ожидался объект",
            StringValue.class, "ожидалась строка",
            IntValue.class, "ожидалось целое число",
            FloatValue.class, "ожидалось дробное число",
            BoolValue.class, "ожидалось логическое",
            Value.class, "ожидалось значение");

    /** Чего здесь ждали — фразой, годной для {@link Args#because}. */
    static String expected(Class<?> target) {
        String language = LANGUAGE_TYPES.get(target);
        if (language != null) {
            return language;
        }
        if (Value.class.isAssignableFrom(target)) {
            return "ожидалось значение типа " + target.getSimpleName();
        }
        if (target == String.class || target == CharSequence.class) {
            return "ожидалась строка";
        }
        if (target == boolean.class || target == Boolean.class) {
            return "ожидалось логическое";
        }
        if (target.isPrimitive() || Number.class.isAssignableFrom(target)) {
            return "ожидалось число (" + target.getSimpleName() + ")";
        }
        if (target.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object constant : target.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            return "ожидалось одно из: " + String.join(", ", names);
        }
        if (target.isArray() || Collection.class.isAssignableFrom(target)) {
            return "ожидался массив";
        }
        if (Map.class.isAssignableFrom(target)) {
            return "ожидался объект";
        }
        return "ожидалось значение типа " + target.getSimpleName();
    }
}
