package ru.wds.wdl.value;

import java.util.List;

/**
 * Трейт как значение.
 * <p>
 * Устроен как класс, у которого забрали конструктор: заголовок перечисляет поля,
 * тело перечисляет методы, и то и другое бывает требованием к классу. Поэтому
 * создать экземпляр трейтом нельзя — {@code new Counted()} ошибка, — а из всего,
 * что умеет {@link ClassValue}, здесь остаётся только имя: трейт участвует
 * в выражении ровно в двух местах, справа от {@code is} и в печати.
 * <p>
 * Реализация — {@code ru.wds.wdl.runtime.WdlTrait}, по той же причине, что
 * и у {@link ClassValue}.
 */
public non-sealed interface TraitValue extends Value {

    String name();

    /**
     * Имена методов, которые класс обязан объявить сам.
     * <p>
     * Нужно ровно там, где требования проверяет не {@code Linker}: класс, встроенный
     * приложением, формы не имеет, а обещание трейта выполнить обязан — и проверить
     * это надо при сборке класса, то есть при старте приложения, а не при первом
     * вызове из скрипта.
     */
    default List<String> requiredMethods() {
        return List.of();
    }

    @Override
    default ValueType type() {
        return ValueType.TRAIT;
    }

    @Override
    default String display() {
        return "trait " + name();
    }
}
