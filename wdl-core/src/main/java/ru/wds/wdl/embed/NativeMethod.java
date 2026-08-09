package ru.wds.wdl.embed;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;

import java.util.List;

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
 */
@FunctionalInterface
public interface NativeMethod {

    Value call(NativeInstance self, CallContext context, List<Value> arguments, Span span);
}
