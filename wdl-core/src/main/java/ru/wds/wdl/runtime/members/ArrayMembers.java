package ru.wds.wdl.runtime.members;

import ru.wds.wdl.ast.op.BinaryOp;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.Callback;
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
 *
 * <h2>Обработка: {@code map}, {@code filter}, {@code reduce} и остальные</h2>
 * <b>Обработчику приходит один аргумент — элемент.</b> Ни индекса, ни самого массива
 * рядом с ним нет, и это решение, а не пропуск: правило «второй аргумент даётся, если
 * функция его принимает» сделало бы поведение зависимым от чужой сигнатуры и разъехалось
 * бы на функции с остатком ({@code *args} принимает что угодно) и на декорированной
 * функции, у которой сигнатура вообще чужая. Индекс, когда он действительно нужен,
 * даёт обычный {@code for} — он для этого и есть.
 * <p>
 * <b>Работа идёт по снимку.</b> {@link ArrayValue#items()} и так отдаёт снимок, поэтому
 * обработчик, дописывающий в тот же массив, не зациклит перебор и не получит
 * {@code ConcurrentModificationException} — он просто отработает по тому составу,
 * который был на входе.
 * <p>
 * <b>Своей точки проверки лимитов здесь нет.</b> Шагом считается вызов, а каждый виток
 * этих циклов и есть вызов функции скрипта: {@code Run.checkpoint} срабатывает
 * в {@code UserFunction.body}. Второй счётчик на витке дал бы двойной учёт одной работы.
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
                // Свёртка тем же '+', который написан в тексте скрипта: числа
                // складываются, класс с 'def `+`' — своим сложением, строки склеиваются.
                // Отдельного сложения «только для чисел» здесь нет по той же причине,
                // по какой у сортировки нет своего сравнения: разойдясь с оператором,
                // член начал бы врать на первом же массиве экземпляров.
                // У пустого — 0: вопрос «сколько всего» ответ у пустого имеет,
                // в отличие от «который наименьший».
                .snapshot("sum", (receiver, context, span) -> {
                    List<Value> items = self(receiver).items();
                    if (items.isEmpty()) {
                        return IntValue.ZERO;
                    }
                    Value total = items.get(0);
                    for (int i = 1; i < items.size(); i++) {
                        total = Overloading.binary(BinaryOp.ADD, total, items.get(i), span, context);
                    }
                    return total;
                })
                // min и max — это 'sorted.first' и 'sorted.last' без копии массива,
                // и на несравнимых значениях они ломаются ровно там же, где сортировка.
                .snapshot("min", (receiver, context, span) -> extreme(self(receiver), -1, span, context))
                .snapshot("max", (receiver, context, span) -> extreme(self(receiver), 1, span, context))
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
                .method("each", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    ArrayValue array = self(receiver);
                    Callback handler = args("each", arguments, context, span).callback(0, "обработчик");
                    for (Value item : array.items()) {
                        handler.call(item);
                    }
                    // Отдаётся сам массив, как у push: цепочка на each не обрывается,
                    // а тому, кому результат не нужен, лишнее значение не мешает.
                    return array;
                })
                .method("map", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    Callback transform = args("map", arguments, context, span).callback(0, "преобразование");
                    List<Value> items = self(receiver).items();
                    List<Value> mapped = new ArrayList<>(items.size());
                    for (Value item : items) {
                        mapped.add(transform.call(item));
                    }
                    return ArrayValue.of(mapped);
                })
                .method("filter", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    Callback predicate = args("filter", arguments, context, span).callback(0, "условие");
                    List<Value> kept = new ArrayList<>();
                    for (Value item : self(receiver).items()) {
                        if (predicate.call(item).isTruthy()) {
                            kept.add(item);
                        }
                    }
                    return ArrayValue.of(kept);
                })
                // Истинность здесь та же, что у 'if': условие вправе вернуть не только
                // логическое, и заводить второе понятие «истинно» ради членов незачем.
                .method("find", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    Callback predicate = args("find", arguments, context, span).callback(0, "условие");
                    for (Value item : self(receiver).items()) {
                        if (predicate.call(item).isTruthy()) {
                            return item;
                        }
                    }
                    // Не нашлось — null, как у first у пустого: это вопрос «есть ли такой».
                    return NullValue.NULL;
                })
                .method("reduce", Arity.between(1, 2), (receiver, context, arguments, span) -> {
                    Callback combine = args("reduce", arguments, context, span).callback(0, "свёртка");
                    List<Value> items = self(receiver).items();
                    // 'передан ли второй аргумент', а не 'не null ли он': reduce(f, null)
                    // — законное начальное значение, и отличать его от пропуска надо.
                    boolean seeded = arguments.size() > 1;
                    if (!seeded && items.isEmpty()) {
                        throw new WdlRuntimeError(ErrorKind.VALUE, span, "reduce() у пустого массива"
                                + " без начального значения: сворачивать нечего —"
                                + " передайте его вторым аргументом");
                    }
                    Value accumulator = seeded ? arguments.get(1) : items.get(0);
                    for (int i = seeded ? 0 : 1; i < items.size(); i++) {
                        accumulator = combine.call(accumulator, items.get(i));
                    }
                    return accumulator;
                })
                // Условие необязательно: без него спрашивается истинность самого
                // элемента — тот же вопрос, что задаёт 'if (item)'.
                .method("any", Arity.between(0, 1), (receiver, context, arguments, span) -> {
                    Callback predicate = predicate("any", arguments, context, span);
                    for (Value item : self(receiver).items()) {
                        if (holds(predicate, item)) {
                            return BoolValue.TRUE;
                        }
                    }
                    return BoolValue.FALSE;
                })
                .method("all", Arity.between(0, 1), (receiver, context, arguments, span) -> {
                    Callback predicate = predicate("all", arguments, context, span);
                    for (Value item : self(receiver).items()) {
                        if (!holds(predicate, item)) {
                            return BoolValue.FALSE;
                        }
                    }
                    // У пустого — true: «ни одного нарушения» это и есть ответ.
                    return BoolValue.TRUE;
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

    /**
     * Наименьший или наибольший элемент.
     *
     * @param direction знак, с которым кандидат обязан отличаться от текущего ответа:
     *                  {@code -1} для {@code min}, {@code 1} для {@code max}
     */
    private static Value extreme(ArrayValue array, int direction, Span span, CallContext context) {
        List<Value> items = array.items();
        if (items.isEmpty()) {
            return NullValue.NULL;
        }
        Value best = items.get(0);
        for (int i = 1; i < items.size(); i++) {
            Value candidate = items.get(i);
            if (Integer.signum(Overloading.order(candidate, best, span, context)) == direction) {
                best = candidate;
            }
        }
        return best;
    }

    /** Необязательное условие {@code any} и {@code all}: {@code null}, если его не передали. */
    private static Callback predicate(String name, List<Value> arguments, CallContext context, Span span) {
        Args args = args(name, arguments, context, span);
        return args.has(0) ? args.callback(0, "условие") : null;
    }

    /** Выполняется ли условие на элементе; без условия спрашивается истинность самого элемента. */
    private static boolean holds(Callback predicate, Value item) {
        return predicate == null ? item.isTruthy() : predicate.call(item).isTruthy();
    }

    private static Args args(String name, List<Value> arguments, CallContext context, Span span) {
        return Args.of("array." + name, arguments, context, span);
    }
}
