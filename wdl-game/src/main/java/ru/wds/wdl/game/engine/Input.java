package ru.wds.wdl.game.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Что делает пользователь: нажатые клавиши, положение мыши, очередь событий.
 * <p>
 * Пишет сюда поток обработки событий Swing (EDT), читает поток игрового цикла.
 * Поэтому всё состояние — конкурентное, а не «почти всегда один поток».
 *
 * <h2>Опрос и событие — разные вопросы, и оба нужны</h2>
 * «Держат ли сейчас стрелку» — это {@link #down(int)}, и спрашивать это надо
 * каждый кадр: ракетка едет, пока клавишу держат. «Нажали ли её только что» —
 * это {@link #pressed(int)}, и без него подача мяча по пробелу повторилась бы
 * шестьдесят раз в секунду.
 * <p>
 * Край кадра считается по снимку: {@link #beginFrame()} запоминает, что было
 * нажато на прошлом кадре, и разница даёт нажатия этого. Ловить край в самом
 * слушателе нельзя — автоповтор клавиатуры шлёт {@code keyPressed} пачкой,
 * и «нажали только что» стало бы правдой десять раз подряд.
 *
 * <h2>Обработчик скрипта зовётся из цикла, а не из EDT</h2>
 * События копятся в очереди, а цикл кадров разбирает её в своём потоке
 * ({@link #drain()}). Иначе функция скрипта выполнялась бы в потоке интерфейса:
 * пока она считает, окно не перерисовывается, а её ошибка роняет очередь событий
 * Swing, а не игру.
 */
public final class Input {

    /** Нажатие или отпускание клавиши — то, что уедет в обработчик скрипта. */
    public record Stroke(String key, boolean down) {
    }

    private final Set<Integer> held = ConcurrentHashMap.newKeySet();
    private volatile Set<Integer> heldLastFrame = Set.of();
    private final Queue<Stroke> events = new ConcurrentLinkedQueue<>();

    private volatile int mouseX;
    private volatile int mouseY;
    private volatile boolean mouseDown;

    /** Держат ли клавишу прямо сейчас. */
    public boolean down(int code) {
        return held.contains(code);
    }

    /** Нажали ли клавишу в этом кадре — на прошлом её не держали, на этом держат. */
    public boolean pressed(int code) {
        return held.contains(code) && !heldLastFrame.contains(code);
    }

    public int mouseX() {
        return mouseX;
    }

    public int mouseY() {
        return mouseY;
    }

    public boolean mouseDown() {
        return mouseDown;
    }

    /** Начало кадра: снимок нажатого, по которому считается край. */
    public void beginFrame() {
        heldLastFrame = Set.copyOf(held);
    }

    /** Накопленные события клавиатуры; очередь после вызова пуста. */
    public List<Stroke> drain() {
        List<Stroke> drained = new ArrayList<>();
        for (Stroke stroke = events.poll(); stroke != null; stroke = events.poll()) {
            drained.add(stroke);
        }
        return drained;
    }

    /**
     * Забывает всё нажатое: окно потеряло фокус.
     * <p>
     * Без этого клавиша, отпущенная в чужом окне, остаётся нажатой навсегда —
     * ракетка уезжает в стену и живёт там до следующего нажатия.
     */
    public void forgetAll() {
        Set<Integer> stuck = new HashSet<>(held);
        held.clear();
        for (Integer code : stuck) {
            events.add(new Stroke(Keys.nameOf(code), false));
        }
    }

    // --- со стороны Swing --------------------------------------------------

    void keyDown(int code) {
        // Автоповтор шлёт keyPressed пачкой: событие в очередь кладётся только
        // на настоящем переходе, иначе обработчик скрипта увидит десять нажатий.
        if (held.add(code)) {
            events.add(new Stroke(Keys.nameOf(code), true));
        }
    }

    void keyUp(int code) {
        if (held.remove(code)) {
            events.add(new Stroke(Keys.nameOf(code), false));
        }
    }

    void mouseMoved(int x, int y) {
        this.mouseX = x;
        this.mouseY = y;
    }

    void mouseButton(boolean pressed) {
        this.mouseDown = pressed;
    }
}
