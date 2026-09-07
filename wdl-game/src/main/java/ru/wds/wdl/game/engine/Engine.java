package ru.wds.wdl.game.engine;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Движок: сцена, ввод, окно и цикл кадров — то, что игра получает целиком.
 *
 * <h2>Цикл идёт в потоке, который его позвал</h2>
 * {@link #run} не заводит потока. Это решение, а не упрощение: обработчик игры
 * (а через мост — функция скрипта) выполняется там же, где стоит вызов
 * {@code world.run()}, и потому его ошибка обычным образом доходит до автора
 * скрипта — с местом в коде, с {@code try}/{@code catch}, без единой строчки
 * про потоки. Цена — {@code run()} не возвращает управления, пока игра идёт;
 * так и должно быть, это и есть игра.
 * <p>
 * Второе следствие того же решения: в потоке интерфейса Swing не выполняется
 * ничего, кроме показа готовой картинки. Кадр рисуется в оперативную память
 * ({@link BufferedImage}) в потоке игры, а окно получает его уже собранным.
 *
 * <h2>Два кадра-буфера</h2>
 * Пока окно показывает один кадр, движок рисует следующий в другой картинке.
 * Один буфер на двоих означал бы, что панель иногда показывает наполовину
 * стёртый экран — самый заметный вид мерцания.
 */
public final class Engine {

    private final Scene scene;
    private final Input input = new Input();
    private final int fps;
    private volatile String title;

    /** Два кадра и два холста к ним: холст держит картинку, в которую пишет {@code pixel}. */
    private final BufferedImage[] buffers = new BufferedImage[2];
    private final Painter[] painters = new Painter[2];
    private int nextBuffer;

    private volatile GameWindow window;
    private volatile boolean running;
    private volatile double measuredFps;
    private volatile long frames;
    private volatile double time;

    public Engine(String title, int width, int height, String background, int fps) {
        this.title = Objects.requireNonNull(title, "title");
        this.scene = new Scene(width, height, background);
        this.fps = fps;
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            painters[i] = new Painter(buffers[i]);
        }
    }

    public Scene scene() {
        return scene;
    }

    public Input input() {
        return input;
    }

    public String title() {
        return title;
    }

    public void title(String newTitle) {
        this.title = Objects.requireNonNull(newTitle, "title");
        GameWindow opened = window;
        if (opened != null) {
            opened.title(newTitle);
        }
    }

    /** Идёт ли цикл кадров. */
    public boolean running() {
        return running;
    }

    /** Сколько кадров прошло с начала игры. */
    public long frames() {
        return frames;
    }

    /** Сколько секунд игрового времени прошло — сумма шагов, а не показания часов. */
    public double time() {
        return time;
    }

    /** Измеренная частота кадров за последнюю секунду. */
    public double fps() {
        return measuredFps;
    }

    /**
     * Открывает окно и крутит игру, пока окно не закроют или не позовут {@link #stop}.
     * <p>
     * {@link Game#stop()} зовётся только на штатном конце. Если обработчик игры бросил
     * ошибку, мир остался в непонятном состоянии, и звать по нему ещё один обработчик
     * — это второй способ упасть поверх первого; окно при этом закрывается всё равно.
     *
     * @throws IllegalStateException если игра уже идёт или графической среды нет
     */
    public void run(Game game) {
        Objects.requireNonNull(game, "game");
        if (running) {
            throw new IllegalStateException("игра уже идёт");
        }
        GameWindow opened = GameWindow.open(title, scene.width(), scene.height(), input);
        window = opened;
        running = true;
        GameLoop loop = new GameLoop(fps);
        try {
            game.start();
            while (running && opened.isOpen() && !Thread.currentThread().isInterrupted()) {
                double dt = loop.tick();
                frames++;
                time += dt;
                measuredFps = loop.fps();
                frame(game, dt);
                render(game);
            }
            running = false;
            game.stop();
        } finally {
            running = false;
            window = null;
            opened.close();
        }
    }

    /**
     * Один шаг симуляции без окна и без рисования.
     * <p>
     * То же, что делает кадр, минус графика: ввод, движение, {@link Game#update}.
     * Нужен ровно затем, чтобы игру можно было прогнать в тесте — там, где
     * графической среды нет, а проверить правила надо.
     */
    public void step(Game game, double dt) {
        Objects.requireNonNull(game, "game");
        frames++;
        time += dt;
        frame(game, dt);
    }

    /** Просит цикл остановиться: текущий кадр досчитается, следующего не будет. */
    public void stop() {
        running = false;
    }

    /** Закрывает окно, если оно открыто. Цикл при этом заканчивается сам. */
    public void close() {
        running = false;
        GameWindow opened = window;
        if (opened != null) {
            opened.close();
        }
    }

    private void frame(Game game, double dt) {
        input.beginFrame();
        for (Input.Stroke stroke : input.drain()) {
            game.key(stroke.key(), stroke.down());
        }
        scene.integrate(dt);
        game.update(dt);
    }

    private void render(Game game) {
        int index = nextBuffer;
        nextBuffer = (nextBuffer + 1) % buffers.length;
        Painter painter = painters[index];
        Graphics2D graphics = buffers[index].createGraphics();
        try {
            painter.begin(graphics);
            painter.clear(scene.background());
            for (Sprite sprite : scene.sprites()) {
                painter.draw(sprite);
            }
            game.draw(painter);
        } finally {
            painter.end();
            graphics.dispose();
        }
        GameWindow opened = window;
        if (opened != null) {
            opened.present(buffers[index]);
        }
    }
}
