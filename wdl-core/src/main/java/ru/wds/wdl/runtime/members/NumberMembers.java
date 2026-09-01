package ru.wds.wdl.runtime.members;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Члены числа.
 * <p>
 * <b>Лексер к обращению у числового литерала готов, и это не совпадение:</b> дробная
 * часть требует цифру <i>после</i> точки, поэтому {@code 213.toString()} разбирается
 * как обращение, а не как сломанный литерал. Править ради членов ничего не пришлось —
 * и {@code LexerTest} это фиксирует, чтобы никто не «починил» лексер обратно.
 * <p>
 * Границу здесь видно лучше всего: {@code abs} и {@code sign} — существительные,
 * ответ о самом числе, и число неизменяемо, так что устареть он не может; а
 * {@code round(2)} принимает аргумент, {@code toString()} назван глаголом — оба
 * методы. Целое и дробное различает не тип языка ({@code typeof} у обоих
 * {@code number}), а вопрос {@code n.integer}.
 */
public final class NumberMembers {

    private NumberMembers() {
    }

    static MemberSet set() {
        return MemberSet.builder()
                .property("abs", (receiver, context, span) -> {
                    NumberValue number = self(receiver);
                    return number.isInteger()
                            ? IntValue.of(Math.abs(number.asLong()))
                            : FloatValue.of(Math.abs(number.asDouble()));
                })
                .property("sign", (receiver, context, span) -> {
                    double value = self(receiver).asDouble();
                    return IntValue.of(value > 0 ? 1 : value < 0 ? -1 : 0);
                })
                .property("floor", (receiver, context, span) -> whole(self(receiver), Math.floor(self(receiver).asDouble())))
                .property("ceil", (receiver, context, span) -> whole(self(receiver), Math.ceil(self(receiver).asDouble())))
                .property("integer", (receiver, context, span) -> BoolValue.of(self(receiver).isInteger()))
                .method("round", Arity.between(0, 1), (receiver, context, arguments, span) -> {
                    Args args = args("round", arguments, context, span);
                    NumberValue number = self(receiver);
                    long digits = args.has(0) ? args.integer(0, "знаков после точки") : 0;
                    if (digits <= 0) {
                        return IntValue.of(Math.round(number.asDouble()));
                    }
                    if (number.isInteger()) {
                        return number;
                    }
                    return FloatValue.of(BigDecimal.valueOf(number.asDouble())
                            .setScale((int) Math.min(digits, 15), RoundingMode.HALF_UP)
                            .doubleValue());
                })
                .method("clamp", Arity.exactly(2), (receiver, context, arguments, span) -> {
                    Args args = args("clamp", arguments, context, span);
                    double low = args.real(0, "нижняя граница");
                    double high = args.real(1, "верхняя граница");
                    if (low > high) {
                        throw new WdlRuntimeError(ErrorKind.VALUE, span,
                                "clamp(): нижняя граница " + low + " больше верхней " + high);
                    }
                    NumberValue number = self(receiver);
                    double value = Math.max(low, Math.min(number.asDouble(), high));
                    return number.isInteger() && value == Math.rint(value)
                            ? IntValue.of((long) value)
                            : FloatValue.of(value);
                })
                .method("toString", Arity.exactly(0), (receiver, context, arguments, span) ->
                        StringValue.of(self(receiver).display()))
                .build();
    }

    /**
     * Округление вниз и вверх даёт целое, даже если пришло дробное: {@code 2.7.floor}
     * — это {@code 2}, а не {@code 2.0}. Бесконечность целым не бывает, и подменять
     * её огромным числом хуже, чем оставить как есть.
     */
    private static Value whole(NumberValue source, double rounded) {
        if (source.isInteger()) {
            return source;
        }
        return Double.isFinite(rounded) ? IntValue.of((long) rounded) : FloatValue.of(rounded);
    }

    private static NumberValue self(Value receiver) {
        return (NumberValue) receiver;
    }

    private static Args args(String name, List<Value> arguments, CallContext context, Span span) {
        return Args.of("number." + name, arguments, context, span);
    }
}
