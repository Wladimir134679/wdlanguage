package ru.wds.wdl.bridge.reflect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.reflect.samples.Counter;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Своя обёртка над чужим типом: часть членов лямбдами, часть — от Java.
 * <p>
 * Это тот случай, ради которого источник членов и написан: класс свой (свой
 * конструктор, своё состояние), но половина его методов уже написана в чужом типе.
 */
class FromJavaTest {

    private static final CallContext CONTEXT = text -> {
    };
    private static final Span AT = Span.NONE;

    /** Класс скрипта поверх чужого {@code Counter}: создание своё, методы — его. */
    private static NativeClass counterClass() {
        return NativeClass.named("Counter")
                .param("name", StringValue.of("счёт"))
                .init((self, context, args, span) -> {
                    self.state(new Counter(args.at(0).display(), 1));
                    return NullValue.NULL;
                })
                .members(FromJava.of(Counter.class)
                        .bean("value")
                        .field("name")
                        .method("getValue")
                        .methodAs("next", "getAndIncrement"))
                .build();
    }

    private static Value instance(NativeClass type, Value... arguments) {
        return type.instantiate(List.of(arguments), CONTEXT, AT);
    }

    @Test
    @DisplayName("Состояние не того типа — отказ при сборке класса, а не при первом обращении")
    void wrongBackingIsRefusedAtBuild() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> NativeClass.named("Счётчик")
                        .backing(StringBuilder.class)
                        .members(FromJava.of(Counter.class).bean("value"))
                        .build());
        assertTrue(error.getMessage().contains("StringBuilder"), error.getMessage());
        assertTrue(error.getMessage().contains("Counter"), error.getMessage());
    }

    @Test
    @DisplayName("via достаёт объект из состояния: члены берутся не у того, что лежит в state")
    void viaExtractsFromState() {
        NativeClass boxed = NativeClass.named("Boxed")
                .backing(List.class)
                .init((self, context, args, span) -> {
                    self.state(List.of(new Counter("внутри", 7)));
                    return NullValue.NULL;
                })
                .members(FromJava.of(Counter.class)
                        .via(state -> ((List<?>) state).get(0))
                        .bean("value"))
                .build();

        Value made = instance(boxed);
        assertEquals(IntValue.of(7), ((InstanceObjectValue) made).owner()
                .property("value").read(made, CONTEXT, AT));
    }

    @Test
    @DisplayName("метод чужого типа зовётся у объекта из состояния экземпляра")
    void methodGoesToState() {
        NativeClass counter = counterClass();
        Value made = instance(counter, StringValue.of("заказы"));

        assertEquals(IntValue.of(1), counter.method((InstanceObjectValue) made, "getValue")
                .call(CONTEXT, List.of(), AT));
        assertEquals(IntValue.of(1), counter.method((InstanceObjectValue) made, "next")
                .call(CONTEXT, List.of(), AT));
        assertEquals(IntValue.of(2), counter.method((InstanceObjectValue) made, "getValue")
                .call(CONTEXT, List.of(), AT),
                "метод отработал у настоящего Java-объекта, а не у копии");
    }

    @Test
    @DisplayName("свойство из аксессоров всегда свежее — и пишется обратно в объект")
    void beanIsLiveProperty() {
        NativeClass counter = counterClass();
        InstanceObjectValue made = (InstanceObjectValue) instance(counter);

        assertEquals(IntValue.of(1), counter.property("value").read(made, CONTEXT, AT));
        counter.method(made, "next").call(CONTEXT, List.of(), AT);
        assertEquals(IntValue.of(2), counter.property("value").read(made, CONTEXT, AT),
                "значение живёт в Java-объекте, а не в снимке при создании");

        counter.property("value").write(made, IntValue.of(10), CONTEXT, AT);
        assertEquals(IntValue.of(10), counter.property("value").read(made, CONTEXT, AT));
    }

    @Test
    @DisplayName("параметр создания полем не становится: за именем стоит свойство")
    void paramIsNotStored() {
        NativeClass counter = counterClass();
        Value made = instance(counter, StringValue.of("заказы"));

        // 'name' объявлен параметром и открыт свойством над полем Java: среди пар
        // экземпляра его нет, но читается он тем же обращением.
        assertEquals("Counter{}", made.display());
        assertEquals(StringValue.of("заказы"),
                counter.property("name").read((InstanceObjectValue) made, CONTEXT, AT));
    }

    @Test
    @DisplayName("только для чтения — по просьбе, даже когда сеттер у типа есть")
    void readOnlyWins() {
        NativeClass counter = NativeClass.named("Counter")
                .init((self, context, args, span) -> {
                    self.state(new Counter("счёт", 1));
                    return NullValue.NULL;
                })
                .members(FromJava.of(Counter.class).bean("value").readOnly("value"))
                .build();

        assertTrue(counter.property("value").readable());
        assertFalse(counter.property("value").writable());
    }

    @Test
    @DisplayName("опечатка в имени члена — исключение при сборке класса, а не null у скрипта")
    void typoRefusedAtBuild() {
        assertEquals("класс 'Counter': у типа " + Counter.class.getName() + " нет метода 'getVlaue'",
                assertThrows(IllegalArgumentException.class, () -> NativeClass.named("Counter")
                        .members(FromJava.of(Counter.class).method("getVlaue"))
                        .build()).getMessage());

        assertEquals("класс 'Counter': у типа " + Counter.class.getName()
                        + " нет пары аксессоров для свойства 'size': нужен getSize() или isSize()",
                assertThrows(IllegalArgumentException.class, () -> NativeClass.named("Counter")
                        .members(FromJava.of(Counter.class).bean("size"))
                        .build()).getMessage());
    }

    @Test
    @DisplayName("класс держит в состоянии не тот тип — отказ на первом же обращении")
    void wrongStateRefused() {
        NativeClass wrong = NativeClass.named("Counter")
                .init((self, context, args, span) -> {
                    self.state("строка вместо счётчика");
                    return NullValue.NULL;
                })
                .members(FromJava.of(Counter.class).method("getValue"))
                .build();
        InstanceObjectValue made = (InstanceObjectValue) instance(wrong);

        assertThrows(IllegalStateException.class,
                () -> wrong.method(made, "getValue").call(CONTEXT, List.of(), AT));
    }
}
