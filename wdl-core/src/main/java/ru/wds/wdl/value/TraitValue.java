package ru.wds.wdl.value;

import ru.wds.wdl.value.types.InstanceObjectValue;

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

    /** Имена методов, объявленных в трейте с телом. Заведено ради интроспекции. */
    default List<String> methodNames() {
        return List.of();
    }

    /** Имена свойств, объявленных в трейте с телом. */
    default List<String> propertyNames() {
        return List.of();
    }

    /**
     * Методы, которые класс обязан объявить сам, — с числом аргументов.
     * <p>
     * Нужно ровно там, где требования проверяет не {@code Linker}: класс, встроенный
     * приложением, формы не имеет, а обещание трейта выполнить обязан — и проверить
     * это надо при сборке класса, то есть при старте приложения, а не при первом
     * вызове из скрипта.
     */
    default List<Requirement> requiredMethods() {
        return List.of();
    }

    /**
     * Поля, которые класс обязан объявить сам.
     * <p>
     * Отдельно от методов, потому что закрывают требование они по-разному: поле
     * закрывает только поле, метод — только метод. У поля нечего проверять на число
     * аргументов, а перекрытие метода полем — другая история и работа линтера.
     */
    default List<String> requiredFields() {
        return List.of();
    }

    /**
     * Имена, которые класс обязан уметь читать или писать.
     * <p>
     * Требование через <b>возможность</b>, а не через способ хранения: его закрывает
     * и обычное поле, и вычисляемое свойство. Отсюда и вся польза — поле можно
     * заменить свойством, не сломав контракт. См. {@link PropertyRequirement}.
     */
    default List<PropertyRequirement> requiredProperties() {
        return List.of();
    }

    /**
     * Отвечает на {@code значение is этот}: то же самое, что {@link ClassValue#matches}
     * и по той же причине там описано — правый операнд {@code is} лучше всех знает,
     * как ответить на вопрос о себе.
     * <p>
     * Строка дублирует {@code ClassValue.matches} дословно, а не наследуется от общего
     * места: общий надтип для {@code ClassValue} и {@code TraitValue} пришлось бы
     * вставлять в {@code sealed}-иерархию {@link Value} ради одного метода в одну
     * строку — цена больше пользы (см. «Отвергнутое» в плане дескрипторов типов).
     */
    default boolean matches(Value value) {
        return value instanceof InstanceObjectValue instance && instance.owner().conformsTo(this);
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
