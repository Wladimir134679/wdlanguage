package ru.wds.wdl.runtime.members;

import ru.wds.wdl.embed.Args;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Члены строки.
 * <p>
 * <b>Строка неизменяема, поэтому здесь нет ни одного члена, меняющего получателя.</b>
 * {@code upper} и {@code trimmed} — свойства-снимки: новая строка каждый раз, а старая
 * как была. Глагольных пар вида {@code sort()} у строки поэтому не бывает вовсе.
 * <p>
 * <b>Единственное место, где приходится назвать разницу вслух, — единицы измерения.</b>
 * {@code size} и обращение по индексу считают <b>кодовые единицы UTF-16</b>: так
 * устроено обращение сегодня, это {@code O(1)}, и делать индексацию линейной ради
 * эмодзи неправильная сделка. А {@code chars} идёт по <b>кодовым точкам</b>
 * и суррогатную пару не рвёт. Два разных вопроса — два разных ответа, а не один
 * «правильный».
 */
public final class StringMembers {

    private StringMembers() {
    }

    public static MemberSet set() {
        return MemberSet.builder()
                .property("size", (receiver, context, span) -> IntValue.of(self(receiver).length()))
                .property("empty", (receiver, context, span) -> BoolValue.of(text(receiver).isEmpty()))
                .snapshot("upper", (receiver, context, span) -> StringValue.of(text(receiver).toUpperCase()))
                .snapshot("lower", (receiver, context, span) -> StringValue.of(text(receiver).toLowerCase()))
                .snapshot("trimmed", (receiver, context, span) -> StringValue.of(text(receiver).strip()))
                .snapshot("chars", (receiver, context, span) -> {
                    // По кодовым точкам: 'a.chars' у строки с эмодзи даёт символы,
                    // а не половинки суррогатной пары.
                    List<Value> chars = new ArrayList<>();
                    text(receiver).codePoints()
                            .forEach(point -> chars.add(StringValue.of(new String(Character.toChars(point)))));
                    return ArrayValue.of(chars);
                })
                .method("indexOf", Arity.exactly(1), (receiver, context, arguments, span) ->
                        IntValue.of(text(receiver).indexOf(args("indexOf", arguments, context, span)
                                .string(0, "искомое"))))
                .method("contains", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(text(receiver).contains(args("contains", arguments, context, span)
                                .string(0, "искомое"))))
                .method("startsWith", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(text(receiver).startsWith(args("startsWith", arguments, context, span)
                                .string(0, "начало"))))
                .method("endsWith", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(text(receiver).endsWith(args("endsWith", arguments, context, span)
                                .string(0, "конец"))))
                .method("split", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    String separator = args("split", arguments, context, span).string(0, "разделитель");
                    List<Value> parts = new ArrayList<>();
                    if (separator.isEmpty()) {
                        // Пустой разделитель — это «по символам», и по кодовым точкам,
                        // как chars: иначе split("") и chars расходились бы на эмодзи.
                        text(receiver).codePoints()
                                .forEach(point -> parts.add(StringValue.of(new String(Character.toChars(point)))));
                    } else {
                        int from = 0;
                        String value = text(receiver);
                        int at;
                        while ((at = value.indexOf(separator, from)) >= 0) {
                            parts.add(StringValue.of(value.substring(from, at)));
                            from = at + separator.length();
                        }
                        parts.add(StringValue.of(value.substring(from)));
                    }
                    return ArrayValue.of(parts);
                })
                .method("replace", Arity.exactly(2), (receiver, context, arguments, span) -> {
                    Args args = args("replace", arguments, context, span);
                    return StringValue.of(text(receiver)
                            .replace(args.string(0, "что"), args.string(1, "на что")));
                })
                .method("repeat", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    long times = args("repeat", arguments, context, span).integer(0, "сколько раз");
                    if (times < 0) {
                        throw new WdlRuntimeError(ErrorKind.VALUE, span,
                                "repeat(): повторить строку " + times + " раз нельзя");
                    }
                    return StringValue.of(text(receiver).repeat((int) Math.min(times, Integer.MAX_VALUE)));
                })
                .method("slice", Arity.between(1, 2), (receiver, context, arguments, span) -> {
                    Args args = args("slice", arguments, context, span);
                    String value = text(receiver);
                    int from = clamp(args.integer(0, "начало"), value.length());
                    int to = args.has(1) ? clamp(args.integer(1, "конец"), value.length()) : value.length();
                    return StringValue.of(from >= to ? "" : value.substring(from, to));
                })
                .method("toNumber", Arity.exactly(0), (receiver, context, arguments, span) -> {
                    // Метод, а не свойство, и не из-за цены: 'toNumber' — глагол,
                    // и за ним стоит разбор, который может не получиться. null здесь
                    // честнее ошибки: 'x.toNumber() || 0' — обычная идиома.
                    String value = text(receiver).strip();
                    try {
                        return value.indexOf('.') < 0 && value.indexOf('e') < 0 && value.indexOf('E') < 0
                                ? IntValue.of(Long.parseLong(value))
                                : FloatValue.of(Double.parseDouble(value));
                    } catch (NumberFormatException notANumber) {
                        return NullValue.NULL;
                    }
                })
                .build();
    }

    private static StringValue self(Value receiver) {
        return (StringValue) receiver;
    }

    private static String text(Value receiver) {
        return self(receiver).value();
    }

    private static Args args(String name, List<Value> arguments, CallContext context, Span span) {
        return Args.of("string." + name, arguments, context, span);
    }

    /** Границы среза подрезаются, а не ошибаются, — по той же причине, что у массива. */
    private static int clamp(long index, int size) {
        return (int) Math.max(0, Math.min(index, size));
    }
}
