package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.Environment;
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
 * <p>
 * Класс собирается на запуск — по той же причине, что и {@link Files}.
 */
final class Randoms {

    private Randoms() {
    }

    /** Класс {@code Random} этого запуска: тот, что уже в области, или новый. */
    static NativeClass in(Environment scope) {
        return Types.in(scope, "Random", Randoms::build);
    }

    private static NativeClass build() {
        return NativeClass.named("Random")
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
                long limit = arguments.integer(0, "граница");
                if (limit <= 0) {
                    throw arguments.bad(0, "граница", "ожидалось положительное число");
                }
                return IntValue.of(limit <= Integer.MAX_VALUE
                        ? random(self).nextInt((int) limit)
                        : Math.floorMod(random(self).nextLong(), limit));
            })

            .method("pick", Arity.exactly(1), (self, context, arguments, span) -> {
                ArrayValue array = arguments.array(0, "откуда выбирать");
                if (array.isEmpty()) {
                    throw arguments.bad(0, "откуда выбирать", "ожидался непустой массив");
                }
                return array.get(random(self).nextInt(array.size()));
            })

            .build();
    }

    private static Random random(NativeInstance self) {
        return self.state(Random.class);
    }
}
