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
     * Проверка класса или типажа: {@code figure is Circle}.
     * <p>
     * Обычная бинарная операция, а не спецформа: справа стоит выражение, и оно
     * вычисляется. Ограничение «класс или типаж» проверяется по значению, а не
     * по виду узла, — иначе {@code kinds[0]} справа пришлось бы запрещать.
     */
    IS("is"),

    AND("&&", true),
    OR("||", true),

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
