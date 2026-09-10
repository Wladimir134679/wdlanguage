package ru.wds.wdl.stdlib.streams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Конвейер сам по себе, без языка вокруг.
 * <p>
 * Проверяется здесь не «результат правильный» — это увидит и тест на скриптах, —
 * а то, ради чего конвейер вообще заведён и что снаружи не видно:
 * <ul>
 *   <li><b>лень</b>: сколько раз спросили источник, а не какой ответ получили;</li>
 *   <li><b>короткое замыкание</b>: обход кончается там, где ответ уже известен;</li>
 *   <li><b>каскадное закрытие</b>: {@code close()} доходит до источника с любой
 *       глубины и через любую ошибку.</li>
 * </ul>
 * Источник здесь — счётчик обращений, поэтому «прочитано три элемента из тысячи»
 * это утверждение о работе, а не о результате.
 */
class PipelineTest {

    private static final Span PLACE = Span.point(0);

    /** Контекст без запуска: вывод в никуда, шаги не считаются — их и нечем считать. */
    private static final CallContext CONTEXT = text -> {
    };

    /** Источник, который помнит, сколько раз у него спросили элемент. */
    private static final class Counting implements Source {

        private final List<Value> items;
        private int asked;
        private boolean closed;

        Counting(int size) {
            items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                items.add(IntValue.of(i));
            }
        }

