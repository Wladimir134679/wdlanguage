package ru.wds.wdl.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceTest {

    private static final String CODE = """
            a = 5
            b = 63.2
            println(a + b)""";

    @Test
    @DisplayName("смещение переводится в строку и столбец")
    void positionOf() {
        Source source = Source.ofString(CODE);

        assertEquals(new Position(1, 1), source.positionOf(0));
        assertEquals(new Position(1, 5), source.positionOf(4));
        assertEquals(new Position(2, 1), source.positionOf(CODE.indexOf('b')));
        assertEquals(new Position(3, 1), source.positionOf(CODE.indexOf("println")));
    }

    @Test
    @DisplayName("строка исходника достаётся без перевода строки")
    void lineText() {
        Source source = Source.ofString(CODE);

        assertEquals(3, source.lineCount());
        assertEquals("a = 5", source.lineText(1));
        assertEquals("b = 63.2", source.lineText(2));
        assertEquals("println(a + b)", source.lineText(3));
    }

    @Test
    @DisplayName("перевод строки в конце файла не создаёт лишнюю строку с текстом")
    void trailingNewline() {
        Source source = Source.ofString("x = 1\n");

        assertEquals(2, source.lineCount());
        assertEquals("x = 1", source.lineText(1));
        assertEquals("", source.lineText(2));
    }

    @Test
    @DisplayName("выход за границы текста — ошибка, а не молчаливый результат")
    void outOfBounds() {
        Source source = Source.ofString("x = 1");

        assertThrows(IndexOutOfBoundsException.class, () -> source.positionOf(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> source.positionOf(6));
        assertThrows(IndexOutOfBoundsException.class, () -> source.lineText(0));
    }

    @Test
    @DisplayName("объединение интервалов растягивает границы")
    void spanMerge() {
        assertEquals(new Span(2, 10), new Span(2, 4).to(new Span(7, 10)));
        assertEquals(new Span(1, 3), Span.NONE.to(new Span(1, 3)));
        assertEquals(new Span(1, 3), new Span(1, 3).to(Span.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Span(5, 2));
    }
}
