package ru.wds.wdl.game.pong;

import ru.wds.wdl.game.engine.Engine;
import ru.wds.wdl.game.engine.Game;
import ru.wds.wdl.game.engine.Keys;
import ru.wds.wdl.game.engine.Painter;
import ru.wds.wdl.game.engine.Scene;
import ru.wds.wdl.game.engine.Sprite;

import java.util.Objects;

/**
 * Пинг-понг: сама игра — на Java, поверх движка WDGame.
 * <p>
 * Правила, которые меняются, вынесены в {@link PongHooks} и живут в скриптах;
 * здесь остаётся то, что правилами не является: поле, ракетки, мяч, ввод игрока,
 * столкновения и счёт. Такое разделение — весь смысл модуля: показать встраивание
 * с той стороны, с которой его видит хозяин, где скрипт не пишет игру, а участвует
 * в ней.
 *
 * <h2>Порядок внутри кадра</h2>
 * Движок к моменту {@link #update} уже сдвинул спрайты по их скорости, поэтому
 * кадр читается сверху вниз как «что стало и что с этим делать»:
 * <ol>
 *   <li>ракетки получают скорость — игрок с клавиатуры, соперник из скрипта;</li>
 *   <li>обе загоняются в поле: за край их не пускает игра, а не скрипт;</li>
 *   <li>дальше — по состоянию партии: подача ждёт, розыгрыш считает столкновения
 *       и голы, сыгранная партия просто стоит.</li>
 * </ol>
 *
 * <h2>Отбитый мяч сначала переставляется, потом разворачивается</h2>
 * Иначе на следующем кадре он снова окажется внутри ракетки, снова «отскочит»
 * и залипнет в ней до конца розыгрыша. Поэтому {@code ball.x} выставляется
 * по краю ракетки <b>до</b> вызова {@link PongHooks#bounce}, и скрипту достаётся
 * мяч, который уже вышел из ракетки.
 */
public final class Pong implements Game {

    /** Ширина поля. */
    public static final int WIDTH = 900;
    /** Высота поля. */
    public static final int HEIGHT = 540;
    /** До скольких очков идёт партия. */
    public static final int TARGET = 7;

    private static final String BACKGROUND = "#101418";
    private static final String PLAYER_COLOR = "#7ec8ff";
    private static final String RIVAL_COLOR = "#ff9f6e";
    private static final String BALL_COLOR = "#f2f2f2";

    private static final double PADDLE_WIDTH = 14;
    private static final double PADDLE_HEIGHT = 96;
    private static final double BALL_SIZE = 14;
    /** Отступ ракетки от своего края поля. */
    private static final double MARGIN = 28;
    /** Скорость ракетки игрока: клавишу держат — едет, отпустили — стоит. */
    private static final double PADDLE_SPEED = 460;
    /** Сколько соперник медлит перед своей подачей: нажать пробел ему нечем. */
    private static final double SERVE_DELAY = 0.9;
    /** Зазор между мячом и ракеткой на подаче. */
    private static final double SERVE_GAP = 6;

    private final Engine engine;
    private final Scene scene;
    private final PongHooks hooks;
    private final Match match;

    private Sprite player;
    private Sprite rival;
    private Sprite ball;

    /** Сколько осталось до подачи соперника; для подачи игрока не используется. */
    private double serveIn;

