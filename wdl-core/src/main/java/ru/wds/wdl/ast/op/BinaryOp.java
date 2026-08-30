package ru.wds.wdl.ast.op;

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
 */
public enum BinaryOp {

    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/"),
    REMAINDER("%"),

    EQUAL("=="),
    NOT_EQUAL("!="),
    LESS("<"),
    LESS_EQUAL("<="),
    GREATER(">"),
    GREATER_EQUAL(">="),

    /**
     * Проверка класса или трейта: {@code figure is Circle}.
     * <p>
     * Обычная бинарная операция, а не спецформа: справа стоит выражение, и оно
     * вычисляется. Ограничение «класс или трейт» проверяется по значению, а не
     * по виду узла, — иначе {@code kinds[0]} справа пришлось бы запрещать.
     */
    IS("is"),

    /**
     * Отрицание {@link #IS}: {@code figure !is Circle}.
     * <p>
     * Отдельная операция, а не {@code UnaryExpr} вокруг {@link #IS}. Прецедент
     * в языке уже стоит: {@code !=} — это {@link #NOT_EQUAL}, а не отрицание
     * {@link #EQUAL}. Дерево честно отражает текст, форматтер получает форму даром,
     * а {@code !x is T} (отрицание прилипло к левому операнду) перестаёт быть
     * единственной записью.
     */
    NOT_IS("!is"),

    /**
     * Принадлежность: {@code role in user.roles}.
     * <p>
     * Работает для массива (по значению), строки (подстрока), объекта (ключ)
     * и диапазона (границы включительно). Реализация одна на все формы записи —
     * {@code Operations.contains}; туда же ходят члены {@code a.contains(x)}
     * и {@code obj.has(k)}, иначе оператор и метод однажды разошлись бы.
     */
    IN("in"),
    /** Отрицание {@link #IN}: {@code text !in banned}. */
    NOT_IN("!in"),
    /** Принадлежность, записанная от контейнера: {@code a has x} ≡ {@code x in a}. */
    HAS("has"),
    /** Отрицание {@link #HAS}: {@code banned !has text}. */
    NOT_HAS("!has"),

    AND("&&", true),
    OR("||", true),

    /**
     * Диапазон: {@code 1..5}.
     * <p>
     * Обычная бинарная операция: обе границы — выражения, и они вычисляются.
     * Сила связывания выбрана между сравнениями и сдвигами, чтобы {@code x in 1..5}
     * читалось как принадлежность диапазону, а {@code 0..n - 1} — как «до n минус один»;
     * см. {@code parser.Operators}.
     */
    RANGE(".."),

    BIT_AND("&"),
    BIT_OR("|"),
    BIT_XOR("^"),
    SHIFT_LEFT("<<"),
    SHIFT_RIGHT(">>"),
    SHIFT_RIGHT_UNSIGNED(">>>");

    private final String symbol;
    private final boolean shortCircuit;

    BinaryOp(String symbol) {
        this(symbol, false);
    }

    BinaryOp(String symbol, boolean shortCircuit) {
        this.symbol = symbol;
        this.shortCircuit = shortCircuit;
    }

    public String symbol() {
        return symbol;
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
