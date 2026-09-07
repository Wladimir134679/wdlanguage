package ru.wds.wdl.game.engine;

import java.awt.Color;
import java.util.Locale;
import java.util.Map;

/**
 * Цвет строкой: {@code "#7ec8ff"}, {@code "#fff"}, {@code "#80ff0000"} или имя.
 * <p>
 * Скрипт не должен знать про {@code java.awt.Color}, а заводить ради цвета
 * отдельный тип языка — это конструктор, свойства и печать там, где хватает
 * строки, которую видно в коде глазами. Строка ещё и переживает сохранение
 * в JSON, чего не умеет ни один объект.
 * <p>
 * Разбор кэшируется: цвет в игре пишется литералом и разбирается каждый кадр
 * для каждого спрайта — а строк этих на всю игру десяток.
 */
public final class Colors {

    /** Имена, которые узнаёт скрипт. Список короткий намеренно: остальное — код. */
    private static final Map<String, Color> NAMED = Map.ofEntries(
            Map.entry("black", Color.BLACK),
            Map.entry("white", Color.WHITE),
            Map.entry("red", new Color(0xE4, 0x4A, 0x4A)),
            Map.entry("green", new Color(0x4A, 0xC2, 0x6B)),
            Map.entry("blue", new Color(0x4A, 0x8F, 0xE4)),
            Map.entry("yellow", new Color(0xE8, 0xC6, 0x4A)),
            Map.entry("orange", new Color(0xE8, 0x8A, 0x4A)),
            Map.entry("cyan", new Color(0x4A, 0xD5, 0xE8)),
            Map.entry("magenta", new Color(0xD5, 0x4A, 0xE8)),
            Map.entry("gray", new Color(0x80, 0x80, 0x80)),
            Map.entry("darkgray", new Color(0x40, 0x40, 0x40)),
            Map.entry("lightgray", new Color(0xC0, 0xC0, 0xC0)),
            Map.entry("transparent", new Color(0, 0, 0, 0)));

    private static final Map<String, Color> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private Colors() {
    }

    /**
     * Цвет по строке.
     *
     * @throws IllegalArgumentException если строка не цвет — вызывающий переводит
     *                                  это в ошибку скрипта со своим местом в коде
     */
    public static Color of(String text) {
        if (text == null) {
            throw new IllegalArgumentException("цвет не задан");
        }
        Color cached = CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        Color parsed = parse(text.trim().toLowerCase(Locale.ROOT));
        CACHE.put(text, parsed);
        return parsed;
    }

    /** Строка {@code "#rrggbb"} по составляющим — то, что отдаёт {@code game.rgb}. */
    public static String hex(int red, int green, int blue) {
        return String.format("#%02x%02x%02x", clamp(red), clamp(green), clamp(blue));
    }

    private static Color parse(String text) {
        Color named = NAMED.get(text);
        if (named != null) {
            return named;
        }
        if (!text.startsWith("#")) {
            throw new IllegalArgumentException("непонятный цвет '" + text
                    + "': ожидалось #rrggbb, #rgb, #aarrggbb или имя вроде 'red'");
        }
        String digits = text.substring(1);
        try {
            return switch (digits.length()) {
                // Короткая запись: каждая цифра удваивается, #f0c → #ff00cc.
                case 3 -> new Color(digit(digits, 0) * 17, digit(digits, 1) * 17, digit(digits, 2) * 17);
                case 6 -> new Color(Integer.parseInt(digits, 16));
                // Прозрачность первой парой — как в Android и в вебе с #aarrggbb.
                case 8 -> new Color((int) Long.parseLong(digits, 16), true);
                default -> throw new IllegalArgumentException("непонятный цвет '#" + digits
                        + "': ожидалось 3, 6 или 8 шестнадцатеричных цифр");
            };
        } catch (NumberFormatException notHex) {
            throw new IllegalArgumentException("непонятный цвет '#" + digits
                    + "': ожидались шестнадцатеричные цифры", notHex);
        }
    }

    private static int digit(String text, int index) {
        int value = Character.digit(text.charAt(index), 16);
        if (value < 0) {
            throw new IllegalArgumentException("непонятный цвет '#" + text + "'");
        }
        return value;
    }

    private static int clamp(int component) {
        return Math.max(0, Math.min(255, component));
    }
}
