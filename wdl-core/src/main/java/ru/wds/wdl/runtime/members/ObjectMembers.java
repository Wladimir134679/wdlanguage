package ru.wds.wdl.runtime.members;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.Operations;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Члены объекта — самый короткий набор из всех, и это решение, а не недоделка.
 * <p>
 * <b>Данные объекта перекрывают его члены</b> (общее правило: ключ, положенный
 * руками, значит больше встроенного имени), поэтому каждый член здесь — это отобранное
 * у автора имя ключа, работающее через раз:
 * <pre>{@code
 * box = {size: "L"}
 * println(box.size)          // L — данные, их клали руками
 * println(Object.size(box))  // 1 — надёжный путь, через дескриптор
 * }</pre>
 * Отсюда правило набора: у объекта членов столько, сколько нельзя написать ключом,
 * и ни одним больше. Всё остальное живёт у дескриптора {@code Object}.
 * <p>
 * У экземпляра класса эти же члены работают — экземпляр это {@code object}, — но
 * встают в цепочку после полей, свойств и методов класса, то есть перекрываются
 * ещё и ими.
 */
public final class ObjectMembers {

    private ObjectMembers() {
    }

    static MemberSet set() {
        return MemberSet.builder()
                .property("size", (receiver, context, span) -> IntValue.of(self(receiver).size()))
                .property("empty", (receiver, context, span) -> BoolValue.of(self(receiver).isEmpty()))
                .snapshot("keys", (receiver, context, span) ->
                        ArrayValue.of(new ArrayList<>(self(receiver).entries().keySet())))
                .snapshot("values", (receiver, context, span) ->
                        ArrayValue.of(new ArrayList<>(self(receiver).entries().values())))
                .snapshot("pairs", (receiver, context, span) -> {
                    // Пара — двухэлементный массив, а не объект с ключами key/value:
                    // объект пришлось бы разбирать теми же обращениями, которые
                    // у объекта и перекрываются данными.
                    List<Value> pairs = new ArrayList<>();
                    for (Map.Entry<Value, Value> entry : self(receiver).entries().entrySet()) {
                        pairs.add(ArrayValue.of(entry.getKey(), entry.getValue()));
                    }
                    return ArrayValue.of(pairs);
                })
                // Тот же ответ, что у 'k in obj' и 'obj has k': реализация одна.
                .method("has", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(Operations.contains(self(receiver), arguments.get(0), span)))
                .method("get", Arity.between(1, 2), (receiver, context, arguments, span) -> {
                    // Путь к ключу, который назван как член: 'box.get("size")' отдаёт
                    // данные, что бы ни лежало в наборе членов.
                    MapValue object = self(receiver);
                    Args args = args("get", arguments, context, span);
                    if (object.has(arguments.get(0))) {
                        return object.get(arguments.get(0));
                    }
                    return args.has(1) ? arguments.get(1) : NullValue.NULL;
                })
                .method("remove", Arity.exactly(1), (receiver, context, arguments, span) ->
                        self(receiver).remove(arguments.get(0)))
                .method("clear", Arity.exactly(0), (receiver, context, arguments, span) -> {
                    MapValue object = self(receiver);
                    object.clear();
                    return object;
                })
                .build();
    }

    private static MapValue self(Value receiver) {
        return (MapValue) receiver;
    }

    private static Args args(String name, List<Value> arguments, CallContext context, Span span) {
        return Args.of("object." + name, arguments, context, span);
    }
}
