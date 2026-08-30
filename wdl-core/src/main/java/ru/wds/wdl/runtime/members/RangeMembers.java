package ru.wds.wdl.runtime.members;

import ru.wds.wdl.runtime.Operations;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.RangeValue;

/**
 * Члены диапазона — самый короткий набор после объекта, и это ответ, а не пропуск.
 * <p>
 * Диапазон неизменяем, поэтому меняющих членов здесь нет вовсе, а всё, что он о себе
 * знает, — это две границы и ответ на вопрос «пуст ли». Свойства, а не методы:
 * устареть такой ответ не может.
 * <p>
 * {@code size} сознательно нет. Он честно определён только при целых границах,
 * а свойство, бросающее ошибку на {@code 0.5..2.5}, — плохое свойство: границу
 * «когда можно спрашивать» пришлось бы держать в голове читателю. Вернётся вместе
 * с шагом, когда будет спрос.
 * <p>
 * {@code contains(v)} — метод, а не свойство: у него есть аргумент. Ответ он берёт
 * у {@link Operations#contains}, той же реализации, что стоит за {@code v in 1..5}.
 */
public final class RangeMembers {

    private RangeMembers() {
    }

    static MemberSet set() {
        return MemberSet.builder()
                .property("from", (receiver, context, span) -> self(receiver).from())
                .property("to", (receiver, context, span) -> self(receiver).to())
                .property("empty", (receiver, context, span) -> BoolValue.of(self(receiver).empty()))
                .method("contains", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(Operations.contains(self(receiver), arguments.get(0), span)))
                .build();
    }

    private static RangeValue self(Value receiver) {
        return (RangeValue) receiver;
    }
}
