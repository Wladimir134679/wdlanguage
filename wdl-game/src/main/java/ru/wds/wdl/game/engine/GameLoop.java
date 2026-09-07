package ru.wds.wdl.game.engine;

/**
 * Время игры: сколько прошло с прошлого кадра и сколько ещё спать до следующего.
 * <p>
 * Отдельно от {@link Engine} потому, что это единственная часть цикла, которую
 * стоит проверять числами, а не глазами: клампинг длинного кадра и удержание
 * частоты — это правила, а не рисование.
 *
 * <h2>Длинный кадр обрезается</h2>
 * Окно перетащили, машина ушла в своп, отладчик постоял на точке останова — и
 * между кадрами прошла секунда. Без ограничения мяч сдвинется на треть экрана
 * <b>за один шаг</b> и пролетит сквозь ракетку: столкновение проверяется
 * по положениям, а не по траектории. Поэтому шаг ограничен {@link #MAX_STEP},
 * и игра предпочитает замедлиться, а не провалиться сквозь стену.
 */
public final class GameLoop {

    /** Самый длинный шаг, который движок отдаёт игре: 50 мс, то есть 20 кадров в секунду. */
    public static final double MAX_STEP = 0.05;

    private final long frameNanos;
    private long previous = System.nanoTime();

    /** Скользящий счёт кадров: сколько их было за последнюю секунду. */
    private long secondStarted = previous;
    private int framesInSecond;
    private volatile double measured;

    /**
     * @param fps желаемая частота кадров; {@code 0} — «как получится», без сна
     */
    public GameLoop(int fps) {
        if (fps < 0) {
            throw new IllegalArgumentException("частота кадров не может быть отрицательной: " + fps);
        }
        this.frameNanos = fps == 0 ? 0 : 1_000_000_000L / fps;
    }

    /**
     * Ждёт начала следующего кадра и отдаёт длину прошедшего в секундах.
     *
     * @return время кадра, не больше {@link #MAX_STEP}
     */
    public double tick() {
        sleepUntilNextFrame();
        long now = System.nanoTime();
        double dt = (now - previous) / 1_000_000_000.0;
        previous = now;
        countFrame(now);
        return Math.min(dt, MAX_STEP);
    }

    /** Сколько кадров в секунду получается на самом деле. */
    public double fps() {
        return measured;
    }

    private void sleepUntilNextFrame() {
        if (frameNanos == 0) {
            return;
        }
        long due = previous + frameNanos;
        long left = due - System.nanoTime();
        if (left <= 0) {
            return;
        }
        try {
            Thread.sleep(left / 1_000_000L, (int) (left % 1_000_000L));
        } catch (InterruptedException interrupted) {
            // Прерывание сильнее игры: остановка снаружи обязана снимать и того,
            // кто спит между кадрами. Признак восстанавливается, решение — за циклом.
            Thread.currentThread().interrupt();
        }
    }

    private void countFrame(long now) {
        framesInSecond++;
        long sinceSecond = now - secondStarted;
        if (sinceSecond >= 1_000_000_000L) {
            measured = framesInSecond * 1_000_000_000.0 / sinceSecond;
            framesInSecond = 0;
            secondStarted = now;
        }
    }
}
