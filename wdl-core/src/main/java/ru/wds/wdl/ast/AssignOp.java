package ru.wds.wdl.ast;

/**
 * Вид присваивания: простое {@code =} или составное {@code +=}, {@code <<=} и прочие.
 * <p>
 * Составное присваивание хранит операцию, которую надо применить к старому значению.
 * Разворачивать {@code a += b} в {@code a = a + b} прямо в парсере нельзя: тогда
 * {@code массив[индекс()] += 1} вычислил бы {@code индекс()} дважды. Место записи
 * вычисляется один раз, а операция берётся отсюда.
 */
public enum AssignOp {

    ASSIGN("=", null),
    ADD("+=", BinaryOp.ADD),
    SUBTRACT("-=", BinaryOp.SUBTRACT),
    MULTIPLY("*=", BinaryOp.MULTIPLY),
    DIVIDE("/=", BinaryOp.DIVIDE),
    REMAINDER("%=", BinaryOp.REMAINDER),
    BIT_AND("&=", BinaryOp.BIT_AND),
    BIT_OR("|=", BinaryOp.BIT_OR),
    BIT_XOR("^=", BinaryOp.BIT_XOR),
    SHIFT_LEFT("<<=", BinaryOp.SHIFT_LEFT),
    SHIFT_RIGHT(">>=", BinaryOp.SHIFT_RIGHT),
    SHIFT_RIGHT_UNSIGNED(">>>=", BinaryOp.SHIFT_RIGHT_UNSIGNED);

    private final String symbol;
    private final BinaryOp base;

    AssignOp(String symbol, BinaryOp base) {
        this.symbol = symbol;
        this.base = base;
    }

    public String symbol() {
        return symbol;
    }

    /** Операция над старым значением или {@code null} для простого присваивания. */
    public BinaryOp base() {
        return base;
    }

    public boolean isCompound() {
        return base != null;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
