package ru.wds.wdl.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.api.samples.Game;
import ru.wds.wdl.bridge.reflect.FromJava;
import ru.wds.wdl.bridge.reflect.JavaPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Главный сценарий встраивания: приложение отдаёт скрипту свой объект и свой тип.
 * <p>
 * Проверяется не перекладывание вызовов, а граница: что объект приложения виден
 * скрипту как обычное значение, что каждому запуску достаётся свой мост
 * и что отказ адресован тому, кто ошибся, — вызвавшему API, а не автору скрипта.
 */
class HostObjectsTest {

    @Test
    @DisplayName("Объект приложения виден скрипту, и скрипт зовёт его методы")
    void hostObjectIsCallable() {
        Game game = new Game();
        WdlEngine engine = WdlEngine.builder()
                .expose(Game.class)
                .define("game", game)
                .build();

        try (WdlInstance run = engine.compile("""
                def onTick(count) {
                    for (i = 0; i < count; i = i + 1) { game.spawn("orc", i, i * 2) }
                    return game.tick();
                }
                """).instance()) {
            run.execute();
            assertEquals(1L, run.function("onTick").invoke(2));
            assertEquals(2L, run.function("onTick").invoke(0));
        }
        assertEquals(List.of("orc@0,0", "orc@1,2"), game.getSpawned());
    }

    @Test
    @DisplayName("Открытый тип скрипт создаёт сам — под именем, которое ему дали")
    void exposedTypeIsConstructible() {
        WdlEngine engine = WdlEngine.builder().expose(Game.class, "Engine").build();
        assertEquals(1L, Values.toJava(engine.run("""
                own = new Engine()
                own.spawn("goblin", 1, 1)
                own.tick()
                """)));
    }

    @Test
    @DisplayName("Схема описывает тип по частям: видно ровно перечисленное")
    void schemaLimitsMembers() {
        Game game = new Game();
        WdlEngine engine = WdlEngine.builder()
                .expose(() -> FromJava.of(Game.class).as("Engine").method("tick").noConstructors())
                .define("game", game)
                .build();
        assertEquals(1L, Values.toJava(engine.run("game.tick()")));
        assertTrue(assertThrows(WdlException.class, () -> engine.run("game.spawn(\"orc\", 0, 0)"))
                .getMessage().contains("spawn"));
    }

    @Test
    @DisplayName("Каждому запуску — свой мост: объект приложения общий, классы нет")
    void bridgeBelongsToRun() {
        Game game = new Game();
        WdlScript script = WdlEngine.builder()
                .expose(Game.class)
                .define("game", game)
                .build()
                .compile("game.tick()");

        try (WdlInstance first = script.instance(); WdlInstance second = script.instance()) {
            assertEquals(1L, Values.toJava(first.execute()));
            assertEquals(2L, Values.toJava(second.execute()));
            // Класс языка у каждого запуска свой, хотя Java-тип за ним один.
            assertTrue(first.eval("typeof(game)").display().equals("object"));
        }
    }

    @Test
    @DisplayName("Объект без открытого типа — отказ на сборке движка, а не на запуске")
    void objectWithoutTypeIsRefusedEarly() {
        WdlEngine.Builder builder = WdlEngine.builder().define("game", new Game());
        IllegalStateException refused = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(refused.getMessage().contains("game"), refused.getMessage());
        assertTrue(refused.getMessage().contains("expose"), refused.getMessage());
    }

    @Test
    @DisplayName("Политика с обёртками неизвестных типов принимает объект без expose")
    void wrapUnknownAcceptsAnyObject() {
        WdlEngine engine = WdlEngine.builder()
                .policy(JavaPolicy.builder().wrapUnknown(true).build())
                .define("game", new Game())
                .build();
        assertEquals(1L, Values.toJava(engine.run("game.tick()")));
    }

    @Test
    @DisplayName("Строки и числа по-прежнему переводятся сами, без всякого моста")
    void plainValuesNeedNoBridge() {
        WdlEngine engine = WdlEngine.builder().define("title", "демо").define("level", 3).build();
        // eval, а не run: голое выражение инструкцией языка не является.
        assertEquals("демо 3", Values.toJava(engine.eval("title + \" \" + level")));
    }

    @Test
    @DisplayName("Имя скрипта доходит до сообщения об ошибке")
    void scriptNameReachesDiagnostics() {
        WdlEngine engine = WdlEngine.builder().build();
        WdlException failed = assertThrows(WdlException.class,
                () -> engine.compile("x = = 1", "handlers/onTick.wdl"));
        assertTrue(failed.getMessage().contains("handlers/onTick.wdl"), failed.getMessage());
    }
}
