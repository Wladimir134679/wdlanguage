package ru.wds.wdl.embed;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;

import java.util.List;
import java.util.Objects;

/**
 * Функция скрипта, готовая к вызову: значение плюс то, что нужно, чтобы его позвать.
 * <p>
 * Библиотеке почти всегда нужен не «объект-обработчик с методами», а одно: <b>вот
 * функция, позови её</b>. Голая {@link FunctionValue} этого не даёт — у неё в подписи
 * стоят {@link CallContext} и {@link Span}, которые к делу библиотеки не относятся
 * и в каждом теле переписывались бы руками. Здесь они подставлены один раз.
 * <pre>{@code
 * .method("onEach", Arity.exactly(1), (self, context, args, span) -> {
 *     Callback handler = args.callback(0, "обработчик");
 *     for (Value item : items) {
 *         handler.call(item);
 *     }
 *     return NullValue.NULL;
 * })
 * }</pre>
 *
 * <h2>Откуда функция пришла — не спрашивается</h2>
 * Из главного файла, из метода класса, из модуля, из замыкания на пять уровней
 * вложенности — библиотека этого не знает и знать не должна. Функция несёт свой
 * контекст с собой: область объявления, файл и запуск (см. {@code runtime.Run}).
 * Поэтому здесь нет ни «а из какого она класса», ни «а в каком она скоупе» — есть
 * {@link #call}, и этого достаточно.
 *
 * <h2>Живёт столько же, сколько вызов</h2>
 * {@link CallContext} здесь — контекст того вызова, в котором обработчик получен,
 * и нужен он ровно для одного: чтобы {@code println} внутри обработчика напечатал
 * туда же, куда печатает остальной скрипт. Держать {@code Callback} дольше вызова
 * незачем — для функции, которая уезжает в приложение и живёт своей жизнью, есть
 * {@code WdlCallable} в {@code wdl-api}: он привязан к запуску, а не к вызову.
 */
public final class Callback {

    private final FunctionValue function;
    private final CallContext context;
    private final Span span;

    private Callback(FunctionValue function, CallContext context, Span span) {
        this.function = Objects.requireNonNull(function, "function");
        this.context = Objects.requireNonNull(context, "context");
        this.span = Objects.requireNonNull(span, "span");
    }

    /**
     * Обёртка над значением-функцией.
     *
     * @param context контекст вызова, в котором функция получена, — вывод берётся оттуда
     * @param span    место вызова в исходнике: туда укажет ошибка внутри обработчика
     */
    public static Callback of(FunctionValue function, CallContext context, Span span) {
        return new Callback(function, context, span);
    }

    /** Имя для сообщений; у анонимной функции — {@code "def"}. */
    public String name() {
        return function.name();
    }

    /** Сколько аргументов функция принимает. Спрашивать до вызова — дело вызывающего. */
    public Arity arity() {
        return function.arity();
    }

    /** Значение-функция как есть — если библиотеке нужно передать её дальше. */
    public FunctionValue value() {
        return function;
    }

    /**
     * Зовёт функцию.
     * <p>
     * Число аргументов не проверяется: то же самое сделает сама функция, и сообщение
     * у неё уже правильное — с именем и с тем, сколько она принимает. Дублировать
     * проверку значило бы иметь два текста одной ошибки.
     */
    public Value call(Value... arguments) {
        return call(List.of(arguments));
    }

    /** То же самое, когда аргументы уже собраны списком. */
    public Value call(List<Value> arguments) {
        return function.call(context, arguments, span);
    }

    @Override
    public String toString() {
        return "Callback[" + function.display() + "]";
    }
}
