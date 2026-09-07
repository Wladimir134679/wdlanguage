package ru.wds.wdl.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.game.GameScripts.errorOf;
import static ru.wds.wdl.game.GameScripts.printed;

/**
 * Модуль {@code game} глазами скрипта.
 * <p>
 * Модуль — словарь, а не пульт: игру он не создаёт и не запускает. Спрайты
 * и холст приходят в хуки от приложения, поэтому здесь проверяется ровно то,
 * что модуль даёт сам, — типы и две вспомогательные функции.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GameTest {

    @Test
    @DisplayName("Модуль импортируется и приносит два типа")
    void moduleImports() {
        assertEquals("module game class Sprite() class Canvas()", printed("""
                import game as g
                println(g)
                println(g.Sprite)
                println(g.Canvas)
                """));
    }

    @Test
    @DisplayName("Цвет собирается по составляющим")
    void rgb() {
        assertEquals("#7ec8ff #000000 #ffffff", printed("""
                import game as g
                println(g.rgb(126, 200, 255), " ", g.rgb(0, 0, 0), " ", g.rgb(255, 255, 255))
                """));
    }

    @Test
    @DisplayName("clamp загоняет значение в границы, не портя целое")
    void clamp() {
        assertEquals("0 10 5 2.5", printed("""
                import game as g
                println(g.clamp(-3, 0, 10), " ", g.clamp(15, 0, 10), " ",
                        g.clamp(5, 0, 10), " ", g.clamp(2.5, 0, 10))
                """));
    }

    @Test
    @DisplayName("Перевёрнутые границы clamp — ошибка, а не молчаливый ответ")
    void clampRefusesInvertedBounds() {
        assertTrue(errorOf("""
                import game as g
                g.clamp(5, 10, 0)
                """).getMessage().contains("минимум"));
    }
}
