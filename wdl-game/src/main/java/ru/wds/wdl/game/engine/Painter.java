package ru.wds.wdl.game.engine;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Холст кадра: то, чем рисуют — движок сам и скрипт в своём обработчике.
 * <p>
 * Живёт ровно один кадр в том смысле, что за пределами обработчика рисовать
 * бессмысленно: следующий кадр начинается с заливки фоном, и нарисованное
 * до него исчезнет. Объект при этом переиспользуется — новое перо приходит
 * в {@link #begin}, а холст у мира один.
 *
 * <h2>Попиксельно — это правда попиксельно</h2>
 * {@link #pixel} пишет прямо в {@link BufferedImage#setRGB}, а не рисует
 * прямоугольник 1×1. Разница видна и в скорости, и в результате: сглаживание
 * и текущее перо на точку не влияют, а именно этого ждут от «поставить пиксель».
 */
public final class Painter {

    private final BufferedImage image;
    private final int width;
    private final int height;
    private Graphics2D graphics;
    private Color current = Color.WHITE;

    Painter(BufferedImage image) {
        this.image = image;
        this.width = image.getWidth();
        this.height = image.getHeight();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Начало кадра: холст получает перо, которым будет рисовать. */
    void begin(Graphics2D newGraphics) {
        this.graphics = newGraphics;
        this.graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        this.graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        this.graphics.setColor(current);
    }

    /**
     * Конец кадра: перо отдано обратно движку.
     * <p>
     * Без этого холст держал бы {@code Graphics2D}, который уже освободили, и
     * рисование из скрипта <b>после</b> обработчика молча уходило бы в никуда
     * вместо понятного отказа.
     */
    void end() {
        this.graphics = null;
    }

    /** Цвет по умолчанию: им рисует всё, что позвали без своего цвета. */
    public void color(String color) {
        this.current = Colors.of(color);
        if (graphics != null) {
            graphics.setColor(current);
        }
    }

    public void clear(String color) {
        Graphics2D g = require();
        g.setColor(color == null ? current : Colors.of(color));
        g.fillRect(0, 0, width, height);
        g.setColor(current);
    }

    /**
     * Точка. Координаты за краем холста молча отбрасываются: рисование по циклу
     * почти всегда выходит за границу на краях, и падать на этом — значит
     * заставить скрипт проверять то, что движок уже знает.
     */
    public void pixel(double x, double y, String color) {
        int px = (int) Math.round(x);
        int py = (int) Math.round(y);
        if (px < 0 || py < 0 || px >= width || py >= height) {
            return;
        }
        image.setRGB(px, py, (color == null ? current : Colors.of(color)).getRGB());
    }

    public void rect(double x, double y, double w, double h, String color) {
        Graphics2D g = require();
        g.setColor(color == null ? current : Colors.of(color));
        g.fillRect(round(x), round(y), round(w), round(h));
        g.setColor(current);
    }

    public void frame(double x, double y, double w, double h, String color, double thickness) {
        Graphics2D g = require();
        g.setColor(color == null ? current : Colors.of(color));
        g.setStroke(new BasicStroke((float) Math.max(1, thickness)));
        g.drawRect(round(x), round(y), round(w), round(h));
        g.setColor(current);
    }

    public void oval(double x, double y, double w, double h, String color) {
        Graphics2D g = require();
        g.setColor(color == null ? current : Colors.of(color));
        g.fillOval(round(x), round(y), round(w), round(h));
        g.setColor(current);
    }

    public void line(double x1, double y1, double x2, double y2, String color, double thickness) {
        Graphics2D g = require();
        g.setColor(color == null ? current : Colors.of(color));
        g.setStroke(new BasicStroke((float) Math.max(1, thickness)));
        g.drawLine(round(x1), round(y1), round(x2), round(y2));
        g.setColor(current);
    }

    public void text(double x, double y, String text, double size, String color) {
        Graphics2D g = require();
        g.setColor(color == null ? current : Colors.of(color));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.max(1, Math.round(size))));
        g.drawString(text, round(x), round(y));
        g.setColor(current);
    }

    /** Ширина строки в пикселях: без неё текст не поставить по центру. */
    public int textWidth(String text, double size) {
        Graphics2D g = require();
        return g.getFontMetrics(new Font(Font.SANS_SERIF, Font.BOLD,
                (int) Math.max(1, Math.round(size)))).stringWidth(text);
    }

    /** Рисует спрайт — то, что движок делает сам перед обработчиком скрипта. */
    void draw(Sprite sprite) {
        if (!sprite.visible()) {
            return;
        }
        switch (sprite.shape()) {
            case RECT -> rect(sprite.x(), sprite.y(), sprite.width(), sprite.height(), sprite.color());
            case OVAL -> oval(sprite.x(), sprite.y(), sprite.width(), sprite.height(), sprite.color());
        }
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    private Graphics2D require() {
        if (graphics == null) {
            throw new IllegalStateException("холст доступен только внутри кадра: "
                    + "рисуйте в обработчике onDraw");
        }
        return graphics;
    }

    @Override
    public String toString() {
        return "canvas " + width + "x" + height;
    }
}
