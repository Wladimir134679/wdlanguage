package ru.wds.wdl.game.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Цвет и клавиша: две таблицы, через которые скрипт разговаривает с движком строками.
 */
class ColorsTest {

    @Test
    @DisplayName("Цвет читается в четырёх видах: #rrggbb, #rgb, #aarrggbb и по имени")
    void colorFormats() {
        assertEquals(new Color(0x7E, 0xC8, 0xFF), Colors.of("#7ec8ff"));
        assertEquals(new Color(0xFF, 0x00, 0xCC), Colors.of("#f0c"), "короткая запись удваивает цифры");
        assertEquals(new Color(0xFF, 0x00, 0x00, 0x80), Colors.of("#80ff0000"),
                "первая пара — прозрачность");
        assertEquals(Color.WHITE, Colors.of("white"));
        assertEquals(Color.WHITE, Colors.of("  WHITE "), "регистр и пробелы не важны");
    }

    @Test
    @DisplayName("Разобранный цвет не разбирается второй раз")
    void colorIsCached() {
        assertSame(Colors.of("#123456"), Colors.of("#123456"));
    }

    @Test
    @DisplayName("Непонятный цвет — отказ, а не чёрный по умолчанию")
    void wrongColorIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Colors.of("бирюзовый"));
        assertThrows(IllegalArgumentException.class, () -> Colors.of("#12345"));
        assertThrows(IllegalArgumentException.class, () -> Colors.of("#zzzzzz"));
        assertThrows(IllegalArgumentException.class, () -> Colors.of(null));
    }

    @Test
    @DisplayName("Составляющие собираются в строку и обрезаются по границам")
    void hexFromComponents() {
        assertEquals("#7ec8ff", Colors.hex(126, 200, 255));
        assertEquals("#ff0000", Colors.hex(300, -20, 0), "значения вне 0..255 прижимаются к границе");
    }

    @Test
    @DisplayName("Имя клавиши переводится в код и обратно")
    void keyNamesRoundTrip() {
        assertEquals(KeyEvent.VK_LEFT, Keys.codeOf("left"));
        assertEquals(KeyEvent.VK_W, Keys.codeOf("W"), "регистр не важен");
        assertEquals(KeyEvent.VK_F5, Keys.codeOf("f5"));
        assertEquals(KeyEvent.VK_7, Keys.codeOf("7"));

        assertEquals("left", Keys.nameOf(KeyEvent.VK_LEFT));
        assertEquals("space", Keys.nameOf(KeyEvent.VK_SPACE));
        assertEquals("w", Keys.nameOf(KeyEvent.VK_W));
    }

    @Test
    @DisplayName("Опечатка в имени клавиши — ошибка, а не вечное «не нажата»")
    void unknownKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Keys.codeOf("lft"));
        assertThrows(IllegalArgumentException.class, () -> Keys.codeOf(null));
    }

    @Test
    @DisplayName("Клавише вне таблицы имя всё равно находится")
    void unlistedKeyStillHasName() {
        assertTrue(Keys.nameOf(KeyEvent.VK_SEMICOLON).length() > 0);
        assertTrue(Keys.names().contains("escape"));
    }
}
