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

import java.util.ArrayList;
import java.util.List;

/**
 * Разбор JSON в значения языка.
 * <p>
 * <b>Отдаются обычные значения, а не обёртки.</b> Объект становится {@code object},
 * массив — {@code array}, строка — строкой. Поэтому {@code data.items[0].name},
 * {@code for (key in data)}, {@code len(data)} и печать работают сами собой, и автору
 * скрипта нечего изучать: разобранный JSON — такие же данные, как literal в коде.
 * Обёртка понадобилась бы только там, где данных больше, чем памяти, — это другой
 * разговор и другой класс.
 * <p>
 * Разбор свой, без зависимости: {@code wdl-stdlib} идёт по умолчанию, и тянуть
 * ради него чужую библиотеку значило бы навязать её всем, кто встраивает движок.
 * <p>
 * <b>Глубина ограничена.</b> Разбор рекурсивный, а JSON часто приходит снаружи —
 * из запроса, из файла, от чужого сервиса. Тысяча открывающих скобок не должна
 * ронять чужое приложение {@code StackOverflowError}: это ошибка данных, и отвечать
 * на неё надо ошибкой скрипта.
 */
final class JsonReader {

    /** Предел вложенности: та же причина, что у предела вложенности вызовов в ядре. */
    private static final int MAX_DEPTH = 200;

    private final String text;
    private final Span span;
    private int position;

    private JsonReader(String text, Span span) {
        this.text = text;
        this.span = span;
    }

    /** Разбирает документ целиком: мусор после значения — ошибка. */
    static Value read(String text, Span span) {
        JsonReader reader = new JsonReader(text, span);
        reader.skipWhitespace();
        Value value = reader.value(0);
        reader.skipWhitespace();
        if (reader.position < text.length()) {
            throw reader.error("после значения остался лишний текст");
        }
        return value;
    }

    private Value value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("слишком глубокая вложенность: больше " + MAX_DEPTH + " уровней");
        }
        char c = peek();
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> StringValue.of(string());
            case 't' -> literal("true", BoolValue.TRUE);
            case 'f' -> literal("false", BoolValue.FALSE);
            case 'n' -> literal("null", NullValue.NULL);
            default -> number();
        };
    }

    private Value object(int depth) {
        position++;
        MapValue result = new MapValue();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("ключ объекта должен быть строкой");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(StringValue.of(key), value(depth + 1));
            skipWhitespace();
            char next = peek();
            position++;
            if (next == '}') {
                return result;
            }
            if (next != ',') {
                throw error("ожидались ',' или '}'");
            }
        }
    }

    private Value array(int depth) {
        position++;
        List<Value> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return ArrayValue.of(items);
        }
        while (true) {
            skipWhitespace();
            items.add(value(depth + 1));
            skipWhitespace();
            char next = peek();
            position++;
            if (next == ']') {
                return ArrayValue.of(items);
            }
            if (next != ',') {
                throw error("ожидались ',' или ']'");
            }
        }
    }

    private String string() {
        position++;
        StringBuilder result = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("строка не закрыта");
            }
            char c = text.charAt(position++);
            if (c == '"') {
                return result.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw error("управляющий символ внутри строки нужно экранировать");
                }
                result.append(c);
                continue;
            }
            if (position >= text.length()) {
                throw error("строка не закрыта");
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> result.append(unicode());
                default -> throw error("неизвестная escape-последовательность '\\" + escaped + "'");
            }
        }
    }

    private char unicode() {
        if (position + 4 > text.length()) {
            throw error("в \\u должно быть четыре шестнадцатеричные цифры");
        }
        String digits = text.substring(position, position + 4);
        try {
            char result = (char) Integer.parseInt(digits, 16);
            position += 4;
            return result;
        } catch (NumberFormatException e) {
            throw error("в \\u должно быть четыре шестнадцатеричные цифры, а здесь '" + digits + "'");
        }
    }

    /**
     * Число.
     * <p>
     * Целое остаётся целым — правило языка, а не JSON: {@code 1} должно быть тем же
     * {@code 1}, что и в коде скрипта, иначе счётчики и идентификаторы начнут
     * печататься с точкой. Не влезающее в {@code long} становится вещественным:
     * потерю точности видно, а заворот разрядов — нет.
     */
    private Value number() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        boolean fractional = false;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c >= '0' && c <= '9') {
                position++;
                continue;
            }
            if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fractional = true;
                position++;
                continue;
            }
            break;
        }
        String literal = text.substring(start, position);
        if (literal.isEmpty() || literal.equals("-")) {
            throw errorAt(start, "ожидалось значение");
        }
        try {
            if (!fractional) {
                try {
                    return IntValue.of(Long.parseLong(literal));
                } catch (NumberFormatException overflow) {
                    return FloatValue.of(Double.parseDouble(literal));
                }
            }
            return FloatValue.of(Double.parseDouble(literal));
        } catch (NumberFormatException e) {
            throw errorAt(start, "'" + literal + "' не похоже на число");
        }
    }

    private Value literal(String word, Value result) {
        if (!text.startsWith(word, position)) {
            throw error("ожидалось значение");
        }
        position += word.length();
        return result;
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("текст кончился раньше, чем значение");
        }
        return text.charAt(position);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw error("ожидался '" + expected + "'");
        }
        position++;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                position++;
                continue;
            }
            return;
        }
    }

    private WdlRuntimeError error(String what) {
        return errorAt(position, what);
    }

    /**
     * Ошибка со строкой и столбцом <b>внутри JSON</b>, а не в скрипте.
     * <p>
     * Место в скрипте покажет движок — это {@code span} вызова {@code parse}. Но
     * искать опечатку автору придётся в данных, поэтому координата внутри документа
     * важнее всего остального в этом сообщении.
     */
    private WdlRuntimeError errorAt(int at, String what) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < Math.min(at, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new WdlRuntimeError(span, "json: " + what + " (строка " + line
                + ", столбец " + column + ")");
    }
}