    public Pong(Engine engine, PongHooks hooks) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.scene = engine.scene();
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.match = new Match(scene.width(), scene.height(), TARGET);
    }

    /**
     * Движок с полем нужного размера — чтобы размеры поля не были записаны
     * в двух местах: здесь и у того, кто заводит окно.
     */
    public static Engine arena() {
        return new Engine("Пинг-понг — WDGame", WIDTH, HEIGHT, BACKGROUND, 60);
    }

    /** Партия: счёт, состояние, чья подача. */
    public Match match() {
        return match;
    }

    /** Мяч — тестам и отладке; игре он и так свой. */
    public Sprite ball() {
        return ball;
    }

    /** Ракетка игрока. */
    public Sprite player() {
        return player;
    }

    /** Ракетка соперника. */
    public Sprite rival() {
        return rival;
    }

    @Override
    public void start() {
        double middle = (scene.height() - PADDLE_HEIGHT) / 2;
        player = scene.spawn("player", MARGIN, middle,
                PADDLE_WIDTH, PADDLE_HEIGHT, PLAYER_COLOR);
        rival = scene.spawn("rival", scene.width() - MARGIN - PADDLE_WIDTH, middle,
                PADDLE_WIDTH, PADDLE_HEIGHT, RIVAL_COLOR);
        ball = scene.spawn("ball", 0, 0, BALL_SIZE, BALL_SIZE, BALL_COLOR);
        ball.shape(Sprite.Shape.OVAL);
        restart();
    }

    @Override
    public void update(double dt) {
        match.tick(dt);
        steer();
        hold(player);
        hold(rival);
        switch (match.state()) {
            case SERVE -> awaitServe(dt);
            case PLAY -> rally();
            case OVER -> ball.halt();
        }
    }

    @Override
    public void draw(Painter canvas) {
        hooks.hud(match, canvas);
    }

    @Override
    public void key(String key, boolean down) {
        if (!down) {
            return;
        }
        switch (key) {
            // Пробел — событие, а не опрос: удержание клавиши не должно подавать
            // мяч шестьдесят раз в секунду.
            case "space" -> {
                if (match.state() == Match.State.SERVE
                        && match.serving() == Match.Side.PLAYER) {
                    serve();
                }
            }
            case "r" -> restart();
            case "escape" -> engine.stop();
            default -> {
                // Остальные клавиши опрашиваются каждый кадр — см. steer().
            }
        }
    }

    /** Новая партия: счёт с нуля, все на местах, подаёт игрок. */
    public void restart() {
        match.reset();
        double middle = (scene.height() - PADDLE_HEIGHT) / 2;
        player.moveTo(MARGIN, middle);
        player.halt();
        rival.moveTo(scene.width() - MARGIN - PADDLE_WIDTH, middle);
        rival.halt();
        ball.halt();
        match.beginRally(Match.Side.PLAYER);
        attachBall();
        serveIn = SERVE_DELAY;
    }

    /** Ракетки: игрок с клавиатуры, соперник — из скрипта. */
    private void steer() {
        if (match.state() == Match.State.OVER) {
            player.halt();
            rival.halt();
            return;
        }
        double direction = 0;
        if (held("up") || held("w")) {
            direction -= 1;
        }
        if (held("down") || held("s")) {
            direction += 1;
        }
        player.vy(direction * PADDLE_SPEED);
        rival.vy(hooks.rival(match, ball, rival));
    }

    /** Мяч у подающей ракетки; соперник подаёт сам, игрок — по пробелу. */
    private void awaitServe(double dt) {
        attachBall();
        if (match.serving() == Match.Side.RIVAL) {
            serveIn -= dt;
            if (serveIn <= 0) {
                serve();
            }
        }
    }

    private void serve() {
        hooks.serve(match, ball);
        match.playing();
    }

    /** Розыгрыш: стены, ракетки, гол. */
    private void rally() {
        bounceOffWalls();
        // Знак скорости в условии — не украшение: без него мяч, вылетающий
        // из ракетки, отбивался бы второй раз и уходил обратно в неё.
        if (ball.vx() < 0 && ball.hits(player)) {
            ball.x(player.right());
            match.rallied();
            hooks.bounce(match, ball, player);
        } else if (ball.vx() > 0 && ball.hits(rival)) {
            ball.x(rival.x() - ball.width());
            match.rallied();
            hooks.bounce(match, ball, rival);
        }
        if (ball.right() < 0) {
            point(Match.Side.RIVAL);
        } else if (ball.x() > scene.width()) {
            point(Match.Side.PLAYER);
        }
    }

    private void bounceOffWalls() {
        // Отражение по модулю, а не сменой знака: мяч, застрявший в стене
        // на длинном кадре, иначе разворачивался бы каждый кадр и вибрировал в ней.
        if (ball.y() < 0) {
            ball.y(0);
            ball.vy(Math.abs(ball.vy()));
        } else if (ball.bottom() > scene.height()) {
            ball.y(scene.height() - ball.height());
            ball.vy(-Math.abs(ball.vy()));
        }
    }

    private void point(Match.Side side) {
        match.award(side);
        ball.halt();
        if (hooks.point(match, side)) {
            match.finish(side);
            return;
        }
        // Подаёт пропустивший — так у отстающего есть чем отыграться.
        match.beginRally(side.other());
        serveIn = SERVE_DELAY;
        attachBall();
    }

    /** Ставит мяч вплотную к ракетке подающего. */
    private void attachBall() {
        Sprite paddle = match.serving() == Match.Side.PLAYER ? player : rival;
        double x = match.serving() == Match.Side.PLAYER
                ? paddle.right() + SERVE_GAP
                : paddle.x() - ball.width() - SERVE_GAP;
        ball.moveTo(x, paddle.centerY() - ball.height() / 2);
        ball.halt();
    }

    /** Не пускает ракетку за край поля. */
    private void hold(Sprite paddle) {
        double lowest = scene.height() - paddle.height();
        if (paddle.y() < 0) {
            paddle.y(0);
        } else if (paddle.y() > lowest) {
            paddle.y(lowest);
        }
    }

    private boolean held(String key) {
        return engine.input().down(Keys.codeOf(key));
    }
}
