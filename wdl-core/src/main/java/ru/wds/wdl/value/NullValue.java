package ru.wds.wdl.value;

/**
 * Отсутствие значения. Единственный экземпляр — {@link #NULL}.
 * <p>
 * Отдельный тип, а не {@code null}-ссылка: ссылка обязательно однажды утечёт
 * в интерпретатор и превратится в {@code NullPointerException} где-нибудь в глубине
 * стека, вместо внятного «переменная не определена» с указанием места в скрипте.
 */
public final class NullValue implements Value {

    public static final NullValue NULL = new NullValue();

    private NullValue() {
    }

    @Override
    public ValueType type() {
        return ValueType.NULL;
    }

    @Override
    public boolean isTruthy() {
        return false;
    }

    @Override
    public String display() {
        return "null";
    }

    @Override
    public String toString() {
        return "null";
    }
}
