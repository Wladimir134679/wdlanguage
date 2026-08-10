package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.Library;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;
import java.util.Objects;

/**
 * Модуль {@code sys.json}: JSON в значения языка и обратно.
 *
 * <pre>{@code
 * import sys.json as json
 *
 * data = json.parse("{\"name\": \"Аня\", \"tags\": [1, 2]}")
 * println(data.name, " ", data.tags[0])
 *
 * println(json.stringify({id: 7, tags: [1, 2]}))       // {"id":7,"tags":[1,2]}
 * println(json.stringify(data, 2))                     // с отступами
 * }</pre>
 *
 * Два имени и ничего больше: разобрать и записать. Всё остальное — обычная работа
 * с объектами и массивами, которую язык уже умеет, и заводить ради неё свой API
 * значило бы учить автора скрипта второму способу делать то же самое.
 */
public final class Json implements Library {

    /** Предел отступа: {@code stringify(value, 100)} — почти наверняка опечатка. */
    private static final int MAX_INDENT = 10;

    private Json() {
    }

    /** Фабрика для реестра встроенных модулей. */
    public static Library library() {
        return new Json();
    }

    @Override
    public String name() {
        return "sys/json";
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");

        scope.define("parse", BuiltinFunction.of("parse", Arity.exactly(1),
                (context, arguments, span) ->
                        JsonReader.read(Std.text(arguments.get(0), span, "json.parse(): текст"), span)));

        scope.define("stringify", BuiltinFunction.of("stringify", Arity.between(1, 2),
                (context, arguments, span) ->
                        StringValue.of(JsonWriter.write(arguments.get(0), indent(arguments, span), span))));

        return scope;
    }

    /**
     * Отступ: сколько пробелов на уровень вложенности.
     * <p>
     * По умолчанию ноль — то есть одна строка без пробелов. Так JSON чаще всего
     * и отправляют; читаемый вид просят явно, вторым аргументом.
     */
    private static int indent(List<Value> arguments, Span span) {
        if (arguments.size() < 2) {
            return 0;
        }
        Value given = arguments.get(1);
        if (!(given instanceof NumberValue number) || !number.isInteger()) {
            throw new WdlRuntimeError(span, "json.stringify(): отступ должен быть целым числом,"
                    + " а здесь " + given.type().title() + " (" + given + ")");
        }
        long indent = number.asLong();
        if (indent < 0 || indent > MAX_INDENT) {
            throw new WdlRuntimeError(span, "json.stringify(): отступ должен быть от 0 до "
                    + MAX_INDENT + ", а здесь " + indent);
        }
        return (int) indent;
    }
}
