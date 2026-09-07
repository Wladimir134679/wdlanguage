package ru.wds.wdl.game.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Рисование проверяется по картинке, а не по вызовам.
 * <p>
 * Кадр рисуется в {@link BufferedImage} — в оперативную память, без единого окна,
 * — поэтому нарисованное можно просто прочитать обратно. Это то же самое, что
 * увидит игрок, и проверять его можно на сборке.
 */
class PainterTest {

    @Test
    @DisplayName("Заливка красит холст целиком, точка ставится в одну ячейку")
    void clearAndPixel() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Painter painter = frame(image);

        painter.clear("#000000");
        painter.pixel(3, 4, "#ff0000");

        assertEquals(Color.BLACK.getRGB(), image.getRGB(0, 0));
        assertEquals(Color.RED.getRGB(), image.getRGB(3, 4));
        assertEquals(Color.BLACK.getRGB(), image.getRGB(4, 4), "соседняя точка не тронута");
    }

    @Test
    @DisplayName("Точка за краем холста молча отбрасывается")
    void pixelOutsideIsDropped() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Painter painter = frame(image);
        painter.clear("#000000");

        painter.pixel(-1, 0, "#ff0000");
        painter.pixel(4, 0, "#ff0000");
        painter.pixel(0, 100, "#ff0000");

        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                assertEquals(Color.BLACK.getRGB(), image.getRGB(x, y));
            }
        }
    }

    @Test
    @DisplayName("Цвет по умолчанию действует, пока его не сменили")
    void defaultColorIsKept() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Painter painter = frame(image);

        painter.clear("#000000");
        painter.color("#00ff00");
        painter.rect(0, 0, 2, 2, null);
        painter.rect(4, 4, 2, 2, "#ff0000");
        painter.pixel(7, 7, null);

        assertEquals(Color.GREEN.getRGB(), image.getRGB(1, 1), "цвет холста");
        assertEquals(Color.RED.getRGB(), image.getRGB(5, 5), "свой цвет фигуры");
        assertEquals(Color.GREEN.getRGB(), image.getRGB(7, 7),
                "свой цвет действует на одну фигуру, а не насовсем");
    }

    @Test
    @DisplayName("Движок рисует спрайт сам, невидимый не рисуется")
    void spritesAreDrawn() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Painter painter = frame(image);
        Scene scene = new Scene(16, 16, "#000000");
        Sprite visible = scene.spawn("visible", 0, 0, 4, 4, "#ff0000");
        Sprite hidden = scene.spawn("hidden", 8, 8, 4, 4, "#00ff00");
        hidden.visible(false);

        painter.clear(scene.background());
        painter.draw(visible);
        painter.draw(hidden);

        assertEquals(Color.RED.getRGB(), image.getRGB(1, 1));
        assertEquals(Color.BLACK.getRGB(), image.getRGB(9, 9));
    }

    @Test
    @DisplayName("Вне кадра холст рисовать отказывается")
    void paintingOutsideFrameIsRefused() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Painter painter = new Painter(image);

        assertThrows(IllegalStateException.class, () -> painter.clear("#000000"));
    }

    /** Холст, которому выдано перо, — то же, что делает движок в начале кадра. */
    private static Painter frame(BufferedImage image) {
        Painter painter = new Painter(image);
        Graphics2D graphics = image.createGraphics();
        painter.begin(graphics);
        return painter;
    }
}
