package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Value;

import java.util.Map;

/**
 * Вычисленные аннотации объявления типа — класса или трейта — вместе с членами.
 * <p>
 * Собираются там, где есть область объявления, то есть в {@code Interpreter}, и уже
 * готовыми значениями отдаются в {@link WdlClass} и {@link WdlTrait}. Иначе значению
 * типа пришлось бы держать интерпретатор и строить контекст выполнения прямо
 * в конструкторе — то есть считать чужие выражения в момент, когда самого типа
 * ещё нет.
 * <p>
 * Ключ у методов — <b>имя члена в таблице</b> ({@code FunctionExpr#memberName()}),
 * а не имя в тексте: у зеркального и унарного оператора они расходятся, и второй
 * список правил манглинга языку не нужен.
 *
 * @param own        аннотации самого типа и его заголовка: заголовок класса — это поля
 * @param methods    аннотации методов и фабрик, объявленных этим типом
 * @param properties аннотации свойств, объявленных этим типом
 */
record TypeAnnotations(DeclaredAnnotations own,
                       Map<String, DeclaredAnnotations> methods,
                       Map<String, Map<Value, Value>> properties) {

    static final TypeAnnotations NONE =
            new TypeAnnotations(DeclaredAnnotations.NONE, Map.of(), Map.of());

    /** Аннотации члена по имени в таблице; у ненаписанных — {@link DeclaredAnnotations#NONE}. */
    DeclaredAnnotations method(String name) {
        return methods.getOrDefault(name, DeclaredAnnotations.NONE);
    }

    /** Аннотации свойства по имени; у ненаписанных — пустая карта. */
    Map<Value, Value> property(String name) {
        return properties.getOrDefault(name, Map.of());
    }
}
