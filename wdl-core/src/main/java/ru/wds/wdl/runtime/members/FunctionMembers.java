package ru.wds.wdl.runtime.members;

import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

/**
 * Члены функции — интроспекция, ради которой многое и затевалось: тестовый фреймворк
 * на самом wdl наконец может спросить у функции имена её параметров, хотя движок
 * знал их всегда.
 * <p>
 * Здесь одни свойства и ни одного метода, и это следствие правила, а не вкус: всё,
 * что функция рассказывает о себе, — метаданные, собранные при объявлении. Устареть
 * такой ответ не может.
 * <p>
 * <b>Оговорка, без которой обещание было бы ложным: декоратор подменяет значение.</b>
 * У обёртки своя сигнатура, и {@code total.params} после {@code @log} покажет
 * параметры обёртки. Переживают декоратор имена только там, где автор позвал
 * {@code like()} — она делегирует {@code signature()} цели. Это не недоделка
 * интроспекции, а свойство декоратора, и говорить о нём надо вслух.
 */
public final class FunctionMembers {

    private FunctionMembers() {
    }

    static MemberSet set() {
        return MemberSet.builder()
                .property("name", (receiver, context, span) -> {
                    String known = self(receiver).knownName();
                    // knownName() честно отвечает null у функции, имени которой
                    // взять неоткуда: подставлять "def" в данные нельзя — под таким
                    // ключом две разные функции затёрли бы друг друга.
                    return known == null ? NullValue.NULL : StringValue.of(known);
                })
                .property("anonymous", (receiver, context, span) ->
                        BoolValue.of(self(receiver).anonymous()))
                .property("params", (receiver, context, span) ->
                        Introspection.params(self(receiver).signature()))
                .property("arity", (receiver, context, span) ->
                        Introspection.arity(self(receiver).arity()))
                .property("rest", (receiver, context, span) -> {
                    String rest = self(receiver).signature().restName();
                    return rest == null ? NullValue.NULL : StringValue.of(rest);
                })
                .property("namedRest", (receiver, context, span) -> {
                    String rest = self(receiver).signature().namedRestName();
                    return rest == null ? NullValue.NULL : StringValue.of(rest);
                })
                .build();
    }

    private static FunctionValue self(Value receiver) {
        return (FunctionValue) receiver;
    }
}
