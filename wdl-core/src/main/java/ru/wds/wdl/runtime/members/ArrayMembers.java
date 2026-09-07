package ru.wds.wdl.runtime.members;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Indexes;
import ru.wds.wdl.runtime.Overloading;
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
                // Порядок берётся у той же цепочки, что стоит за 'a[0] < a[1]':
                // ядро, потом член '<=>' у класса. Заведи сортировка своё сравнение —
                // она разошлась бы с оператором на первом же массиве экземпляров.
                .snapshot("sorted", (receiver, context, span) -> {
                    List<Value> items = new ArrayList<>(self(receiver).items());
                    items.sort((left, right) -> Overloading.order(left, right, span, context));
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
                    array.insert(Indexes.position(array.size(), args.integer(0, "индекс"), "массива", span),
                            arguments.get(1));
                    return array;
                })
                .method("remove", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    Args args = args("remove", arguments, context, span);
                    return array.removeAt(Indexes.element(array.size(), args.integer(0, "индекс"), "массива", span));
                })
                .method("indexOf", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    List<Value> items = self(receiver).items();
                    for (int i = 0; i < items.size(); i++) {
                        if (Overloading.equal(items.get(i), arguments.get(0), span, context)) {
                            return IntValue.of(i);
                        }
                    }
                    return IntValue.of(-1);
                })
                // Ответ берётся у той же реализации, что стоит за 'x in a': член
                // и оператор обязаны отвечать одинаково всегда, а не пока за ними
                // следят.
                .method("contains", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(Overloading.contains(self(receiver), arguments.get(0), span, context)))
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
                    int from = Indexes.cut(items.size(), args.integer(0, "начало"));
                    int to = args.has(1) ? Indexes.cut(items.size(), args.integer(1, "конец")) : items.size();
                    return from >= to ? ArrayValue.of(List.of()) : ArrayValue.of(items.subList(from, to));
                })
                .method("sort", Arity.exactly(0), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    List<Value> items = new ArrayList<>(array.items());
                    items.sort((left, right) -> Overloading.order(left, right, span, context));
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
}
