package ru.wds.wdl.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.game.engine.Engine;
import ru.wds.wdl.game.engine.Sprite;
import ru.wds.wdl.game.pong.Match;
import ru.wds.wdl.game.pong.Pong;
import ru.wds.wdl.runtime.Output;

import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пинг-понг со встроенными скриптами — теми самыми, что лежат в ресурсах.
 * <p>
 * Проверяется не «скрипт позвался», а что игрой правда управляют файлы:
 * подачу разгоняет {@code rules.wdl}, ракетку соперника ведёт {@code rival.wdl},
 * сетку и счёт рисует {@code hud.wdl}. Окна при этом нет ни в одном тесте:
 * кадр считается {@code step}, а рисуется {@code paint}.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PongScriptsTest {

    private static final double FRAME = 1.0 / 60;
    /** Цвет фона поля и цвет сетки — как их задают Pong и hud.wdl. */
    private static final int BACKGROUND = 0x101418;
    private static final int NET = 0x2b3440;

    private final StringBuilder printed = new StringBuilder();

    private PongScripts scripts;
    private Engine engine;
    private Pong pong;

    @BeforeEach
    void setUp() {
        scripts = PongScripts.embedded((Output) printed::append);
        engine = Pong.arena();
        pong = new Pong(engine, scripts);
        pong.start();
    }

    @AfterEach
    void tearDown() {
        scripts.close();
    }

    private void step() {
        engine.step(pong, FRAME);
    }

    @Test
    @DisplayName("Скрипты выполняются один раз, до первого кадра")
    void scriptsRunOnce() {
        assertTrue(printed.toString().contains("встроенных скриптов"),
                () -> "pong.wdl должен был напечатать себя: " + printed);
    }

    @Test
    @DisplayName("Подачу разгоняет rules.wdl — в сторону принимающего")
    void serveComesFromRules() {
        pong.key("space", true);

        assertEquals(Match.State.PLAY, pong.match().state());
        assertTrue(pong.ball().vx() > 0,
                () -> "подача игрока летит вправо, а не " + pong.ball().vx());
        assertTrue(pong.ball().vx() >= 320, "первая подача идёт со START_SPEED");
    }

    @Test
    @DisplayName("Отскок из rules.wdl разворачивает мяч и ускоряет его")
    void bounceComesFromRules() {
        serve();
        Sprite ball = pong.ball();
        ball.moveTo(pong.player().right() - 4, pong.player().centerY());
        ball.vx(-300);
        ball.vy(0);
        step();

        assertTrue(ball.vx() > 300, () -> "мяч отбит и ускорен, а не " + ball.vx());
        assertEquals(1, pong.match().rally());
    }

    @Test
    @DisplayName("Ракетку соперника ведёт rival.wdl — к летящему мячу")
    void rivalComesFromScript() {
        Sprite ball = pong.ball();
        ball.moveTo(400, 420);
        ball.vx(300);
        ball.vy(0);
        step();

        assertTrue(pong.rival().vy() > 0,
                () -> "мяч ниже ракетки — соперник едет вниз, а не " + pong.rival().vy());
    }

    @Test
    @DisplayName("Партию закрывает rules.wdl — на седьмом очке")
    void matchEndsAtTarget() {
        playUntilOver();

        assertEquals(Match.State.OVER, pong.match().state());
        assertEquals(Match.Side.PLAYER, pong.match().winner());
        assertEquals(Pong.TARGET, pong.match().player());
        assertTrue(printed.toString().contains("Очко: 7 : 0"),
                () -> "счёт печатает rules.wdl: " + printed);
    }

    @Test
    @DisplayName("Сетку по центру поля рисует hud.wdl — попиксельно")
    void hudDrawsNet() {
        BufferedImage frame = engine.paint(pong);
        int middle = engine.scene().width() / 2;

        assertEquals(NET, rgb(frame, middle, 0), "первый штрих сетки");
        assertEquals(NET, rgb(frame, middle, 5), "и его последняя точка");
        assertEquals(BACKGROUND, rgb(frame, middle, 6), "между штрихами — фон");
        assertNotEquals(BACKGROUND, rgb(frame, middle, 12), "следующий штрих");
    }

    /** Подача: игроку — пробелом, сопернику — ожиданием, он подаёт сам. */
    private void serve() {
        if (pong.match().serving() == Match.Side.PLAYER) {
            pong.key("space", true);
        }
        for (int i = 0; i < 120 && pong.match().state() != Match.State.PLAY; i++) {
            step();
        }
    }

    /** Играет партию до конца, отдавая все очки игроку. */
    private void playUntilOver() {
        for (int frame = 0; frame < 3000 && pong.match().state() != Match.State.OVER; frame++) {
            if (pong.match().state() == Match.State.PLAY) {
                pong.ball().moveTo(engine.scene().width() + 1, 100);
                pong.ball().halt();
            } else if (pong.match().serving() == Match.Side.PLAYER) {
                pong.key("space", true);
            }
            step();
        }
    }

    private static int rgb(BufferedImage frame, int x, int y) {
        return frame.getRGB(x, y) & 0xFFFFFF;
    }
}
