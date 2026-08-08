package ru.wds.wdl.value;

/**
 * Типаж как значение.
 * <p>
 * Устроен как класс, у которого забрали конструктор: заголовок перечисляет поля,
 * тело перечисляет методы, и то и другое бывает требованием к классу. Поэтому
 * создать экземпляр типажом нельзя — {@code new Counted()} ошибка, — а из всего,
 * что умеет {@link ClassValue}, здесь остаётся только имя: типаж участвует
 * в выражении ровно в двух местах, справа от {@code is} и в печати.
 * <p>
 * Реализация — {@code ru.wds.wdl.runtime.WdlTrait}, по той же причине, что
 * и у {@link ClassValue}.
 */
public non-sealed interface TraitValue extends Value {

    String name();

    @Override
    default ValueType type() {
        return ValueType.TRAIT;
    }

    @Override
    default String display() {
        return "trait " + name();
    }
}
