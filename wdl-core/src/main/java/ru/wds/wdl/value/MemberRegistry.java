package ru.wds.wdl.value;

import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.ValueType;

/**
 * Куда приложение добавляет члены значениям: {@code "текст".slug}, {@code point.polar}.
 * <p>
 * Достаётся из области видимости, которую {@link Library#installTo} и так получает
 * ({@code scope.members()}), — то есть оттуда же, откуда библиотека уже кладёт имена
 * и классы. Второго метода у {@code Library} для этого заводить не пришлось: его
 * пришлось бы звать всем, кто заводит запуск, и забыть его было бы легче, чем
 * не забыть.
 * <p>
 * <b>Принадлежит запуску.</b> Набор, добавленный здесь, виден только этому
 * интерпретатору: два движка в одном процессе не делят расширения. Поэтому и звать
 * это надо из {@code installTo}, а не из статического поля, — ровно как
 * {@code bridge.NativeClass}.
 * <p>
 * <b>Перекрыть встроенное нельзя.</b> Попытка — {@link IllegalArgumentException}
 * при установке, а не тихая победа последнего: член ядра это договорённость всего
 * запуска, и молча подменять её у библиотеки нет права.
 *
 * <pre>{@code
 * public Environment installTo(Environment scope) {
 *     MemberRegistry members = scope.members();
 *     if (members != null) {
 *         members.install(ValueType.STRING, MemberSet.builder()
 *                 .property("slug", (receiver, context, span) -> ...)
 *                 .build());
 *     }
 *     return scope;
 * }
 * }</pre>
 */
public interface MemberRegistry {

    /** Добавляет набор всем значениям этого типа. */
    void install(ValueType type, MemberSet members);

    /**
     * Добавляет набор экземплярам этого класса — и его потомков.
     * <p>
     * Ключ — само значение класса, а не имя: два одноимённых класса из разных
     * модулей расширяются независимо.
     */
    void install(ClassValue owner, MemberSet members);
}
