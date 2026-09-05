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
    @DisplayName("позиция переводится обратно в смещение")
    void offsetOf() {
        Source source = Source.ofString(CODE);

        assertEquals(0, source.offsetOf(1, 1));
        assertEquals(4, source.offsetOf(1, 5));
        assertEquals(CODE.indexOf('b'), source.offsetOf(2, 1));
        assertEquals(CODE.indexOf("println"), source.offsetOf(3, 1));
        assertEquals(CODE.length(), source.offsetOf(new Position(3, "println(a + b)".length() + 1)));
    }

    @Test
    @DisplayName("перевод смещения в позицию и обратно ничего не теряет")
    void roundTrip() {
        assertRoundTrip(CODE);
        assertRoundTrip("");
        assertRoundTrip("x = 1");
        assertRoundTrip("x = 1\n");
        assertRoundTrip("a = 1\r\nb = 2\r\n");
        assertRoundTrip("count = 1 // счётчик\nname = \"мир\"\n");
        assertRoundTrip("emoji = \"😀\"\ntail = 1");
        assertRoundTrip("﻿x = 1\ny = 2");
        assertRoundTrip("\n\n\n");
    }

    @Test
    @DisplayName("столбец за концом строки — та позиция, где стоит курсор")
    void offsetAtLineEnd() {
        Source source = Source.ofString("ab\ncd");

        assertEquals(2, source.offsetOf(1, 3));
        assertEquals(new Position(1, 3), source.positionOf(2));
        assertEquals(5, source.offsetOf(2, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> source.offsetOf(1, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> source.offsetOf(2, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> source.offsetOf(3, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> source.offsetOf(1, 0));
    }

    @Test
    @DisplayName("символ вне BMP занимает две единицы UTF-16")
    void surrogatePair() {
        Source source = Source.ofString("s = \"😀\"");

        assertEquals(new Position(1, 6), source.positionOf(5));
        assertEquals(new Position(1, 8), source.positionOf(7));
        assertEquals(2, new Span(5, 7).length());
    }

    @Test
    @DisplayName("маркер кодировки — обычный символ позиции 0")
    void byteOrderMark() {
        Source source = Source.ofString("﻿x = 1");

        assertEquals(new Position(1, 1), source.positionOf(0));
        assertEquals(new Position(1, 2), source.positionOf(1));
        assertEquals("﻿x = 1", source.lineText(1));
    }

    private static void assertRoundTrip(String text) {
        Source source = Source.ofString(text);
        for (int offset = 0; offset <= text.length(); offset++) {
            assertEquals(offset, source.offsetOf(source.positionOf(offset)),
                    "смещение " + offset + " в тексте " + text.replace("\n", "\\n"));
        }
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
