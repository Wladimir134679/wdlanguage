package ru.wds.wdl.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.wds.wdl.game.GameScripts.errorOf;
import static ru.wds.wdl.game.GameScripts.printed;

/**
 * Модуль {@code game} глазами скрипта.
 * <p>
 * Ни один тест не открывает окна: игра прогоняется {@code world.step(dt)} — тем же
 * кадром, что и в настоящем цикле, только без графики. Ради этого сцена и отделена
 * от окна.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GameTest {

    @Test
    @DisplayName("Модуль импортируется и приносит три типа")
    void moduleImports() {
        assertEquals("module game class World(title, width, height, background, fps) "
                + "class Sprite() class Canvas()", printed("""
                        import game as g
                        println(g)
                        println(g.World)
                        println(g.Sprite)
                        println(g.Canvas)
                        """));
    }

    @Test
    @DisplayName("Мир создаётся именованными аргументами, размеры доступны свойствами")
    void worldIsCreated() {
        assertEquals("Пинг-понг 320 240 0 false", printed("""
                import game as g

                world = new g.World(title: "Пинг-понг", width: 320, height: 240)
                println(world.title, " ", world.width, " ", world.height, " ",
                        world.count, " ", world.running)
                """));
    }

    @Test
    @DisplayName("Спрайт заводится миром, а его имя и форма читаются обратно")
    void spawnAndRead() {
        assertEquals("ball oval #ff0000 true", printed("""
                import game as g

                world = new g.World(width: 200, height: 100)
                ball = world.spawn("ball", x: 10, y: 20, w: 4, h: 4, color: "#ff0000", shape: "oval")
                println(ball.name, " ", ball.shape, " ", ball.color, " ", ball is g.Sprite)
                """));
    }

    @Test
    @DisplayName("Спрайт по имени — тот же самый объект, что вернул spawn")
    void getReturnsSameSprite() {
        assertEquals("true null", printed("""
                import game as g

                world = new g.World(width: 200, height: 100)
                ball = world.spawn("ball")
                println(world.get("ball") == ball, " ", world.get("нет такого"))
                """));
    }

    @Test
    @DisplayName("Шаг симуляции двигает спрайт на его скорость")
    void stepMovesSprite() {
        assertEquals("50.0 -25.0 10", printed("""
                import game as g

                world = new g.World(width: 200, height: 100)
                ball = world.spawn("ball", x: 0, y: 0)
                ball.vx = 100
                ball.vy = -50

                for (i = 0; i < 10; i += 1) world.step(0.05)

                println(ball.x, " ", ball.y, " ", world.frames)
                """));
    }

    @Test
    @DisplayName("Обработчик кадра получает время кадра и видит уже сдвинутый мир")
    void updateSeesMovedWorld() {
        assertEquals("край: 30.0 время кадра: 0.1 сдвинулись: 20.0", printed("""
                import game as g

                world = new g.World(width: 200, height: 100)
                ball = world.spawn("ball", x: 0, y: 0, w: 10, h: 10)
                ball.vx = 200

                seen = 0
                where = 0
                world.onUpdate(def (dt) {
                    seen = dt
                    where = ball.x
                    // Мир к этому моменту уже сдвинут — обработчик правит то, что видит.
                    println("край: ", ball.right)
                })

                world.step(0.1)
                println("время кадра: ", seen, " сдвинулись: ", where)
                """));
    }

    @Test
    @DisplayName("Обработчик вправе не брать аргументов")
    void handlerMayIgnoreArguments() {
        assertEquals("кадр кадр", printed("""
                import game as g

                world = new g.World(width: 100, height: 100)
                world.onUpdate(def () => println("кадр"))
                world.step()
                world.step()
                """));
    }

    @Test
    @DisplayName("Столкновение спрайтов видно скрипту")
    void collisionIsVisible() {
        assertEquals("false true", printed("""
                import game as g

                world = new g.World(width: 200, height: 100)
                ball = world.spawn("ball", x: 0, y: 0, w: 10, h: 10)
                wall = world.spawn("wall", x: 30, y: 0, w: 10, h: 100)
                ball.vx = 100

                println(ball.hits(wall))
                world.step(0.25)
                println(ball.hits(wall))
                """));
    }

    @Test
    @DisplayName("Снятый спрайт исчезает со сцены, но остаётся у скрипта")
    void removedSpriteStaysReachable() {
        assertEquals("2 true 1 false brick", printed("""
                import game as g

                world = new g.World(width: 200, height: 100)
                ball = world.spawn("ball")
                brick = world.spawn("brick", x: 50)

                println(world.count, " ", brick.alive)
                brick.remove()
                println(world.count, " ", brick.alive, " ", brick.name)
                """));
    }

    @Test
    @DisplayName("Все спрайты перечисляются в порядке создания")
    void allListsSprites() {
        assertEquals("ball paddle 2", printed("""
                import game as g

                world = new g.World(width: 200, height: 100)
                world.spawn("ball")
                world.spawn("paddle")

                for (s in world.all()) print(s.name, " ")
                println(len(world.all()))
                """));
    }

    @Test
    @DisplayName("Опрос клавиш работает и без окна: до нажатия всё отпущено")
    void keysAreNotPressed() {
        assertEquals("false false", printed("""
                import game as g

                world = new g.World(width: 100, height: 100)
                println(world.down("left"), " ", world.pressed("space"))
                """));
    }

    @Test
    @DisplayName("Вспомогательные функции: цвет по составляющим и загон в границы")
    void helpers() {
        assertEquals("#7ec8ff 0 10 5", printed("""
                import game as g

                println(g.rgb(126, 200, 255), " ", g.clamp(-3, 0, 10), " ",
                        g.clamp(15, 0, 10), " ", g.clamp(5, 0, 10))
                """));
    }

    @Test
    @DisplayName("Занятое имя спрайта — ошибка, а не второй мяч")
    void duplicateNameIsRefused() {
        assertTrue(errorOf("""
                import game as g
                world = new g.World(width: 100, height: 100)
                world.spawn("ball")
                world.spawn("ball")
                """).getMessage().contains("ball"));
    }

    @Test
    @DisplayName("Непонятный цвет, форма и клавиша отвечают ошибкой значения")
    void wrongValuesAreRefused() {
        assertTrue(errorOf("""
                import game as g
                world = new g.World(width: 100, height: 100)
                world.spawn("ball", color: "бирюзовый")
                """).getMessage().contains("цвет"));

        assertTrue(errorOf("""
                import game as g
                world = new g.World(width: 100, height: 100)
                world.spawn("ball", shape: "треугольник")
                """).getMessage().contains("rect"));

        assertTrue(errorOf("""
                import game as g
                world = new g.World(width: 100, height: 100)
                world.down("lft")
                """).getMessage().contains("клавиша"));
    }

    @Test
    @DisplayName("Ошибка обработчика доходит до скрипта обычным образом")
    void handlerErrorReachesScript() {
        assertEquals("поймали: мяч улетел", printed("""
                import game as g

                world = new g.World(width: 100, height: 100)
                world.onUpdate(def (dt) { throw new ValueError("мяч улетел") })

                try {
                    world.step()
                } catch (e) {
                    println("поймали: ", e.message)
                }
                """));
    }

    @Test
    @DisplayName("Правила игры проверяются без окна: мяч отбивается от стен и от ракетки")
    void pongRulesRunHeadless() {
        assertEquals("отскок от стены: true, счёт: 1", printed("""
                import game as g

                world = new g.World(width: 200, height: 100)
                ball = world.spawn("ball", x: 100, y: 50, w: 8, h: 8, shape: "oval")
                paddle = world.spawn("paddle", x: 10, y: 0, w: 6, h: 100)
                ball.vx = -300
                ball.vy = -400
                bounced = false
                score = 0

                world.onUpdate(def (dt) {
                    if (ball.y < 0) {
                        ball.y = 0
                        ball.vy = -ball.vy
                        bounced = true
                    }
                    if (ball.hits(paddle)) {
                        ball.x = paddle.right
                        ball.vx = -ball.vx
                        score += 1
                    }
                })

                for (i = 0; i < 60; i += 1) world.step(1.0 / 60)
                println("отскок от стены: ", bounced, ", счёт: ", score)
                """));
    }
}
