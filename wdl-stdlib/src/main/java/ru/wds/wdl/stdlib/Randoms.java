package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;

import java.util.Random;

/**
 * Класс {@code Random}: генератор случайных чисел.
 * <p>
 * Второй показательный пример, и показывает он ровно то, чего нет у {@code File}:
 * <b>состояние, которое значениями языка не выражается</b>. Зерно — число и лежит
 * обычным полем, а сам {@link Random} значением быть не может, поэтому живёт
 * в {@link NativeInstance#state()}.
 * <p>
 * Разделение то же, что и везде: что выразимо — полем, чтобы это было видно в печати
 * и в переборе; что не выразимо — состоянием, о котором скрипт не знает.
 */
final class Randoms {

    static final NativeClass CLASS = NativeClass.named("Random")
            // Зерно необязательно: без него генератор непредсказуем, с ним —
            // повторяем, и это то, ради чего зерно вообще задают.
            .field("seed", NullValue.NULL)

            .init((self, context, arguments, span) -> {
                self.state(self.get("seed") instanceof NumberValue seed
                        ? new Random(seed.asLong())
                        : new Random());
                return NullValue.NULL;
            })

            .method("next", Arity.exactly(0), (self, context, arguments, span) ->
                    FloatValue.of(random(self).nextDouble()))

            .method("int", Arity.exactly(1), (self, context, arguments, span) -> {
                NumberValue bound = Std.number(arguments.get(0), span, "Random.int", "граница");
                if (!bound.isInteger() || bound.asLong() <= 0) {
                    throw new WdlRuntimeError(span,
                            "Random.int(): граница должна быть целым положительным числом, а здесь " + bound);
                }
                long limit = bound.asLong();
                return IntValue.of(limit <= Integer.MAX_VALUE
                        ? random(self).nextInt((int) limit)
                        : Math.floorMod(random(self).nextLong(), limit));
            })

            .method("pick", Arity.exactly(1), (self, context, arguments, span) -> {
                if (!(arguments.get(0) instanceof ArrayValue array)) {
                    throw new WdlRuntimeError(span, "Random.pick(): выбирать можно из массива, а здесь "
                            + arguments.get(0).type().title());
                }
                if (array.isEmpty()) {
                    throw new WdlRuntimeError(span, "Random.pick(): массив пуст, выбирать не из чего");
                }
                return array.get(random(self).nextInt(array.size()));
            })

            .build();

    private Randoms() {
    }

    private static Random random(NativeInstance self) {
        return self.state(Random.class);
    }
}
