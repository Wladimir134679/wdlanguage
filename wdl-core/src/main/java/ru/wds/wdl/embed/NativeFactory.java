package ru.wds.wdl.embed;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;

/**
 * Тело фабрики нативного класса: {@code File.temp()}.
 * <p>
 * Первым аргументом приходит <b>сам класс</b> — тот, в котором фабрика объявлена.
 * Без этого фабрике было бы нечем создать экземпляр: класс собирается на запуск,
 * значит статического поля с ним нет и быть не должно, а ссылаться на переменную,
 * которой ещё не присвоено значение, лямбда не может. Поэтому фабрики материализуются
 * в {@code build()}, когда класс уже есть.
 * <pre>{@code
 * .factory("temp", Arity.between(0, 1), (type, context, args, span) ->
 *         type.instantiate(List.of(StringValue.of(path)), context, span))
 * }</pre>
 * Всё остальное — как у встроенной функции: контекст, проверенные по {@code Arity}
 * аргументы и место вызова в исходнике.
 */
@FunctionalInterface
public interface NativeFactory {

    Value call(NativeClass type, CallContext context, Args arguments, Span span);
}
