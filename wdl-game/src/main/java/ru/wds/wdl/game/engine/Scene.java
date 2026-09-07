package ru.wds.wdl.game.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Мир игры: размер, фон и спрайты на нём.
 * <p>
 * Сцена <b>ничего не знает про окно</b> — ни про Swing, ни про Graphics2D. Это
 * главное решение движка, и оно окупается сразу: игру можно прогнать
 * {@code world.step(dt)} без графической среды, то есть в обычном тесте на сборке,
 * где окна нет и быть не может. Окно появляется только в {@link Engine#run},
 * и появляется поздно — на первом кадре.
 *
 * <h2>Имя — ключ</h2>
 * Спрайт заводится под именем, и по имени же его находит {@code world.get("ball")}.
 * Так скрипт, разложенный на несколько функций, не обязан таскать ссылки
 * аргументами через полфайла. Имя уникально: второй спрайт с тем же именем —
 * это опечатка, а не два мяча.
 */
public final class Scene {

    private final int width;
    private final int height;
    private volatile String background;

    /**
     * Порядок отрисовки — порядок создания. Список конкурентный, потому что
     * читает его цикл кадров, а менять вправе и обработчик, и поток скрипта.
     */
    private final List<Sprite> sprites = new CopyOnWriteArrayList<>();
    private final Map<String, Sprite> byName = new ConcurrentHashMap<>();

    public Scene(int width, int height, String background) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("размер сцены " + width + "x" + height
                    + ": ширина и высота должны быть положительными");
        }
        this.width = width;
        this.height = height;
        this.background = background;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String background() {
        return background;
    }

    public void background(String color) {
        this.background = color;
    }

    /**
     * Заводит спрайт.
     *
     * @throws IllegalStateException если имя уже занято
     */
    public Sprite spawn(String name, double x, double y, double width, double height, String color) {
        Sprite sprite = new Sprite(name, x, y, width, height, color);
        if (byName.putIfAbsent(name, sprite) != null) {
            throw new IllegalStateException("спрайт '" + name + "' уже есть на сцене");
        }
        sprite.scene(this);
        sprites.add(sprite);
        return sprite;
    }

    public Sprite get(String name) {
        return byName.get(name);
    }

    /** Спрайты в порядке отрисовки. */
    public List<Sprite> sprites() {
        return List.copyOf(sprites);
    }

    public int count() {
        return sprites.size();
    }

    /** Снимает спрайт со сцены; {@code false}, если его там уже нет. */
    public boolean remove(Sprite sprite) {
        if (sprite == null || !sprites.remove(sprite)) {
            return false;
        }
        byName.remove(sprite.name(), sprite);
        sprite.markRemoved();
        return true;
    }

    /** Убирает всё: перезапуск уровня. */
    public void clear() {
        for (Sprite sprite : sprites) {
            sprite.markRemoved();
        }
        sprites.clear();
        byName.clear();
    }

    /**
     * Двигает все спрайты на один кадр.
     * <p>
     * До обработчика скрипта, а не после: скрипт получает мир уже сдвинутым
     * и правит то, что видит, — отбивает мяч, упирает ракетку в край. Порядок
     * «сначала подумать, потом двигать» означал бы, что столкновение замечено
     * на кадр позже, чем случилось.
     */
    public void integrate(double dt) {
        for (Sprite sprite : sprites) {
            sprite.step(dt);
        }
    }

    /** Спрайты, с которыми пересекается заданный, — кроме него самого. */
    public List<Sprite> hitting(Sprite sprite) {
        return sprites.stream().filter(other -> other != sprite && sprite.hits(other)).toList();
    }
}
