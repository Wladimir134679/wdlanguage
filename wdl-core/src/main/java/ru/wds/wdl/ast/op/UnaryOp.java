package ru.wds.wdl.ast.op;

/**
 * Унарная операция.
 * <p>
 * Здесь только смысл операции и её написание. Приоритет — свойство синтаксиса,
 * а не дерева, и живёт в таблице парсера: дерево уже разобрано, спорить о порядке
 * применения в нём не о чем.
 */
public enum UnaryOp {

    /** {@code -x} — смена знака. */
    NEGATE("-"),
    /** {@code +x} — ничего не меняет, но требует числа: опечатку видно сразу. */
    PLUS("+"),
    /** {@code !x} — логическое отрицание, результат всегда логический. */
    NOT("!"),
    /** {@code ~x} — побитовое дополнение, только для целых. */
    COMPLEMENT("~");

    private final String symbol;

    UnaryOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
