package ru.wds.wdl.game.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сцена, спрайты и ввод — то, что в движке считается, а не рисуется.
 * <p>
 * Тест обходится без окна, и это не хитрость: сцена про окно ничего не знает
 * (см. {@link Scene}), поэтому проверять правила можно там, где графической среды
 * нет вовсе, — на сборке.
 */
class SceneTest {

    @Test
    @DisplayName("Спрайт двигается на скорость, умноженную на время кадра")
    void spriteMovesBySpeed() {
        Scene scene = new Scene(200, 100, "#000000");
        Sprite ball = scene.spawn("ball", 10, 20, 4, 4, "#ffffff");
        ball.vx(100);
        ball.vy(-50);

        scene.integrate(0.5);

        assertEquals(60, ball.x(), 1e-9, "100 пикселей в секунду за полсекунды — это 50");
        assertEquals(-5, ball.y(), 1e-9);
    }

    @Test
    @DisplayName("Столкновение считается по прямоугольникам, касание краями им не считается")
    void collisionIsRectangular() {
        Scene scene = new Scene(200, 100, "#000000");
        Sprite left = scene.spawn("left", 0, 0, 10, 10, "#ffffff");
        Sprite right = scene.spawn("right", 10, 0, 10, 10, "#ffffff");

        assertFalse(left.hits(right), "прямоугольники сошлись краями, но не пересеклись");

        right.x(9.5);
        assertTrue(left.hits(right));
        assertTrue(right.hits(left), "пересечение симметрично");

        right.y(10);
        assertFalse(left.hits(right), "разошлись по вертикали");
    }

    @Test
    @DisplayName("Спрайт ищется по имени, а второе такое же имя — отказ")
    void spritesAreNamed() {
        Scene scene = new Scene(200, 100, "#000000");
        Sprite ball = scene.spawn("ball", 0, 0, 4, 4, "#ffffff");

        assertSame(ball, scene.get("ball"));
        assertNull(scene.get("paddle"));
        assertThrows(IllegalStateException.class,
                () -> scene.spawn("ball", 5, 5, 4, 4, "#ffffff"));
    }

    @Test
    @DisplayName("Снятый спрайт исчезает со сцены, но остаётся у того, кто его держит")
    void removedSpriteStaysReachable() {
        Scene scene = new Scene(200, 100, "#000000");
        Sprite brick = scene.spawn("brick", 0, 0, 4, 4, "#ffffff");

        assertTrue(brick.remove());
        assertFalse(brick.remove(), "второй раз снимать нечего");
        assertFalse(brick.alive());
        assertEquals(0, scene.count());
        assertNull(scene.get("brick"));
        // Ссылка живая: скрипт вправе спросить снятый спрайт о его положении.
        assertEquals(0, brick.x());
    }

    @Test
    @DisplayName("Снятый спрайт больше не сталкивается")
    void removedSpriteDoesNotCollide() {
        Scene scene = new Scene(200, 100, "#000000");
        Sprite first = scene.spawn("first", 0, 0, 10, 10, "#ffffff");
        Sprite second = scene.spawn("second", 5, 5, 10, 10, "#ffffff");

        assertTrue(first.hits(second));
        second.remove();
        assertFalse(first.hits(second));
    }

    @Test
    @DisplayName("Пересекающиеся спрайты перечисляются, сам спрайт в список не попадает")
    void hittingListsOthers() {
        Scene scene = new Scene(200, 100, "#000000");
        Sprite ball = scene.spawn("ball", 0, 0, 10, 10, "#ffffff");
        Sprite paddle = scene.spawn("paddle", 5, 0, 10, 10, "#ffffff");
        scene.spawn("far", 100, 100, 10, 10, "#ffffff");

        List<Sprite> hit = scene.hitting(ball);

        assertEquals(1, hit.size(), hit.toString());
        assertSame(paddle, hit.get(0));
    }

    @Test
    @DisplayName("Обёртка встраивающего слоя живёт в самом спрайте")
    void bindingIsKeptBySprite() {
        Scene scene = new Scene(200, 100, "#000000");
        Sprite ball = scene.spawn("ball", 0, 0, 4, 4, "#ffffff");
        Object wrapper = new Object();

        assertNull(ball.binding());
        ball.binding(wrapper);
        assertSame(wrapper, scene.get("ball").binding());
    }

    @Test
    @DisplayName("Нажатие видно опросом, а край кадра — только на одном кадре")
    void inputTellsHeldFromPressed() {
        Input input = new Input();
        input.beginFrame();
        input.keyDown(KeyEvent.VK_SPACE);

        assertTrue(input.down(KeyEvent.VK_SPACE));
        assertTrue(input.pressed(KeyEvent.VK_SPACE), "клавишу нажали в этом кадре");

        input.beginFrame();
        assertTrue(input.down(KeyEvent.VK_SPACE), "её всё ещё держат");
        assertFalse(input.pressed(KeyEvent.VK_SPACE), "но нажали не сейчас");

        input.keyUp(KeyEvent.VK_SPACE);
        assertFalse(input.down(KeyEvent.VK_SPACE));
    }

    @Test
    @DisplayName("Автоповтор клавиатуры не превращается в поток событий")
    void repeatedKeyDownIsOneEvent() {
        Input input = new Input();
        input.keyDown(KeyEvent.VK_LEFT);
        input.keyDown(KeyEvent.VK_LEFT);
        input.keyDown(KeyEvent.VK_LEFT);

        List<Input.Stroke> strokes = input.drain();

        assertEquals(List.of(new Input.Stroke("left", true)), strokes);
        assertTrue(input.drain().isEmpty(), "очередь после разбора пуста");
    }

    @Test
    @DisplayName("Потеря фокуса отпускает всё нажатое")
    void focusLossReleasesKeys() {
        Input input = new Input();
        input.keyDown(KeyEvent.VK_W);
        input.drain();

        input.forgetAll();

        assertFalse(input.down(KeyEvent.VK_W));
        assertEquals(List.of(new Input.Stroke("w", false)), input.drain());
    }

    @Test
    @DisplayName("Шаг движка ограничен сверху: длинный кадр не проносит объект сквозь стену")
    void loopClampsLongFrames() {
        GameLoop loop = new GameLoop(0);
        // Кадр без сна и без ожидания: важно, что даже очень длинный интервал
        // приходит игре обрезанным.
        assertTrue(loop.tick() <= GameLoop.MAX_STEP);
    }

    @Test
    @DisplayName("Сцена не бывает нулевого размера")
    void sceneRequiresPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> new Scene(0, 100, "#000000"));
        assertThrows(IllegalArgumentException.class, () -> new Scene(100, -1, "#000000"));
    }

    @Test
    @DisplayName("Движок без окна проигрывает кадры: ввод, движение, обработчик")
    void engineStepsWithoutWindow() {
        Engine engine = new Engine("тест", 100, 100, "#000000", 60);
        Sprite ball = engine.scene().spawn("ball", 0, 0, 4, 4, "#ffffff");
        ball.vx(10);
        int[] frames = new int[1];

        Game game = new Game() {
            @Override
            public void update(double dt) {
                frames[0]++;
            }
        };
        for (int i = 0; i < 10; i++) {
            engine.step(game, 0.1);
        }

        assertEquals(10, frames[0]);
        assertEquals(10, ball.x(), 1e-9, "десять кадров по 0.1 с при скорости 10");
        assertEquals(10, engine.frames());
        assertEquals(1.0, engine.time(), 1e-9);
        assertFalse(engine.running(), "цикла кадров не было — был шаг симуляции");
        assertNotNull(engine.input());
    }
}
