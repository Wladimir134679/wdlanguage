package ru.wds.wdl.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Договор построителя нативных классов.
 * <p>
 * Всё, что здесь проверяется, — обещания, данные автору библиотеки: заголовок ведёт
 * себя как заголовок класса на wdl, ошибка в объявлении видна при сборке (то есть
 * при старте приложения), а наследование склеивает таблицы по тем же правилам,
 * что {@code ClassShape} у классов языка.
 */
class NativeClassTest {

    private static final CallContext CONTEXT = text -> { };
    private static final Span AT = Span.NONE;

    private static Value instance(NativeClass type, Value... arguments) {
        return type.instantiate(List.of(arguments), CONTEXT, AT);
    }

    // --- заголовок -----------------------------------------------------------

    @Test
    @DisplayName("сколько полей, столько и аргументов; со значением — необязательный")
    void headerIsTheArity() {
        NativeClass point = NativeClass.named("Point")
                .field("x")
                .field("y", IntValue.of(0))
                .build();

        assertEquals(Arity.between(1, 2), point.arity());
        assertEquals("Point{\"x\": 1, \"y\": 0}", instance(point, IntValue.of(1)).display());
        assertEquals("class Point(x, y)", point.display());
    }

    @Test
    @DisplayName("имена полей становятся контрактом создания: 'new Point(y: 3)' работает")
    void headerNamesTheParameters() {
        NativeClass point = NativeClass.named("Point")
                .field("x")
                .field("y", IntValue.of(0))
                .build();

        assertTrue(point.signature().namesKnown());
        assertEquals(0, point.signature().indexOf("x"));
        assertEquals(1, point.signature().indexOf("y"));
        assertEquals(-1, point.signature().indexOf("z"));
        // Значение по умолчанию здесь готовое, поэтому пропуск закрывает связыватель,
        // а сам класс о именованных аргументах ничего не знает.
        assertEquals(IntValue.of(0), point.signature().params().get(1).constant());
    }

    @Test
    @DisplayName("поле объявляется один раз")
    void duplicateField() {
        assertEquals("поле 'x' класса 'Point' уже объявлено",
                assertThrows(IllegalArgumentException.class,
                        () -> NativeClass.named("Point").field("x").field("x")).getMessage());
    }

