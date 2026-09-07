package ru.wds.wdl.game.engine;

import java.util.Objects;

/**
 * Игровой объект: прямоугольник со скоростью, цветом и именем.
 * <p>
 * Один вид объекта на весь движок, и это сознательно. Настоящему движку нужны
 * компоненты, слои и иерархия преобразований; здесь же проверяется <b>встраивание
 * скрипта в игру</b>, а не игра. Прямоугольник с координатами закрывает арканоид,
 * змейку и пинг-понг — то есть весь класс задач, на которых видно, удобно ли
 * управлять миром из wdl.
 *
 * <h2>Состояние изменяемо и объявлено {@code volatile}</h2>
 * Позиция меняется каждый кадр — движком при интегрировании и скриптом
 * в обработчике. Потоков при этом два в самом простом случае: цикл кадров идёт
 * в потоке, позвавшем {@code run()}, а спрайт вправе тронуть поток, заведённый
 * {@code sys.thread}. Правило проекта — «изменяемое состояние запуска
 * потокобезопасно», и для независимых чисел это ровно {@code volatile}:
 * согласованности пары {@code x, y} между собой движок не обещает, и обещать
 * не может — их и меняют по одному.
 */
public final class Sprite {

    /** Форма, которой спрайт рисуется. Больше двух не нужно: остальное рисует скрипт. */
    public enum Shape {

        RECT("rect"),
        OVAL("oval");

        private final String title;

        Shape(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        /** Форма по имени из скрипта; {@code null}, если имя чужое. */
        public static Shape byName(String name) {
            for (Shape shape : values()) {
                if (shape.title.equalsIgnoreCase(name)) {
                    return shape;
                }
            }
            return null;
        }
    }

    private final String name;
    private volatile double x;
    private volatile double y;
    private volatile double width;
    private volatile double height;
    private volatile double vx;
    private volatile double vy;
    private volatile String color;
    private volatile Shape shape = Shape.RECT;
    private volatile boolean visible = true;
    /**
     * Снят ли спрайт со сцены.
     * <p>
     * Отдельный признак, а не только отсутствие в списке: у скрипта ссылка на спрайт
     * остаётся и после {@code remove()}, и честный ответ на {@code ball.alive} лучше,
     * чем молчаливое движение объекта, которого на сцене нет.
     */
    private volatile boolean removed;

    /** Сцена, на которой стоит спрайт: по ней работает {@link #remove()}. */
    private volatile Scene scene;

    /**
     * Место для того, кто движок встроил: обёртка спрайта в чужом языке.
     * <p>
     * Движок в это поле не смотрит и типа его не знает — {@code Object} здесь
     * не лень, а граница. Нужно оно затем, чтобы {@code world.get("ball")}
     * отдавал скрипту <b>тот же</b> объект, что и {@code world.spawn("ball", ...)}:
     * без такого поля обёртки пришлось бы держать в отдельной карте и вычищать
     * оттуда руками на каждом {@code remove}.
     */
    private volatile Object binding;

    Sprite(String name, double x, double y, double width, double height, String color) {
        this.name = Objects.requireNonNull(name, "name");
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.color = Objects.requireNonNull(color, "color");
    }

    public String name() {
        return name;
    }

    /** Объект встраивающего слоя, привязанный к спрайту, или {@code null}. */
    public Object binding() {
        return binding;
    }

    public void binding(Object value) {
        this.binding = value;
    }

    public double x() {
        return x;
    }

    public void x(double value) {
        this.x = value;
    }

    public double y() {
        return y;
    }

    public void y(double value) {
        this.y = value;
    }

    public double width() {
        return width;
    }

    public void width(double value) {
        this.width = value;
    }

    public double height() {
        return height;
    }

    public void height(double value) {
        this.height = value;
    }

    public double vx() {
        return vx;
    }

    public void vx(double value) {
        this.vx = value;
    }

    public double vy() {
        return vy;
    }

    public void vy(double value) {
        this.vy = value;
    }

    public String color() {
        return color;
    }

    public void color(String value) {
        this.color = Objects.requireNonNull(value, "color");
    }

    public Shape shape() {
        return shape;
    }

    public void shape(Shape value) {
        this.shape = Objects.requireNonNull(value, "shape");
    }

    public boolean visible() {
        return visible;
    }

    public void visible(boolean value) {
        this.visible = value;
    }

    public boolean alive() {
        return !removed;
    }

    void markRemoved() {
        this.removed = true;
    }

    void scene(Scene owner) {
        this.scene = owner;
    }

    /** Убирает спрайт со своей сцены; {@code false}, если его там уже нет. */
    public boolean remove() {
        Scene owner = scene;
        return owner != null && owner.remove(this);
    }

    public double right() {
        return x + width;
    }

    public double bottom() {
        return y + height;
    }

    public double centerX() {
        return x + width / 2;
    }

    public double centerY() {
        return y + height / 2;
    }

    public void moveTo(double newX, double newY) {
        this.x = newX;
        this.y = newY;
    }

    public void move(double dx, double dy) {
        this.x += dx;
        this.y += dy;
    }

    /** Останавливает движение, не трогая позицию. */
    public void halt() {
        this.vx = 0;
        this.vy = 0;
    }

    /**
     * Шаг движения: позиция меняется на скорость, умноженную на время кадра.
     * <p>
     * Скорость задаётся в пикселях <b>в секунду</b>, а не в пикселях за кадр.
     * Это и есть причина, по которой {@code dt} доходит до скрипта: игра, написанная
     * в кадрах, на другой машине идёт с другой скоростью, и виноват в этом
     * оказывается движок.
     */
    void step(double dt) {
        this.x += vx * dt;
        this.y += vy * dt;
    }

    /**
     * Пересекаются ли прямоугольники двух спрайтов.
     * <p>
     * Именно прямоугольники, даже у формы {@link Shape#OVAL}: форма — это способ
     * нарисовать, а не способ столкнуться. Овальные столкновения стоят дороже
     * и в задачах, ради которых движок написан, не нужны — зато «мяч круглый,
     * а бьётся углом» было бы сюрпризом, поэтому это записано здесь.
     */
    public boolean hits(Sprite other) {
        Objects.requireNonNull(other, "other");
        return alive() && other.alive()
                && x < other.right() && right() > other.x
                && y < other.bottom() && bottom() > other.y;
    }

    /** Лежит ли точка внутри спрайта — вопрос про мышь. */
    public boolean contains(double pointX, double pointY) {
        return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
    }

    @Override
    public String toString() {
        return "sprite " + name + " (" + Math.round(x) + ", " + Math.round(y) + ")";
    }
}
