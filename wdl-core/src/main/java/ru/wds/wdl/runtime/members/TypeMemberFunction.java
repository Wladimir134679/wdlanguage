package ru.wds.wdl.runtime.members;

import ru.wds.wdl.runtime.*;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Member;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.List;

/**
 * Член типа, прочитанный через дескриптор: {@code Array.size} — это функция
 * {@code (массив) -> число}.
 * <p>
 * <b>Ради чего.</b> У объекта, экземпляра и модуля данные перекрывают члены — общее
 * правило языка, — и коду, которому нужен гарантированный ответ, нужен путь в обход
 * данных. Он же оказывается ответом на вопрос «а как позвать член, если его имя занято
 * ключом»:
 * <pre>{@code
 * box = {size: "L"}
 * println(box.size)          // L — данные
 * println(Object.size(box))  // 1 — член типа
 * }</pre>
 * <p>
 * <b>Таблица одна, дескриптор — её проекция.</b> Здесь не заводится второй набор:
 * функция — обёртка над тем же {@link Member}, что отвечает на {@code a.size}.
 * Разойтись им поэтому нечем.
 * <p>
 * <b>Свойство становится функцией одного аргумента, метод — функцией {@code n + 1}.</b>
 * Получатель приходит первым, и его тип проверяется до тела: {@code Array.size("x")} —
 * внятный отказ, а не {@code ClassCastException} из середины ядра.
 */
public final class TypeMemberFunction {

    private TypeMemberFunction() {
    }

    /**
     * Функция для члена с этим именем или ошибка, если такого члена у типа нет.
     * <p>
     * Промах здесь — ошибка, а не {@code null}: дескриптор это справочник, и
     * {@code Array.sze} может быть только опечаткой.
     */
    public static Value of(TypeValue descriptor, String name, Span span, ExecutionContext context) {
        ValueType type = descriptor.valueType();
        Member member = BuiltinMembers.of(type).get(name);
        if (member == null) {
            member = context.run().members().added(type, name);
        }
        if (member == null) {
            List<String> known = context.run().members().allNames(type);
            String closest = Names.closestTo(name, known);
            throw new WdlRuntimeError(ErrorKind.NAME, span, "у типа '" + descriptor.name()
                    + "' нет члена '" + name + "'"
                    + (closest != null ? ". Похоже на '" + closest + "'" : "")
                    + ". Справка о самом типе — под ключом 'info'");
        }
        Member found = member;
        String callee = descriptor.name() + "." + name;
        Arity arity = Arity.between(found.arity().min() + 1,
                found.arity().max() == Integer.MAX_VALUE
                        ? Integer.MAX_VALUE
                        : found.arity().max() + 1);
        return BuiltinFunction.of(callee, arity, (callContext, arguments, callSpan) -> {
            Value receiver = arguments.get(0);
            if (receiver.type() != type) {
                throw new WdlRuntimeError(ErrorKind.TYPE, callSpan, callee + "(): первым аргументом идёт "
                        + type.title() + ", а здесь " + receiver.type().title() + " (" + receiver + ")");
            }
            Property property = found.property();
            if (property != null) {
                return property.read(receiver, callContext, callSpan);
            }
            return found.bind(receiver).call(callContext,
                    List.copyOf(arguments.subList(1, arguments.size())), callSpan);
        });
    }
}