        @Override
        public Value next() {
            asked++;
            return asked <= items.size() ? items.get(asked - 1) : null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Функция скрипта, которой здесь нет: вместо неё лямбда с тем же интерфейсом. */
    private static Callback fn(Function<List<Value>, Value> body) {
        return Callback.of(new FunctionValue() {

            @Override
            public String name() {
                return "test";
            }

            @Override
            public Arity arity() {
                return Arity.any();
            }

            @Override
            public Value call(CallContext context, List<Value> arguments, Span span) {
                return body.apply(arguments);
            }

            @Override
            public String display() {
                return "def test";
            }
        }, CONTEXT, PLACE);
    }

    private static long number(Value value) {
        return ((IntValue) value).asLong();
    }

    // --- лень ----------------------------------------------------------------

    @Test
    @DisplayName("Пока не позвали терминальную операцию, у источника не спросили ничего")
    void nothingHappensUntilTerminal() {
        Counting source = new Counting(1000);
        Stages.map(Stages.filter(source, fn(a -> BoolValue.TRUE)), fn(a -> a.get(0)));
        assertEquals(0, source.asked, "стадии не имеют права трогать вход");
    }

    @Test
    @DisplayName("first() читает один элемент из тысячи — ради этого конвейер и ленивый")
    void firstReadsOne() {
        Counting source = new Counting(1000);
        Value got = Terminals.first(Stages.map(source, fn(a -> a.get(0))));
        assertEquals(0, number(got));
        assertEquals(1, source.asked);
        assertTrue(source.closed, "терминальная операция закрывает конвейер сама");
    }

    @Test
    @DisplayName("Тяжёлое преобразование считается до первого совпадения, а не для всех")
    void mapShortCircuits() {
        Counting source = new Counting(1000);
        int[] heavy = {0};
        Value found = Terminals.find(Stages.map(source, fn(a -> {
            heavy[0]++;
            return IntValue.of(number(a.get(0)) * 10);
        })), fn(a -> BoolValue.of(number(a.get(0)) >= 30)));
        assertEquals(30, number(found));
        // Ровно четыре: у массива 'a.map(heavy).find(p)' их было бы тысяча.
        assertEquals(4, heavy[0]);
    }

    @Test
    @DisplayName("limit не спрашивает вход дальше своей границы")
    void limitStopsAsking() {
        Counting source = new Counting(1000);
        assertEquals(3, ((ArrayValue) Terminals.list(Stages.limit(source, 3))).size());
        assertEquals(3, source.asked);
    }

    @Test
    @DisplayName("takeWhile кончается на первом ложном и вход больше не читает")
    void takeWhileStops() {
        Counting source = new Counting(1000);
        Value list = Terminals.list(Stages.takeWhile(source,
                fn(a -> BoolValue.of(number(a.get(0)) < 4))));
        assertEquals(4, ((ArrayValue) list).size());
        // Четыре взятых плюс один, на котором условие стало ложным.
        assertEquals(5, source.asked);
    }

    @Test
    @DisplayName("find останавливается на совпадении: условие спрошено ровно столько раз")
    void findShortCircuits() {
        Counting source = new Counting(1000);
        int[] asked = {0};
        Value found = Terminals.find(source, fn(a -> {
            asked[0]++;
            return BoolValue.of(number(a.get(0)) == 2);
        }));
        assertEquals(2, number(found));
        assertEquals(3, asked[0]);
        assertTrue(source.closed);
    }

    @Test
    @DisplayName("peek зовёт обработчик тогда, когда элемент кому-то понадобился")
    void peekIsLazyToo() {
        Counting source = new Counting(1000);
        List<Value> seen = new ArrayList<>();
        Terminals.list(Stages.limit(Stages.peek(source, fn(a -> {
            seen.add(a.get(0));
            return a.get(0);
        })), 2));
        assertEquals(2, seen.size());
    }

    // --- бесконечное -----------------------------------------------------------

    @Test
    @DisplayName("Бесконечный источник с limit заканчивается")
    void infiniteWithLimit() {
        Source infinite = Sources.iterate(IntValue.of(1),
                fn(a -> IntValue.of(number(a.get(0)) * 2)), CONTEXT, PLACE);
        Value list = Terminals.list(Stages.limit(infinite, 5));
        assertEquals("[1, 2, 4, 8, 16]", list.display());
    }

    @Test
    @DisplayName("distinct барьером не является: он помнит выданное, а не читает вход целиком")
    void distinctIsLazy() {
        Source repeated = Stages.flatMap(Sources.repeat(IntValue.of(1), CONTEXT, PLACE),
                fn(a -> ArrayValue.of(IntValue.of(1), IntValue.of(2))), PLACE);
        Value list = Terminals.list(Stages.limit(
                Stages.distinct(repeated, CONTEXT, PLACE), 2));
        assertEquals("[1, 2]", list.display());
    }

    // --- составные -------------------------------------------------------------

    @Test
    @DisplayName("zip кончается там, где кончился любой из двух")
    void zipStopsAtShorter() {
        Source left = new Counting(10);
        Source right = new Counting(3);
        Value list = Terminals.list(Stages.zip(left, right));
        assertEquals("[[0, 0], [1, 1], [2, 2]]", list.display());
    }

    @Test
    @DisplayName("chunked отдаёт последний кусок укороченным, windowed неполного окна не даёт")
    void chunkedAndWindowed() {
        assertEquals("[[0, 1], [2, 3], [4]]",
                Terminals.list(Stages.chunked(new Counting(5), 2)).display());
        assertEquals("[[0, 1, 2], [1, 2, 3]]",
                Terminals.list(Stages.windowed(new Counting(4), 3)).display());
        assertEquals("[]", Terminals.list(Stages.windowed(new Counting(2), 3)).display());
    }

    @Test
    @DisplayName("concat закрывает исчерпанное звено сразу, не дожидаясь конца склейки")
    void concatClosesEarly() {
        Counting first = new Counting(1);
        Counting second = new Counting(1);
        Source joined = Sources.concat(List.of(first, second));
        joined.next();
        assertFalse(first.closed, "пока не дочитали — не закрываем");
        joined.next();
        assertTrue(first.closed, "дочитали первое — закрыли его");
        joined.close();
        assertTrue(second.closed);
    }

    // --- закрытие --------------------------------------------------------------

    @Test
    @DisplayName("close доходит до источника с любой глубины конвейера")
    void closeGoesAllTheWayUp() {
        Counting source = new Counting(10);
        Source deep = Stages.map(Stages.filter(Stages.skip(Stages.peek(source,
                                fn(a -> a.get(0))), 1),
                        fn(a -> BoolValue.TRUE)),
                fn(a -> a.get(0)));
        deep.close();
        assertTrue(source.closed);
    }

    @Test
    @DisplayName("Ошибка обработчика уходит наверх, а конвейер всё равно закрывается")
    void closesOnFailure() {
        Counting source = new Counting(10);
        RuntimeException boom = new IllegalStateException("сломалось");
        try {
            Terminals.list(Stages.map(source, fn(a -> {
                throw boom;
            })));
        } catch (IllegalStateException expected) {
            assertEquals(boom, expected);
        }
        assertTrue(source.closed, "закрывать надо из finally, а не из try");
    }

    @Test
    @DisplayName("Ответы у пустого потока — те же, что у пустого массива")
    void emptyAnswers() {
        assertEquals(0, number(Terminals.count(Sources.empty())));
        assertEquals(0, number(Terminals.sum(Sources.empty(), CONTEXT, PLACE)));
        assertEquals("null", Terminals.first(Sources.empty()).display());
        assertEquals("null", Terminals.extreme(Sources.empty(), -1, CONTEXT, PLACE).display());
        assertEquals("true", Terminals.all(Sources.empty(), null).display());
        assertEquals("false", Terminals.any(Sources.empty(), null).display());
        assertEquals("", ((StringValue) Terminals.join(Sources.empty(), ",")).value());
    }
}
