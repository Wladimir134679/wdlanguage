package ru.wds.wdl.bridge;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;

/**
 * Запись свойства нативного класса.
 * <p>
 * Значение приходит как есть, непроверенным: тип остаётся на теле — ровно как
 * у метода, где о нём спрашивают {@link Args}. Здесь для этого есть
 * {@link Args#because}: {@code throw new WdlRuntimeError(TYPE, span,
 * Args.because("Window.title", "ожидалась строка", value))}.
 * <p>
 * Свойства без сеттера — обычное дело и не ошибка: класс объявляет только чтение,
 * и попытка записи даёт «только для чтения» тем же сообщением, что у свойства
 * на wdl. Проверку делает интерпретатор до входа сюда, чтобы сообщение было одним
 * на оба вида классов.
 */
@FunctionalInterface
public interface NativeSetter {

    void set(NativeInstance self, Value value, CallContext context, Span span);
}