    @Test
    @DisplayName("обязательное поле не идёт после необязательного: пропуск нечем записать")
    void requiredAfterOptional() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NativeClass.named("Point").field("x", IntValue.of(0)).field("y"))
                .getMessage().contains("не может идти после поля со значением"));
    }

    @Test
    @DisplayName("метод и поле класса объявляются один раз")
    void duplicateMembers() {
        NativeMethod body = (self, context, arguments, span) -> NullValue.NULL;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NativeClass.named("Bag")
                .method("size", Arity.exactly(0), body)
                .method("size", Arity.exactly(0), body)).getMessage().contains("уже объявлен"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> NativeClass.named("Bag")
                .constant("LIMIT", IntValue.of(1))
                .factory("LIMIT", Arity.exactly(0),
                        (type, context, arguments, span) -> NullValue.NULL))
                .getMessage().contains("уже объявлено"));
    }

    @Test
    @DisplayName("фабрика получает свой класс: статического поля с ним нет")
    void factoryKnowsItsClass() {
        NativeClass point = NativeClass.named("Point")
                .field("x")
                .factory("zero", Arity.exactly(0), (type, context, arguments, span) ->
                        type.instantiate(List.of(IntValue.of(0)), context, span))
                .build();

        Value zero = point.statics().get("zero");
        assertEquals("Point{\"x\": 0}",
                ((ru.wds.wdl.value.FunctionValue) zero).call(CONTEXT, List.of(), AT).display());
    }

    // --- наследование --------------------------------------------------------

    @Test
    @DisplayName("заголовок продолжает родительский, методы склеиваются плоско")
    void inheritance() {
        NativeClass base = NativeClass.named("Base")
                .field("id")
                .method("kind", Arity.exactly(0), (self, context, arguments, span) ->
                        StringValue.of("base"))
                .method("id", Arity.exactly(0), (self, context, arguments, span) ->
                        self.get("id"))
                .build();

        NativeClass child = NativeClass.named("Child")
                .extending(base)
                .field("title", StringValue.of("без имени"))
                .method("kind", Arity.exactly(0), (self, context, arguments, span) ->
                        StringValue.of("child"))
                .build();

        assertEquals(Arity.between(1, 2), child.arity());
        assertEquals("class Child(id, title)", child.display());

        Value made = instance(child, IntValue.of(7));
        assertEquals("Child{\"id\": 7, \"title\": \"без имени\"}", made.display());
        // Свой метод победил, унаследованный достался как есть.
        assertEquals("child", call(child, made, "kind").display());
        assertEquals(IntValue.of(7), call(child, made, "id"));
    }

    @Test
    @DisplayName("is отвечает предку и его трейтам, но не соседу по иерархии")
    void conformsToAncestors() {
        NativeTrait counted = NativeTrait.named("Counted")
                .requireMethod("count", Arity.exactly(0))
                .build();
        NativeClass base = NativeClass.named("Base")
                .method("count", Arity.exactly(0), (self, context, arguments, span) -> IntValue.of(0))
                .with(counted)
                .build();
        NativeClass child = NativeClass.named("Child").extending(base).build();
        NativeClass stranger = NativeClass.named("Stranger").build();

        assertTrue(child.conformsTo(child));
        assertTrue(child.conformsTo(base));
        assertTrue(child.conformsTo(counted));
        assertFalse(child.conformsTo(stranger));
        assertFalse(base.conformsTo(child));
    }

    @Test
    @DisplayName("конструкторы выполняются от дальнего предка к потомку")
    void initChain() {
        List<String> order = new ArrayList<>();
        NativeClass base = NativeClass.named("Base")
                .field("id")
                .init((self, context, arguments, span) -> {
                    order.add("base");
                    return NullValue.NULL;
                })
                .build();
        NativeClass child = NativeClass.named("Child")
                .extending(base)
                .init((self, context, arguments, span) -> {
                    order.add("child");
                    // К этому моменту объект собран целиком — и родительская часть тоже.
                    order.add(self.get("id").display());
                    return NullValue.NULL;
                })
                .build();

        instance(child, IntValue.of(3));
        assertEquals(List.of("base", "child", "3"), order);
    }

    @Test
    @DisplayName("родитель один, и заголовок склеивается по правилам заголовка")
    void inheritanceIsChecked() {
        NativeClass base = NativeClass.named("Base").field("id").build();
        NativeClass other = NativeClass.named("Other").build();

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NativeClass.named("Child").extending(base).extending(other))
                .getMessage().contains("уже есть родитель"));

        assertTrue(assertThrows(IllegalStateException.class,
                () -> NativeClass.named("Child").extending(base).field("id").build())
                .getMessage().contains("уже объявлено у родителя"));

        NativeClass optional = NativeClass.named("Optional").field("id", IntValue.of(0)).build();
        assertTrue(assertThrows(IllegalStateException.class,
                () -> NativeClass.named("Child").extending(optional).field("name").build())
                .getMessage().contains("не может идти после поля 'id' со значением"));
    }

    @Test
    @DisplayName("поля самого класса не наследуются: фабрика знает, что создаёт")
    void staticsAreNotInherited() {
        NativeClass base = NativeClass.named("Base").constant("LIMIT", IntValue.of(5)).build();
        NativeClass child = NativeClass.named("Child").extending(base).build();

        assertEquals(IntValue.of(5), base.statics().get("LIMIT"));
        assertEquals(NullValue.NULL, child.statics().get("LIMIT"));
    }

    @Test
    @DisplayName("метода нет — null, а не исключение: решает вызывающий")
    void unknownMethod() {
        NativeClass bag = NativeClass.named("Bag").build();
        assertNull(bag.method((ru.wds.wdl.value.types.InstanceObjectValue) instance(bag), "нет"));
    }

    private static Value call(NativeClass type, Value target, String method) {
        return type.method((ru.wds.wdl.value.types.InstanceObjectValue) target, method)
                .call(CONTEXT, List.of(), AT);
    }
}
