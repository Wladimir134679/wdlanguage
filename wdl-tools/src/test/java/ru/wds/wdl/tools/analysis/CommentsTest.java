package ru.wds.wdl.tools.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Привязка комментариев к объявлениям — источник подсказки по наведению. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CommentsTest {

    private static Symbol symbol(String code, String name) {
        return FileAnalysis.of(Source.ofString(code)).symbols().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("нет символа '" + name + "'"));
    }

    @Test
    @DisplayName("комментарий над объявлением становится его документацией")
    void lineComment() {
        Symbol symbol = symbol("""
                // Считает стоимость заказа.
                def total(price, count) => price * count
                """, "total");

        assertEquals("Считает стоимость заказа.", symbol.documentation());
        assertTrue(symbol.hasDocumentation());
    }

    @Test
    @DisplayName("несколько строк подряд склеиваются в одну справку")
    void severalLines() {
        Symbol symbol = symbol("""
                // Стоимость заказа.
                // Скидка сюда не входит.
                def total(price) => price
                """, "total");

        assertEquals("Стоимость заказа.\nСкидка сюда не входит.", symbol.documentation());
    }

    @Test
    @DisplayName("блочный комментарий теряет маркеры и звёздочки")
    void blockComment() {
        Symbol symbol = symbol("""
                /*
                 * Стоимость заказа.
                 * Считается без скидки.
                 */
                def total(price) => price
                """, "total");

        assertEquals("Стоимость заказа.\nСчитается без скидки.", symbol.documentation());
    }

    @Test
    @DisplayName("пустая строка разрывает связь комментария с объявлением")
    void blankLineBreaksTheLink() {
        Symbol symbol = symbol("""
                // Просто заметка посреди файла.

                def total(price) => price
                """, "total");

        assertNull(symbol.documentation(), "через пустую строку комментарий уже ничей");
        assertFalse(symbol.hasDocumentation());
    }

    @Test
    @DisplayName("комментарий в конце строки объявления к нему не относится")
    void trailingCommentIsNotDocumentation() {
        Symbol symbol = symbol("""
                price = 120   // цена одной штуки
                count = 2
                """, "count");

        assertNull(symbol.documentation(), "справа от чужого объявления — не документация");
    }

    @Test
    @DisplayName("документацию получают и члены типа")
    void classMembers() {
        String code = """
                class Rect(width, height) {
                    // Площадь прямоугольника.
                    def area() => width * height
                }
                """;

        assertEquals("Площадь прямоугольника.", symbol(code, "area").documentation());
        assertNull(symbol(code, "width").documentation());
    }

    @Test
    @DisplayName("комментарии файла перечисляются по интервалам")
    void allComments() {
        Comments comments = Comments.of(Source.ofString("""
                // первый
                price = 1 /* второй */
                """));

        assertEquals(2, comments.all().size());
    }
}
