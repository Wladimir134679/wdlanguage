package ru.wds.wdl.game.engine;

import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Имена клавиш: {@code "left"}, {@code "space"}, {@code "w"}, {@code "f1"}.
 * <p>
 * Скрипт спрашивает {@code world.down("left")}, а не {@code world.down(37)}.
 * Числовой код — деталь AWT: он не читается, не переносится и не подсказывается
 * редактором. Имя же одинаково пишется в игре, в конфиге и в сообщении об ошибке.
 * <p>
 * Таблица одна на оба направления. Опрос переводит имя в код, а событие клавиатуры
 * — код обратно в имя, и держать два списка значило бы однажды получить клавишу,
 * которую можно нажать, но нельзя опросить.
 */
public final class Keys {

    private static final Map<String, Integer> BY_NAME = new LinkedHashMap<>();
    private static final Map<Integer, String> BY_CODE = new LinkedHashMap<>();

    static {
        name("left", KeyEvent.VK_LEFT);
        name("right", KeyEvent.VK_RIGHT);
        name("up", KeyEvent.VK_UP);
        name("down", KeyEvent.VK_DOWN);
        name("space", KeyEvent.VK_SPACE);
        name("enter", KeyEvent.VK_ENTER);
        name("escape", KeyEvent.VK_ESCAPE);
        name("tab", KeyEvent.VK_TAB);
        name("backspace", KeyEvent.VK_BACK_SPACE);
        name("shift", KeyEvent.VK_SHIFT);
        name("ctrl", KeyEvent.VK_CONTROL);
        name("alt", KeyEvent.VK_ALT);
        name("plus", KeyEvent.VK_PLUS);
        name("minus", KeyEvent.VK_MINUS);
        for (char letter = 'a'; letter <= 'z'; letter++) {
            name(String.valueOf(letter), KeyEvent.VK_A + (letter - 'a'));
        }
        for (char digit = '0'; digit <= '9'; digit++) {
            name(String.valueOf(digit), KeyEvent.VK_0 + (digit - '0'));
        }
        for (int number = 1; number <= 12; number++) {
            name("f" + number, KeyEvent.VK_F1 + (number - 1));
        }
    }

    private Keys() {
    }

    /**
     * Код клавиши по имени.
     *
     * @throws IllegalArgumentException если имени нет в таблице: опечатка в
     *                                  {@code world.down("lft")} обязана быть
     *                                  ошибкой, а не вечным «не нажата»
     */
    public static int codeOf(String name) {
        Integer code = name == null ? null : BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
        if (code == null) {
            throw new IllegalArgumentException("неизвестная клавиша '" + name
                    + "'; известны, например: left, right, up, down, space, enter, escape, a-z, 0-9, f1-f12");
        }
        return code;
    }

    /**
     * Имя клавиши по коду; для клавиши, которой нет в таблице, — имя AWT в нижнем
     * регистре. Обработчик события не вправе молчать о нажатии только потому, что
     * клавиша редкая.
     */
    public static String nameOf(int code) {
        String known = BY_CODE.get(code);
        return known != null ? known
                : KeyEvent.getKeyText(code).toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    /** Все известные имена — по ним отвечает справка и подсказка редактора. */
    public static java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(BY_NAME.keySet());
    }

    private static void name(String name, int code) {
        BY_NAME.put(name, code);
        BY_CODE.putIfAbsent(code, name);
    }
}
