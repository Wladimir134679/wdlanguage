package ru.wds.wdl.ast.op;

import java.util.EnumSet;
import java.util.Set;

/**
 * Бинарная операция.
 * <p>
 * Все двухместные операции — один вид узла {@link BinaryExpr} и одно перечисление,
 * включая {@code &&} и {@code ||}. В прошлой реализации арифметика и сравнения жили
 * в разных классах узлов, и каждый обход дерева приходилось писать дважды.
 * <p>
 * Ленивость {@code &&} и {@code ||} — свойство самой операции, а не отдельного вида
 * узла, поэтому она отмечена флагом {@link #isShortCircuit()}: интерпретатор по нему
 * решает, вычислять ли правую часть, а оптимизатор — можно ли переставлять операнды.
 * <p>
 * Здесь же лежит и {@linkplain #member() имя члена}, которым операция перегружается.
 * Оно не всегда совпадает с написанием: четыре сравнения закрывает один
 * {@code `<=>`}, {@code !=} — тот же член, что {@code ==}, а все четыре записи
 * принадлежности — один {@code `in`}. Так и должно быть: разойтись между собой они
 * не могут, потому что члена всего один. Полный список имён и то, что ими нельзя
 * назвать, — в {@link Overloads}.
 */
public enum BinaryOp {

    ADD("+", "+"),
    SUBTRACT("-", "-"),
    MULTIPLY("*", "*"),
    DIVIDE("/", "/"),
    REMAINDER("%", "%"),

    EQUAL("==", "=="),
    NOT_EQUAL("!=", "=="),
    LESS("<", Overloads.ORDER),
    LESS_EQUAL("<=", Overloads.ORDER),
    GREATER(">", Overloads.ORDER),
    GREATER_EQUAL(">=", Overloads.ORDER),

    /**
     * Проверка класса или трейта: {@code figure is Circle}.
     * <p>
     * Обычная бинарная операция, а не спецформа: справа стоит выражение, и оно
     * вычисляется. Ограничение «класс или трейт» проверяется по значению, а не
     * по виду узла, — иначе {@code kinds[0]} справа пришлось бы запрещать.
     */
    IS("is", null),

    /**
     * Отрицание {@link #IS}: {@code figure !is Circle}.
     * <p>
     * Отдельная операция, а не {@code UnaryExpr} вокруг {@link #IS}. Прецедент
     * в языке уже стоит: {@code !=} — это {@link #NOT_EQUAL}, а не отрицание
     * {@link #EQUAL}. Дерево честно отражает текст, форматтер получает форму даром,
     * а {@code !x is T} (отрицание прилипло к левому операнду) перестаёт быть
     * единственной записью.
     */
    NOT_IS("!is", null),

    /**
     * Принадлежность: {@code role in user.roles}.
     * <p>
     * Работает для массива (по значению), строки (подстрока), объекта (ключ)
     * и диапазона (границы включительно). Реализация одна на все формы записи —
     * {@code Operations.contains}; туда же ходят члены {@code a.contains(x)}
     * и {@code obj.has(k)}, иначе оператор и метод однажды разошлись бы.
     */
    IN("in", "in"),
    /** Отрицание {@link #IN}: {@code text !in banned}. */
    NOT_IN("!in", "in"),
    /** Принадлежность, записанная от контейнера: {@code a has x} ≡ {@code x in a}. */
    HAS("has", "in"),
    /** Отрицание {@link #HAS}: {@code banned !has text}. */
    NOT_HAS("!has", "in"),

    AND("&&", null, true),
    OR("||", null, true),

    /**
     * Диапазон: {@code 1..5}.
     * <p>
     * Обычная бинарная операция: обе границы — выражения, и они вычисляются.
     * Сила связывания выбрана между сравнениями и сдвигами, чтобы {@code x in 1..5}
     * читалось как принадлежность диапазону, а {@code 0..n - 1} — как «до n минус один»;
     * см. {@code parser.Operators}.
     */
    RANGE("..", ".."),

    BIT_AND("&", "&"),
    BIT_OR("|", "|"),
    BIT_XOR("^", "^"),
    SHIFT_LEFT("<<", "<<"),
    SHIFT_RIGHT(">>", ">>"),
    SHIFT_RIGHT_UNSIGNED(">>>", ">>>");

    /**
     * Операции, у которых ответ члена — ещё не ответ выражения.
     * <p>
     * {@code `<=>`} отвечает числом, которое надо сравнить с нулём; {@code `==`} —
     * признаком, приводимым к логическому; {@code `in`} кормит ещё и три отрицающих
     * записи. Арифметика, биты и диапазон отдают ответ как есть.
     */
    private static final Set<BinaryOp> RELATIONS = EnumSet.of(EQUAL, NOT_EQUAL,
            LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, IN, NOT_IN, HAS, NOT_HAS);

    private final String symbol;
    private final String member;
    private final boolean shortCircuit;

    BinaryOp(String symbol, String member) {
        this(symbol, member, false);
    }

    BinaryOp(String symbol, String member, boolean shortCircuit) {
        this.symbol = symbol;
        this.member = member;
        this.shortCircuit = shortCircuit;
    }

    public String symbol() {
        return symbol;
    }

    /**
     * Имя члена, которым операция перегружается, или {@code null}, если она
     * не перегружается вовсе.
     * <p>
     * Одно имя нередко закрывает несколько операций — см. описание перечисления.
     * Спрашивается это имя <b>только тогда, когда ядро не справилось</b>: правило
     * запасного пути записано в {@code runtime.Operations} и здесь не повторяется.
     */
    public String member() {
        return member;
    }

    /** Есть ли у операции член, которым её можно перегрузить. */
    public boolean overloadable() {
        return member != null;
    }

    /**
     * Становится ли ответ члена ответом всего выражения без переработки.
     * <p>
     * У арифметики, битов и диапазона — да: что вернул {@code `+`}, то и значение
     * выражения. У сравнений и принадлежности — нет, и разница здесь не в удобстве:
     * их член отвечает не значением, а отношением, которое ещё надо истолковать.
     */
    public boolean memberIsResult() {
        return overloadable() && !RELATIONS.contains(this);
    }

    /** Правый операнд вычисляется не всегда. */
    public boolean isShortCircuit() {
        return shortCircuit;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
