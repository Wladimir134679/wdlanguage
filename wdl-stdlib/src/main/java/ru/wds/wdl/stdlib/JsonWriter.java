package ru.wds.wdl.stdlib;

import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Запись значений языка в JSON.
 * <p>
 * Пишутся данные и только данные: {@code null}, логическое, число, строка, массив
 * и объект. Функция, класс, трейт и модуль — не данные, и молча превращать их
 * в {@code null} нельзя: скрипт, который отправил на сервер объект с потерянным
 * полем, узнает об этом далеко от места ошибки.
 * <p>
 * <b>Экземпляр класса пишется как объект.</b> Он и есть карта пар со ссылкой
 * на класс, а раз обращение к нему в языке одно на все виды контейнеров, то и здесь
 * особого случая не нужно: имя класса в JSON не попадает, потому что в JSON
 * такого понятия нет.
 */
final class JsonWriter {

    private final StringBuilder out = new StringBuilder();
    /** Что сейчас пишется: по этому набору и виден круг. */
    private final Map<Value, Boolean> writing = new IdentityHashMap<>();
    private final String indent;
    private final Span span;

    private JsonWriter(String indent, Span span) {
        this.indent = indent;
        this.span = span;
    }

    /**
     * @param indent сколько пробелов на уровень; {@code 0} — писать в одну строку
     */
    static String write(Value value, int indent, Span span) {
        JsonWriter writer = new JsonWriter(" ".repeat(indent), span);
        writer.value(value, 0);
        return writer.out.toString();
    }

    private void value(Value value, int depth) {
        switch (value) {
            case NullValue ignored -> out.append("null");
            case BoolValue bool -> out.append(bool.value() ? "true" : "false");
            case IntValue number -> out.append(number.value());
            case FloatValue number -> number(number);
            case StringValue string -> string(string.value());
            case ArrayValue array -> array(array, depth);
            case MapValue object -> object(object, depth);
            default -> throw new WdlRuntimeError(span, "json: значение типа "
                    + value.type().title() + " записать нельзя (" + value + ")");
        }
    }

    /**
     * Вещественное число.
     * <p>
     * {@code NaN} и бесконечности в JSON не существует — это не ограничение
     * реализации, а формат. Записать их «как-нибудь» значило бы отправить наружу
     * то, что не прочитает никто.
     */
    private void number(FloatValue number) {
        double raw = number.value();
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            throw new WdlRuntimeError(span, "json: " + number
                    + " записать нельзя — в JSON нет ни бесконечности, ни NaN");
        }
        // display() уже печатает целое вещественное как '2.0', а не '2': менять
        // представление числа при сериализации не наше дело.
        out.append(number.display());
    }

    private void array(ArrayValue array, int depth) {
        enter(array);
        if (array.isEmpty()) {
            out.append("[]");
            leave(array);
            return;
        }
        out.append('[');
        boolean first = true;
        for (Value item : array.items()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(depth + 1);
            value(item, depth + 1);
        }
        newline(depth);
        out.append(']');
        leave(array);
    }

    private void object(MapValue object, int depth) {
        enter(object);
        if (object.isEmpty()) {
            out.append("{}");
            leave(object);
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<Value, Value> entry : object.entries().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(depth + 1);
            string(key(entry.getKey()));
            out.append(':');
            if (!indent.isEmpty()) {
                out.append(' ');
            }
            value(entry.getValue(), depth + 1);
        }
        newline(depth);
        out.append('}');
        leave(object);
    }

    /**
     * Ключ объекта.
     * <p>
     * В языке ключом бывает любое значение, в JSON — только строка. Число и логическое
     * записываются своим текстом ({@code {1: "a"}} → {@code {"1": "a"}}): это
     * привычно и обратимо на глаз. Всё остальное — ошибка, потому что «ключ-функция»
     * восстановить из документа невозможно.
     */
    private String key(Value key) {
        return switch (key) {
            case StringValue string -> string.value();
            case IntValue number -> String.valueOf(number.value());
            case FloatValue number -> number.display();
            case BoolValue bool -> bool.display();
            default -> throw new WdlRuntimeError(span, "json: ключом объекта может быть"
                    + " строка, число или логическое, а здесь " + key.type().title()
                    + " (" + key + ")");
        };
    }

    private void enter(Value container) {
        if (writing.put(container, Boolean.TRUE) != null) {
            throw new WdlRuntimeError(span, "json: значение ссылается само на себя,"
                    + " а в JSON циклов не бывает");
        }
    }

    private void leave(Value container) {
        writing.remove(container);
    }

    private void newline(int depth) {
        if (indent.isEmpty()) {
            return;
        }
        out.append(System.lineSeparator());
        out.append(indent.repeat(depth));
    }

    private void string(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
