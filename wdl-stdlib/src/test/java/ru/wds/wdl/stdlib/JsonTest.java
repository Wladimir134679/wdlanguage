package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.stdlib.Scripts.errorOf;
import static ru.wds.wdl.stdlib.Scripts.printed;

/**
 * Модуль {@code sys.json}.
 * <p>
 * Главное здесь — что разобранный JSON это обычные данные языка: к нему применимо
 * всё, что применимо к объекту и массиву, и никакого второго API для этого не нужно.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JsonTest {

    @Test
    @DisplayName("разобранный объект — обычный объект: точка, индекс, перебор, len")
    void parsedIsOrdinaryData() {
        assertEquals("Аня 2 1 object", printed("""
                import sys.json as json
                data = json.parse("{\\"name\\": \\"Аня\\", \\"tags\\": [1, 2]}")
                println(data.name, " ", len(data), " ", data["tags"][0], " ", typeof(data))
                """));
    }

    @Test
    @DisplayName("типы: null, логическое, целое, вещественное, строка")
    void types() {
        assertEquals("null true 7 1.5 текст", printed("""
                import sys.json as json
                v = json.parse("[null, true, 7, 1.5, \\"текст\\"]")
                println(v[0], " ", v[1], " ", v[2], " ", v[3], " ", v[4])
                """));
    }

    @Test
    @DisplayName("целое остаётся целым, как и в арифметике языка")
    void integersStayIntegers() {
        assertEquals("7 number true", printed("""
                import sys.json as json
                n = json.parse("7")
                println(n, " ", typeof(n), " ", n == 7)
                """));
    }

    @Test
    @DisplayName("экранирование при разборе: кавычки, перевод строки, \\u")
    void escapes() {
        // У второй строки проверяется длина: '\n' обязан стать одним символом,
        // а не двумя, и печать этого не показала бы.
        assertEquals("a\"b|3|Ж", printed("""
                import sys.json as json
                v = json.parse("[\\"a\\\\\\"b\\", \\"1\\\\n2\\", \\"\\\\u0416\\"]")
                println(v[0], "|", len(v[1]), "|", v[2])
                """));
    }

    @Test
    @DisplayName("запись: объект, массив, вложенность — в одну строку")
    void writeCompact() {
        assertEquals("{\"id\":7,\"tags\":[1,2],\"who\":{\"name\":\"Аня\"}}", printed("""
                import sys.json as json
                println(json.stringify({id: 7, tags: [1, 2], who: {name: "Аня"}}))
                """));
    }

    @Test
    @DisplayName("запись с отступом — второй аргумент")
    void writePretty() {
        assertEquals("{   \"id\": 7,   \"tags\": [     1   ] }", printed("""
                import sys.json as json
                println(json.stringify({id: 7, tags: [1]}, 2))
                """));
    }

    @Test
    @DisplayName("туда и обратно: разобранное и записанное совпадает")
    void roundTrip() {
        assertEquals("true", printed("""
                import sys.json as json
                text = "{\\"a\\":[1,2,{\\"b\\":null}],\\"c\\":\\"да\\"}"
                println(json.stringify(json.parse(text)) == text)
                """));
    }

    @Test
    @DisplayName("экземпляр класса записывается как объект: он и есть набор полей")
    void instanceIsObject() {
        assertEquals("{\"x\":1,\"y\":2}", printed("""
                import sys.json as json
                class Point(x, y)
                println(json.stringify(new Point(1, 2)))
                """));
    }

    @Test
    @DisplayName("строки экранируются при записи")
    void writeEscapes() {
        assertEquals("{\"say\":\"он сказал \\\"да\\\"\\nи ушёл\"}", printed("""
                import sys.json as json
                println(json.stringify({say: "он сказал \\"да\\"\\nи ушёл"}))
                """));
    }

    // --- ошибки ---------------------------------------------------------------

    @Test
    @DisplayName("сломанный JSON: ошибка со строкой и столбцом внутри документа")
    void brokenJson() {
        String message = errorOf("""
                import sys.json as json
                json.parse("{\\"a\\": }")
                """).getMessage();
        assertTrue(message.startsWith("json: "), message);
        assertTrue(message.contains("строка 1, столбец 7"), message);
    }

    @Test
    @DisplayName("лишний текст после значения — тоже ошибка")
    void trailingText() {
        assertTrue(errorOf("""
                import sys.json as json
                json.parse("{} лишнее")
                """).getMessage().contains("лишний текст"));
    }

    @Test
    @DisplayName("функцию записать нельзя — молча потерять поле хуже, чем упасть")
    void functionsRejected() {
        assertTrue(errorOf("""
                import sys.json as json
                json.stringify({run: def() => 1})
                """).getMessage().contains("функция"));
    }

    @Test
    @DisplayName("цикл в данных — ошибка: в JSON циклов не бывает")
    void cyclesRejected() {
        assertTrue(errorOf("""
                import sys.json as json
                a = {}
                a.self = a
                json.stringify(a)
                """).getMessage().contains("ссылается само на себя"));
    }

    @Test
    @DisplayName("отступ проверяется: не число и не диапазон — ошибка с объяснением")
    void indentChecked() {
        assertTrue(errorOf("""
                import sys.json as json
                json.stringify({}, 99)
                """).getMessage().contains("от 0 до 10"));
    }

    @Test
    @DisplayName("слишком глубокая вложенность — ошибка скрипта, а не StackOverflowError")
    void deepNestingRejected() {
        assertTrue(errorOf("""
                import sys.json as json
                text = ""
                for (i = 0; i < 500; i = i + 1) { text = text + "[" }
                json.parse(text)
                """).getMessage().contains("слишком глубокая вложенность"));
    }
}
