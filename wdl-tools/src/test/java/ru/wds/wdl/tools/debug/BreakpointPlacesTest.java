package ru.wds.wdl.tools.debug;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Привязка точки останова к инструкции: что редактор показал строкой, движок
 * получает смещением.
 * <p>
 * Проверяется поведение, которое человек видит глазами: точка, поставленная мимо
 * инструкции, <b>уезжает вниз</b>, а не пропадает и не остаётся висеть там, где
 * останова не будет.
 */
class BreakpointPlacesTest {

    private record Fixture(Program program, Source source) {

        BreakpointPlaces.Place at(int line) {
            return BreakpointPlaces.at(program, source, line);
        }

        int offsetOfLine(int line) {
            return source.offsetOf(line, 1);
        }
    }

    private static Fixture parse(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> diagnostics.renderAll());
        return new Fixture(program, source);
    }

    @Test
    @DisplayName("Точка на инструкции встаёт на её начало")
    void exactLine() {
        Fixture fixture = parse("a = 1\nb = 2\nc = a + b\n");
        BreakpointPlaces.Place place = fixture.at(3);
        assertNotNull(place);
        assertEquals(3, place.line());
        assertEquals(1, place.column());
        assertEquals(fixture.offsetOfLine(3), place.offset());
    }

    @Test
    @DisplayName("Точка на комментарии и на пустой строке уезжает к следующей инструкции")
    void slidesDown() {
        Fixture fixture = parse("""
                a = 1

                // считаем сумму
                b = a + 1
                """);
        assertEquals(4, fixture.at(2).line(), "с пустой строки не уехала");
        assertEquals(4, fixture.at(3).line(), "с комментария не уехала");
    }

    @Test
    @DisplayName("Точка на заголовке функции уезжает в её тело")
    void slidesIntoBody() {
        Fixture fixture = parse("""
                x = 0
                def twice(v) {
                    return v * 2;
                }
                """);
        // Строка 2 — само объявление: остановиться на нём законно, оно выполняется.
        assertEquals(2, fixture.at(2).line());
        // Строка с открывающей скобкой тела отдельной инструкцией не считается,
        // поэтому точка на ней — это первая инструкция внутри.
        assertEquals(3, fixture.at(3).line());
    }

    @Test
    @DisplayName("Точка на закрывающей скобке уезжает к следующей инструкции файла")
    void slidesPastClosingBrace() {
        Fixture fixture = parse("""
                def twice(v) {
                    return v * 2;
                }
                answer = twice(4)
                """);
        assertEquals(4, fixture.at(3).line());
    }

    @Test
    @DisplayName("Ниже последней инструкции места нет — и это не ошибка")
    void nothingBelow() {
        Fixture fixture = parse("a = 1\n\n\n");
        assertNull(fixture.at(3), "нашлось место там, где инструкций больше нет");
    }

    @Test
    @DisplayName("Из инструкций одной строки берётся самая левая")
    void leftmostOnLine() {
        Fixture fixture = parse("if (true) { a = 1 }\n");
        BreakpointPlaces.Place place = fixture.at(1);
        assertEquals(1, place.line());
        assertEquals(1, place.column(), "точка встала не на 'if'");
    }

    @Test
    @DisplayName("Список строк отвечает по порядку, с пустотами на месте ненайденных")
    void keepsRequestOrder() {
        Fixture fixture = parse("a = 1\nb = 2\n\n");
        List<BreakpointPlaces.Place> places =
                BreakpointPlaces.at(fixture.program(), fixture.source(), Arrays.asList(2, 1, 9));
        assertEquals(3, places.size());
        assertEquals(2, places.get(0).line());
        assertEquals(1, places.get(1).line());
        assertNull(places.get(2), "для несуществующей строки вернулось место");
    }

    @Test
    @DisplayName("Все места идут по возрастанию смещения и не повторяются")
    void allSorted() {
        Fixture fixture = parse("""
                a = 1
                for (i in 1..3) {
                    a = a + i
                }
                """);
        List<BreakpointPlaces.Place> places =
                BreakpointPlaces.all(fixture.program(), fixture.source());
        assertTrue(places.size() >= 3, () -> "мест меньше, чем инструкций: " + places);
        for (int i = 1; i < places.size(); i++) {
            assertTrue(places.get(i - 1).offset() <= places.get(i).offset(),
                    () -> "порядок нарушен: " + places);
        }
    }
}
