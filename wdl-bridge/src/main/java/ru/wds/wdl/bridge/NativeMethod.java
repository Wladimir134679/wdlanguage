package ru.wds.wdl.bridge;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;

/**
 * Тело метода нативного класса — Java-лямбда вместо дерева.
 * <p>
 * Первым аргументом приходит сам объект: у него есть и поля языка
 * ({@code self.get("path")}), и {@link NativeInstance#state() состояние},
 * которое значениями не выражается. Остальное — как у встроенной функции:
 * контекст для вывода, готовые аргументы и место вызова в исходнике для ошибок.
 * <p>
 * Число аргументов проверено до входа сюда, по {@code Arity}, объявленной
 * при регистрации метода, — поэтому тело начинается с дела, а не с проверок.
 * Тип аргумента при этом остаётся на теле, и спрашивают о нём у {@link Args}:
 * {@code args.string(0, "содержимое")}.
 */
@FunctionalInterface
public interface NativeMethod {

    Value call(NativeInstance self, CallContext context, Args arguments, Span span);
}
