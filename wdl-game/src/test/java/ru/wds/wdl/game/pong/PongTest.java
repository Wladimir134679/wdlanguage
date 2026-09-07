package ru.wds.wdl.game.pong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.game.engine.Engine;
import ru.wds.wdl.game.engine.Painter;
import ru.wds.wdl.game.engine.Sprite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пинг-понг без скриптов и без окна.
 * <p>
 * Хуки здесь — обычные Java-лямбды, и это половина проверки: если игра требует
 * от них чего-то, чего нет в {@link PongHooks}, граница проведена неправильно.
 * Вторая половина — {@code ru.wds.wdl.game.PongScriptsTest}, где на том же месте
 * стоят встроенные скрипты.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PongTest {

    /** Один кадр при шестидесяти в секунду. */
    private static final double FRAME = 1.0 / 60;

    private Engine engine;
    private Rules rules;
    private Pong pong;

    @BeforeEach
    void setUp() {
        engine = Pong.arena();
        rules = new Rules();
        pong = new Pong(engine, rules);
        // Окна нет: start() зовёт тот, кто крутит цикл, а здесь цикл — сам тест.
        pong.start();
    }

    private void step() {
        engine.step(pong, FRAME);
    }

    @Test
    @DisplayName("Игра начинается с трёх спрайтов и подачи игрока")
    void startsWithServe() {
        assertEquals(3, engine.scene().count());
        assertNotNull(engine.scene().get("ball"));
        assertSame(pong.ball(), engine.scene().get("ball"));

        assertEquals(Match.State.SERVE, pong.match().state());
        assertEquals(Match.Side.PLAYER, pong.match().serving());
        assertEquals(0, pong.match().player());
    }

    @Test
    @DisplayName("До подачи мяч стоит у ракетки подающего")
    void ballWaitsAtPaddle() {
        step();
        assertEquals(0, pong.ball().vx());
        assertTrue(pong.ball().x() > pong.player().right(),
                "мяч ждёт справа от ракетки игрока");
        assertEquals(Match.State.SERVE, pong.match().state());
    }

    @Test
    @DisplayName("Пробел спрашивает у скрипта подачу и начинает розыгрыш")
    void spaceServes() {
        pong.key("space", true);

        assertEquals(List.of("serve"), rules.calls);
        assertEquals(Match.State.PLAY, pong.match().state());
        assertEquals(300, pong.ball().vx());
    }

    @Test
    @DisplayName("Соперник подаёт сам, когда игрок ждать не обязан")
    void rivalServesItself() {
        goal(Match.Side.PLAYER);
        assertEquals(Match.Side.RIVAL, pong.match().serving());

        for (int i = 0; i < 60 && pong.match().state() == Match.State.SERVE; i++) {
            step();
        }
        assertEquals(Match.State.PLAY, pong.match().state());
        assertEquals(-300, pong.ball().vx(), "подача соперника летит влево");
    }

    @Test
    @DisplayName("Мяч отскакивает от верхней и нижней стены сам, без скрипта")
    void wallsAreEngineWork() {
        serve();
        pong.ball().moveTo(400, 2);
        pong.ball().vy(-400);
        step();

        assertTrue(pong.ball().vy() > 0, "после верхней стены мяч идёт вниз");
        assertTrue(pong.ball().y() >= 0);
        assertFalse(rules.calls.contains("bounce"), "стена — не ракетка");
    }

    @Test
    @DisplayName("Отбитый мяч приходит в скрипт уже вышедшим из ракетки")
    void bouncedBallLeavesPaddle() {
        serve();
        Sprite ball = pong.ball();
        ball.moveTo(pong.player().right() - 4, pong.player().centerY());
        ball.vx(-300);
        step();

        assertTrue(rules.calls.contains("bounce"));
        assertEquals(pong.player().right(), rules.bouncedAt,
                "к моменту вызова мяч выставлен по краю ракетки");
        assertEquals(1, pong.match().rally());
    }

    @Test
    @DisplayName("Гол считает игра, а конец партии решает скрипт")
    void goalsAreCounted() {
        goal(Match.Side.PLAYER);

        assertEquals(1, pong.match().player());
        assertEquals(0, pong.match().rival());
        assertEquals("player", rules.scored);
        // Подаёт пропустивший — так у отстающего есть чем отыграться.
        assertEquals(Match.Side.RIVAL, pong.match().serving());
        assertEquals(Match.State.SERVE, pong.match().state());
    }

    @Test
    @DisplayName("Скрипт закончил партию — игра встала и знает победителя")
    void scriptEndsMatch() {
        rules.finishAt = 2;
        goal(Match.Side.PLAYER);
        goal(Match.Side.PLAYER);

        assertEquals(Match.State.OVER, pong.match().state());
        assertEquals(Match.Side.PLAYER, pong.match().winner());

        double before = pong.ball().x();
        step();
        assertEquals(before, pong.ball().x(), "сыгранная партия стоит на месте");
    }

    @Test
    @DisplayName("Скорость соперника берётся у скрипта каждый кадр")
    void rivalSpeedComesFromScript() {
        rules.rivalSpeed = 120;
        step();

        assertEquals(120, pong.rival().vy());
        assertTrue(rules.calls.contains("rival"));
    }

    @Test
    @DisplayName("Ракетки не уезжают за край поля")
    void paddlesStayInField() {
        rules.rivalSpeed = 5000;
        for (int i = 0; i < 30; i++) {
            step();
        }
        assertEquals(engine.scene().height() - pong.rival().height(), pong.rival().y());

        rules.rivalSpeed = -5000;
        for (int i = 0; i < 30; i++) {
            step();
        }
        assertEquals(0, pong.rival().y());
    }

    @Test
    @DisplayName("R начинает новую партию с нуля")
    void restartResetsScore() {
        goal(Match.Side.PLAYER);
        pong.key("r", true);

        assertEquals(0, pong.match().player());
        assertEquals(1, pong.match().round(), "новая партия — это первый розыгрыш");
        assertEquals(Match.Side.PLAYER, pong.match().serving());
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

    /** Гол названной стороне: мяч уносится за чужой край поля. */
    private void goal(Match.Side side) {
        if (pong.match().state() != Match.State.PLAY) {
            serve();
        }
        Sprite ball = pong.ball();
        if (side == Match.Side.PLAYER) {
            ball.moveTo(engine.scene().width() + 1, 100);
        } else {
            ball.moveTo(-ball.width() - 1, 100);
        }
        ball.halt();
        step();
    }

    /** Правила на Java: тот же интерфейс, что достаётся встроенным скриптам. */
    private static final class Rules implements PongHooks {

        private final List<String> calls = new ArrayList<>();
        private double rivalSpeed;
        private double bouncedAt;
        private String scored;
        /** На каком очке заканчивать партию; 0 — не заканчивать. */
        private int finishAt;

        @Override
        public void serve(Match match, Sprite ball) {
            calls.add("serve");
            ball.vx(match.serving() == Match.Side.PLAYER ? 300 : -300);
            ball.vy(0);
        }

        @Override
        public void bounce(Match match, Sprite ball, Sprite paddle) {
            calls.add("bounce");
            bouncedAt = ball.x();
            ball.vx(-ball.vx());
        }

        @Override
        public double rival(Match match, Sprite ball, Sprite paddle) {
            calls.add("rival");
            return rivalSpeed;
        }

        @Override
        public boolean point(Match match, Match.Side side) {
            calls.add("point");
            scored = side.title();
            return finishAt > 0 && match.score(side) >= finishAt;
        }

        @Override
        public void hud(Match match, Painter canvas) {
            calls.add("hud");
        }
    }
}
