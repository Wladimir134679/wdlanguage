package ru.wds.wdl.runtime.members;

import ru.wds.wdl.embed.Args;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Operations;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * Члены массива.
 * <p>
 * Половины две, и делит их правило: {@code size}, {@code first}, {@code sorted}
 * отвечают о массиве, каким он виден сейчас, — это свойства; {@code push},
 * {@code sort}, {@code remove} его меняют — это методы. Пара {@code sorted}
 * и {@code sort()} заведена сознательно: первое читается в цепочке и получателя
 * не трогает, второе не копирует миллион элементов, и глагол в имени не врёт.
 * <p>
 * {@code first} и {@code last} у пустого массива дают {@code null}, а не ошибку:
 * это вопрос «есть ли что-нибудь», и {@code if (a.first)} должен просто работать —
 * то же решение, что у отсутствующего ключа объекта. Ошибкой остаётся {@code a[0]}:
 * там названа позиция, которой нет, и промолчать значило бы спрятать опечатку.
 */
public final class ArrayMembers {

    private ArrayMembers() {
    }

    static MemberSet set() {
        return MemberSet.builder()
                .property("size", (receiver, context, span) -> IntValue.of(self(receiver).size()))
                .property("empty", (receiver, context, span) -> BoolValue.of(self(receiver).isEmpty()))
                .property("first", (receiver, context, span) -> {
                    ArrayValue array = self(receiver);
                    return array.isEmpty() ? NullValue.NULL : array.get(0);
                })
                .property("last", (receiver, context, span) -> {
                    ArrayValue array = self(receiver);
                    return array.isEmpty() ? NullValue.NULL : array.get(array.size() - 1);
                })
                .snapshot("sorted", (receiver, context, span) -> {
                    List<Value> items = new ArrayList<>(self(receiver).items());
                    items.sort((left, right) -> Operations.order(left, right, span));
                    return ArrayValue.of(items);
                })
                .snapshot("reversed", (receiver, context, span) -> {
                    List<Value> items = new ArrayList<>(self(receiver).items());
                    Collections.reverse(items);
                    return ArrayValue.of(items);
                })
                .snapshot("copy", (receiver, context, span) -> ArrayValue.of(self(receiver).items()))
                .method("push", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    array.add(arguments.get(0));
                    // Возвращается сам массив, а не новая длина: цепочка читается,
                    // а длину спрашивают у 'size' тогда, когда она нужна.
                    return array;
                })
                .method("pop", Arity.exactly(0), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    if (array.isEmpty()) {
                        throw new WdlRuntimeError(ErrorKind.INDEX, span,
                                "pop() у пустого массива: удалять нечего");
                    }
                    return array.removeAt(array.size() - 1);
                })
                .method("insert", Arity.exactly(2), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    Args args = args("insert", arguments, context, span);
                    // Вставить можно и в конец: границей служит size, а не size - 1.
                    array.insert(bound(args.integer(0, "индекс"), array.size(), span), arguments.get(1));
                    return array;
                })
                .method("remove", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    Args args = args("remove", arguments, context, span);
                    return array.removeAt(bound(args.integer(0, "индекс"), array.size() - 1, span));
                })
                .method("indexOf", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    List<Value> items = self(receiver).items();
                    for (int i = 0; i < items.size(); i++) {
                        if (Operations.equal(items.get(i), arguments.get(0))) {
                            return IntValue.of(i);
                        }
                    }
                    return IntValue.of(-1);
                })
                .method("contains", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    for (Value item : self(receiver).items()) {
                        if (Operations.equal(item, arguments.get(0))) {
                            return BoolValue.of(true);
                        }
                    }
                    return BoolValue.of(false);
                })
                .method("join", Arity.between(0, 1), (receiver, context, arguments, span) -> {
                    Args args = args("join", arguments, context, span);
                    String separator = args.has(0) ? args.string(0, "разделитель") : "";
                    StringJoiner joiner = new StringJoiner(separator);
                    for (Value item : self(receiver).items()) {
                        joiner.add(item.display());
                    }
                    return StringValue.of(joiner.toString());
                })
                .method("slice", Arity.between(1, 2), (receiver, context, arguments, span) -> {
                    Args args = args("slice", arguments, context, span);
                    List<Value> items = self(receiver).items();
                    int from = clamp(args.integer(0, "начало"), items.size());
                    int to = args.has(1) ? clamp(args.integer(1, "конец"), items.size()) : items.size();
                    return from >= to ? ArrayValue.of(List.of()) : ArrayValue.of(items.subList(from, to));
                })
                .method("sort", Arity.exactly(0), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    List<Value> items = new ArrayList<>(array.items());
                    items.sort((left, right) -> Operations.order(left, right, span));
                    array.replaceAll(items);
                    return array;
                })
                .method("reverse", Arity.exactly(0), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    List<Value> items = new ArrayList<>(array.items());
                    Collections.reverse(items);
                    array.replaceAll(items);
                    return array;
                })
                .method("clear", Arity.exactly(0), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    array.clear();
                    return array;
                })
                .build();
    }

    private static ArrayValue self(Value receiver) {
        return (ArrayValue) receiver;
    }

    private static Args args(String name, List<Value> arguments, CallContext context, Span span) {
        return Args.of("array." + name, arguments, context, span);
    }

    /** Индекс в границах: сообщение то же, что у обращения по индексу. */
    private static int bound(long index, int last, Span span) {
        if (index < 0 || index > last) {
            throw new WdlRuntimeError(ErrorKind.INDEX, span,
                    "индекс " + index + " вне границ массива размером " + (last + 1));
        }
        return (int) index;
    }

    /**
     * Срез границами не ошибается, а подрезает их. Это не поблажка: у {@code slice}
     * концы — это «докуда», а не «какой элемент», и просить хвост длиннее массива —
     * обычное дело.
     */
    private static int clamp(long index, int size) {
        return (int) Math.max(0, Math.min(index, size));
    }
}
