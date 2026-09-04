package ru.wds.wdl.runtime.members;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

/**
 * Члены класса: встроенная рефлексия — родитель, трейты, состав.
 * <p>
 * <b>Отдаются значения, а не имена</b> ({@code Circle.parent} — это класс
 * {@code Shape}, а не строка «Shape»): по строке нельзя ни спросить {@code is},
 * ни создать экземпляр, а восстановить класс по имени нечем — реестра имён язык
 * не заводит. Имена остаются там, где значения не существует: у методов и свойств.
 * <p>
 * <b>Статика перекрывает эти члены</b>, как ключ перекрывает член у объекта:
 * {@code Point.methods = [...]} — законная запись, она и будет читаться. Правило одно
 * на весь язык — данные раньше членов, — а надёжный путь для того, кому нужен
 * гарантированный ответ, идёт через дескриптор: {@code Class.methods(Point)}.
 * <p>
 * У дескриптора типа ({@code Number}, {@code Array}) этих членов нет: справка о самом
 * типе лежит у него под ключом {@code info}, а всё остальное пространство имён отдано
 * членам описываемого типа — иначе {@code Function.name} значило бы сразу и «имя типа
 * Function», и «член name у функций».
 */
public final class ClassMembers {

    private ClassMembers() {
    }

    static MemberSet set() {
        return MemberSet.builder()
                .property("name", (receiver, context, span) -> StringValue.of(self(receiver).name()))
                .property("parent", (receiver, context, span) -> {
                    ClassValue parent = self(receiver).parentClass();
                    return parent == null ? NullValue.NULL : parent;
                })
                .snapshot("annotations", (receiver, context, span) ->
                        Introspection.annotations(self(receiver).annotations()))
                .snapshot("traits", (receiver, context, span) ->
                        Introspection.traits(self(receiver).traits()))
                .snapshot("methods", (receiver, context, span) ->
                        Introspection.names(self(receiver).methodNames()))
                .snapshot("properties", (receiver, context, span) ->
                        Introspection.names(self(receiver).propertyNames()))
                .snapshot("params", (receiver, context, span) ->
                        // Заголовок класса — это его поля: один список отвечает
                        // и на «чем создавать», и на «что внутри».
                        Introspection.params(self(receiver).signature()))
                .property("arity", (receiver, context, span) ->
                        Introspection.arity(self(receiver).arity()))
                // Метод и свойство — методы, а не свойства: у них есть аргумент.
                // Отдают описание, а не значение: несвязанного метода в языке нет,
                // а за свойством стоит код, который без экземпляра выполнять нечем.
                .method("method", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    String name = Args.of("Class.method", arguments, context, span)
                            .string(0, "имя метода");
                    Signature signature = self(receiver).methodSignature(name);
                    return signature == null
                            ? NullValue.NULL
                            : Introspection.method(name, signature,
                                    self(receiver).methodAnnotations(name));
                })
                .method("property", Arity.exactly(1), (receiver, context, arguments, span) -> {
                    Property property = self(receiver).property(
                            Args.of("Class.property", arguments, context, span)
                                    .string(0, "имя свойства"));
                    return property == null ? NullValue.NULL : Introspection.property(property);
                })
                .build();
    }

    private static ClassValue self(Value receiver) {
        return (ClassValue) receiver;
    }
}
