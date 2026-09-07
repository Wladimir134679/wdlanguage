package ru.wds.wdl.game.pong;

import java.util.Objects;

/**
 * Состояние партии: счёт, чья подача, идёт ли розыгрыш.
 * <p>
 * Это то, что игра показывает скриптам, — и единственное, что они видят помимо
 * спрайтов и холста. Отдельный объект, а не набор полей {@link Pong}, именно
 * поэтому: скрипту нужно состояние партии, но не нужны ни сцена, ни ввод,
 * ни цикл кадров, и давать их значило бы отдать наружу то, чем игра управляет
 * сама.
 *
 * <h2>Меняет его только игра</h2>
 * Скрипт видит партию через {@code Match} со свойствами на чтение: подача, отскок
 * и счёт — решения, но записывает их в состояние Java. Иначе одно и то же правило
 * оказалось бы записано в двух местах, и рассогласование этих двух мест было бы
 * вопросом времени.
 */
public final class Match {

    /** Сторона поля: слева — игрок, справа — соперник. */
    public enum Side {
        PLAYER("player"), RIVAL("rival");

        private final String title;

        Side(String title) {
            this.title = title;
        }

        /** Имя стороны так, как её называет скрипт. */
        public String title() {
            return title;
        }

        /** Противоположная сторона: подаёт всегда тот, кто пропустил. */
        public Side other() {
            return this == PLAYER ? RIVAL : PLAYER;
        }
    }

    /** Что происходит на поле прямо сейчас. */
    public enum State {
        /** Мяч у подающей ракетки и ждёт подачи. */
        SERVE("serve"),
        /** Розыгрыш идёт. */
        PLAY("play"),
        /** Партия сыграна, счёт окончательный. */
        OVER("over");

        private final String title;

        State(String title) {
            this.title = title;
        }

        /** Имя состояния так, как его называет скрипт. */
        public String title() {
            return title;
        }
    }

    private final int width;
    private final int height;
    private final int target;

    // Всё изменяемое — volatile: кадр считает поток игры, а показать состояние
    // может кто угодно ещё (тест, отладочная печать), и половины числа он видеть
    // не должен.
    private volatile int player;
    private volatile int rival;
    private volatile int round;
    private volatile int rally;
    private volatile double time;
    private volatile State state = State.SERVE;
    private volatile Side serving = Side.PLAYER;
    private volatile Side winner;

    public Match(int width, int height, int target) {
        this.width = width;
        this.height = height;
        this.target = target;
    }

    /** Ширина поля в пикселях. */
    public int width() {
        return width;
    }

    /** Высота поля в пикселях. */
    public int height() {
        return height;
    }

    /** До скольких очков играют. */
    public int target() {
        return target;
    }

    /** Счёт игрока. */
    public int player() {
        return player;
    }

    /** Счёт соперника. */
    public int rival() {
        return rival;
    }

    /** Счёт стороны. */
    public int score(Side side) {
        return side == Side.PLAYER ? player : rival;
    }

    /**
     * Номер розыгрыша с начала партии, считая с единицы: по нему скрипт разгоняет
     * подачу.
     */
    public int round() {
        return round;
    }

    /** Сколько раз мяч отбили в текущем розыгрыше. */
    public int rally() {
        return rally;
    }

    /** Игровое время партии в секундах. */
    public double time() {
        return time;
    }

    /** Что происходит на поле. */
    public State state() {
        return state;
    }

    /** Чья подача. */
    public Side serving() {
        return serving;
    }

    /** Победитель или {@code null}, пока партия не сыграна. */
    public Side winner() {
        return winner;
    }

    /** Прибавляет время кадра. */
    void tick(double dt) {
        time += dt;
    }

    /** Новый розыгрыш: подаёт названная сторона. */
    void beginRally(Side next) {
        this.serving = Objects.requireNonNull(next, "next");
        this.state = State.SERVE;
        this.rally = 0;
        this.round++;
    }

    /** Мяч подан — розыгрыш пошёл. */
    void playing() {
        this.state = State.PLAY;
    }

    /** Мяч отбит ракеткой. */
    void rallied() {
        this.rally++;
    }

    /** Очко стороне. */
    void award(Side side) {
        if (side == Side.PLAYER) {
            player++;
        } else {
            rival++;
        }
    }

    /** Партия сыграна. */
    void finish(Side won) {
        this.winner = Objects.requireNonNull(won, "won");
        this.state = State.OVER;
    }

    /** Новая партия с нуля. */
    void reset() {
        player = 0;
        rival = 0;
        round = 0;
        rally = 0;
        time = 0;
        winner = null;
        state = State.SERVE;
        serving = Side.PLAYER;
    }

    @Override
    public String toString() {
        return "Match[" + player + ":" + rival + ", " + state.title() + "]";
    }
}
