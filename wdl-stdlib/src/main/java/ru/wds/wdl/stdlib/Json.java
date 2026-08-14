package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.Args;
import ru.wds.wdl.embed.Library;
import ru.wds.wdl.embed.NativeTrait;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
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
 *
 * class Point(x, y) with json.Serializable {           // свой класс — тоже данные
 *     def toJson() => {x: x, y: y}
 * }
 * println(json.stringify(new Point(1, 2)))             // {"x":1,"y":2}
 * }</pre>
 *
 * Разобрать, записать и один трейт: всё остальное — обычная работа с объектами
 * и массивами, которую язык уже умеет, и заводить ради неё свой API значило бы
 * учить автора скрипта второму способу делать то же самое.
 * <p>
 * <b>{@code Serializable} — это контракт, а не удобство.</b> Экземпляр класса и так
 * пишется как объект, поле в поле; трейт нужен там, где записать надо <b>не то,
 * что лежит</b>: дату строкой, деньги числом, приватное поле не писать вовсе.
 * Требование одно — {@code def toJson()}, — и проверяется оно на строке
 * {@code class}, как у любого трейта.
 */
public final class Json implements Library {

    /** Предел отступа: {@code stringify(value, 100)} — почти наверняка опечатка. */
    private static final int MAX_INDENT = 10;

    /** Имя метода, который трейт требует, — он же способ превратить объект в данные. */
    private static final String TO_JSON = "toJson";

    /**
     * Трейт этого запуска: {@code is} сравнивает по ссылке, поэтому собирается он
     * вместе с модулем, как и классы.
     */
    private NativeTrait serializable;

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

        serializable = NativeTrait.named("Serializable")
                .requireMethod(TO_JSON, Arity.exactly(0))
                .build();
        scope.define(serializable.name(), serializable);

        scope.define("parse", BuiltinFunction.of("parse", Arity.exactly(1),
                (context, arguments, span) ->
                        JsonReader.read(arguments.string(0, "текст"), span)));

        scope.define("stringify", BuiltinFunction.of("stringify", Arity.between(1, 2),
                (context, arguments, span) -> StringValue.of(JsonWriter.write(arguments.at(0),
                        indent(arguments), span, value -> toData(value, context, span)))));

        return scope;
    }

    /**
     * Превращает объект в данные, если его класс обещал это делать.
     * <p>
     * Спрашивается у класса, а не у объекта: {@code toJson} — обычный метод, и метод
     * без обещания трейта звать не за что. Иначе любое поле с таким именем меняло бы
     * поведение записи, а это ровно та неявность, которой трейт и не даёт случиться.
     */
    private Value toData(Value value, CallContext context, Span span) {
        if (value instanceof InstanceObjectValue instance
                && instance.owner().conformsTo(serializable)) {
            return instance.owner().method(instance, TO_JSON).call(context, List.of(), span);
        }
        return value;
    }

    /**
     * Отступ: сколько пробелов на уровень вложенности.
     * <p>
     * По умолчанию ноль — то есть одна строка без пробелов. Так JSON чаще всего
     * и отправляют; читаемый вид просят явно, вторым аргументом.
     */
    private static int indent(Args arguments) {
        long indent = arguments.integer(1, "отступ", 0);
        if (indent < 0 || indent > MAX_INDENT) {
            throw arguments.bad(1, "отступ", "ожидалось число от 0 до " + MAX_INDENT);
        }
        return (int) indent;
    }
}
