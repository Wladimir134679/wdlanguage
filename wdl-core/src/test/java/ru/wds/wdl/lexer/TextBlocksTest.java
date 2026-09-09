package ru.wds.wdl.lexer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правило отступа многострочной строки — само по себе, без лексера.
 * <p>
 * Отдельный тест потому, что ошибиться здесь легко и незаметно: значение получится
 * похожим на правду, а разойдётся оно на пустой строке, на табуляции или на строке
 * с закрывающими кавычками — то есть там, куда в тесте лексера не заглядывают.
 */
class TextBlocksTest {

    /**
     * Строки со смещениями, как их отдаёт лексер: смещение растёт на длину и перевод.
     * <p>
     * Последняя строка в примерах — та, на которой стоят закрывающие кавычки; она же
     * даёт перевод строки в конце значения.
     */
    private static List<TextBlocks.Line> lines(String... values) {
        List<TextBlocks.Line> result = new java.util.ArrayList<>(values.length);
        int offset = 0;
        for (String value : values) {
            result.add(new TextBlocks.Line(value, offset));
            offset += value.length() + 1;
        }
        return result;
    }

    private static String value(String... values) {
        List<TextBlocks.Line> stripped = TextBlocks.strip(lines(values), TextBlocks.indent(lines(values)));
        return stripped.stream().map(TextBlocks.Line::text).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    @Test
    @DisplayName("общий отступ снимается со всех строк сразу")
    void commonIndent() {
        assertEquals("привет\nмир\n", value("    привет", "    мир", "    "));
    }

    @Test
    @DisplayName("вложенность внутри текста сохраняется: снимается только общий отступ")
    void keepsRelativeIndent() {
        assertEquals("attack\n  defend\n", value("    attack", "      defend", "    "));
    }

    @Test
    @DisplayName("строка с закрывающими кавычками задаёт левый край")
    void closingLineSetsMargin() {
        // Кавычки сдвинуты левее текста — значит два пробела остаются в значении.
        assertEquals("  привет\n", value("    привет", "  "));
        // И правее: край задан ими, весь текст прижимается.
        assertEquals("привет\n", value("    привет", "    "));
    }

    @Test
    @DisplayName("пустые строки в счёте отступа не участвуют")
    void blankLinesIgnored() {
        assertEquals("первая\n\nвторая\n", value("    первая", "", "    вторая", "    "));
    }

    @Test
    @DisplayName("хвостовые пробелы срезаются в каждой строке")
    void trailingSpacesCut() {
        assertEquals("привет\nмир\n", value("    привет   ", "    мир\t", "    "));
    }

    @Test
    @DisplayName("смещение строки сдвигается ровно на снятый отступ")
    void offsetsFollowTheCut() {
        List<TextBlocks.Line> source = lines("    привет", "    ");
        List<TextBlocks.Line> stripped = TextBlocks.strip(source, TextBlocks.indent(source));

        assertEquals(4, stripped.get(0).offset());
        assertEquals(4 + "привет".length(), stripped.get(0).end());
    }

    @Test
    @DisplayName("смесь пробелов и табуляций в отступе видна")
    void mixedIndentation() {
        assertTrue(TextBlocks.mixedIndentation(lines("    пробелы", "\tтабуляция", "")));
        assertFalse(TextBlocks.mixedIndentation(lines("    первая", "    вторая", "")));
        // Табуляция внутри строки отступом не считается: смеси здесь нет.
        assertFalse(TextBlocks.mixedIndentation(lines("    ключ\tзначение", "    ")));
    }
}
