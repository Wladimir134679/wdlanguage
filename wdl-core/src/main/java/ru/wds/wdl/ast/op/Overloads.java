package ru.wds.wdl.ast.op;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Что можно написать в обратных кавычках у члена типа: {@code def `+`(right)}.
 * <p>
 * <b>Здесь, а не в {@code parser.Operators}.</b> Список нужен обеим сторонам —
 * разбору, чтобы отвергнуть {@code `=<`} на месте, и выполнению, чтобы спросить член
 * с правильным именем, — а зависимости {@code runtime → parser} в проекте нет
 * и заводить её нельзя. Сам {@code parser.Operators} при этом не меняется вовсе:
 * перегружается смысл существующего оператора, а нового символа язык не получает.
 * <p>
 * <b>Имён меньше, чем операторов.</b> Четыре сравнения закрывает один {@link #ORDER},
 * {@code !=} — тот же член, что {@code ==}, четыре записи принадлежности — один
 * {@code `in`}. Поэтому таблица знает не только «что можно», но и «почему нельзя»:
 * промах по имени почти всегда значит, что человек написал производную запись
 * ({@code `!=`}, {@code `<`}, {@code `has`}) или оператор, который не перегружается
 * в принципе ({@code `&&`}, {@code `is`}), — и об этом надо сказать причиной,
 * а не общим «такого оператора нет».
 * <p>
 * <b>Зеркало ключуется мангленным именем</b> ({@link #mirrorName(String)}). Написать
 * такое имя нельзя: символ {@code @} не входит ни в один оператор, и таблица его
 * отвергнет. Отсюда и берётся то, что зеркало живёт в обычной таблице методов
 * рядом с прямым оператором и не требует ни второй таблицы, ни правки поиска.
 */
public final class Overloads {

    /** Имя члена, закрывающего {@code <}, {@code <=}, {@code >}, {@code >=} и сортировку. */
    public static final String ORDER = "<=>";

    /**
     * Чем имя зеркала отличается от прямого.
     * <p>
     * Символ выбран так, чтобы столкнуться было не с чем: {@code @} не начинает
     * ни один оператор языка, поэтому мангленное имя не написать ни в кавычках,
     * ни без них.
     */
    private static final String MIRROR_MARK = "@";

    /**
     * Чем имя унарного члена отличается от бинарного.
     * <p>
     * Скобки в ключе читаются как «без аргументов» и написаны быть не могут:
     * имя члена — это то, что стоит между кавычками, а скобки идут после них.
     */
    private static final String UNARY_MARK = "()";

    private static final Set<String> BINARY;
    private static final Set<String> UNARY;
    private static final Map<String, String> DERIVED;
    private static final Map<String, String> FORBIDDEN;

    static {
        Set<String> binary = new LinkedHashSet<>();
        for (BinaryOp op : BinaryOp.values()) {
            if (op.overloadable()) {
                binary.add(op.member());
            }
        }
        Set<String> unary = new LinkedHashSet<>();
        for (UnaryOp op : UnaryOp.values()) {
            if (op.overloadable()) {
                unary.add(op.member());
            }
        }
        BINARY = Set.copyOf(binary);
        UNARY = Set.copyOf(unary);

        Map<String, String> derived = new LinkedHashMap<>();
        derived.put("!=", "он выводится отрицанием '=='");
        derived.put("!in", "он выводится отрицанием 'in'");
        derived.put("!has", "он выводится отрицанием 'in'");
        derived.put("!is", "он выводится отрицанием 'is'");
        derived.put("has", "'x in c' и 'c has x' — одна и та же запись, "
                + "и член у неё один: '" + BinaryOp.IN.member() + "'");
        for (BinaryOp op : new BinaryOp[]{BinaryOp.LESS, BinaryOp.LESS_EQUAL,
                BinaryOp.GREATER, BinaryOp.GREATER_EQUAL}) {
            derived.put(op.symbol(), "четыре сравнения закрывает один член '" + ORDER
                    + "', он отвечает числом");
        }
        DERIVED = Map.copyOf(derived);

        Map<String, String> forbidden = new LinkedHashMap<>();
        forbidden.put("&&", "он вычисляет правый операнд не всегда, "
                + "и перегрузка отняла бы у него ровно это");
        forbidden.put("||", "он вычисляет правый операнд не всегда, "
                + "и перегрузка отняла бы у него ровно это");
        forbidden.put("!", "он спрашивает достоверность, а она есть у любого значения: "
                + "ядро на нём не падает никогда, и запасному пути нечего ловить");
        forbidden.put("is", "вопрос принадлежит правому операнду, и отвечает на него сам класс");
        for (AssignOp op : AssignOp.values()) {
            forbidden.put(op.symbol(), "присваивание — это инструкция, а не выражение; "
                    + "составное 'p += q' и так зовёт член '+'");
        }
        FORBIDDEN = Map.copyOf(forbidden);
    }

    private Overloads() {
    }

    /** Бывает ли член с таким именем и одним параметром: {@code def `+`(right)}. */
    public static boolean isBinary(String name) {
        return BINARY.contains(name);
    }

    /** Бывает ли член с таким именем без параметров: {@code def `-`()}. */
    public static boolean isUnary(String name) {
        return UNARY.contains(name);
    }

    /**
     * Бывает ли у этого оператора зеркало.
     * <p>
     * У {@code `in`} — нет, и это не пропуск: обе стороны отношения уже есть
     * в грамматике ({@code x in c} и {@code c has x}), поэтому второй способ написать
     * то же самое породил бы два разных ответа на один вопрос. Член объявляется
     * на контейнере, а какой стороной записано выражение — его не касается.
     */
    public static boolean allowsMirror(String name) {
        return isBinary(name) && !BinaryOp.IN.member().equals(name);
    }

    /**
     * Имя, под которым зеркало лежит в таблице методов.
     * <p>
     * Манглинг именно здесь, одной функцией на весь проект: имя в дереве остаётся
     * честным ({@code `+`} — это {@code "+"}), зеркальность несёт
     * {@code Modifier.MIRROR}, а расходиться двум спискам правил не с чем.
     */
    public static String mirrorName(String name) {
        return name + MIRROR_MARK;
    }

    /**
     * Имя, под которым унарный оператор лежит в таблице методов.
     * <p>
     * Унарный и бинарный различаются арностью, а таблица методов ключуется строкой —
     * значит арность обязана попасть в ключ. Иначе {@code def `-`(right)} и
     * {@code def `-`()} у одного класса делили бы ячейку, и объявить оба сразу было бы
     * нельзя, хотя это ровно то, что делает всякий класс-вектор.
     */
    public static String unaryName(String name) {
        return name + UNARY_MARK;
    }

    /**
     * Ключ члена в таблице методов: одна функция на весь проект.
     * <p>
     * Форма класса, форма трейта, требование трейта и член, добавленный
     * {@code extend}, кладут имена в разные таблицы, но по одному правилу — иначе
     * зеркало, объявленное в трейте, не нашлось бы у класса, который его подмешал.
     *
     * @param mirror  объявлен ли член со словом {@code mirror}
     * @param noParams нет ли у него параметров: для оператора это и значит «унарный»
     */
    public static String key(String name, boolean mirror, boolean noParams) {
        if (mirror) {
            return mirrorName(name);
        }
        return noParams && isUnary(name) ? unaryName(name) : name;
    }

    /**
     * Почему этот оператор не объявляют отдельно, — или {@code null}, если объявляют.
     * <p>
     * Ответ есть у производных записей: отрицаний ({@code !=}, {@code !in}),
     * второй стороны принадлежности ({@code has}) и четырёх сравнений, которые
     * закрывает {@link #ORDER}. Объяви их по отдельности — и однажды {@code a == b}
     * и {@code a != b} окажутся истинны одновременно.
     */
    public static String derived(String name) {
        return DERIVED.get(name);
    }

    /**
     * Почему этот оператор не перегружается вовсе, — или {@code null}, если
     * перегружается.
     */
    public static String forbidden(String name) {
        return FORBIDDEN.get(name);
    }

    /** Все имена бинарных членов — для сообщения «а бывают вот такие». */
    public static Set<String> binaryNames() {
        return BINARY;
    }

    /** Все имена унарных членов. */
    public static Set<String> unaryNames() {
        return UNARY;
    }
}
