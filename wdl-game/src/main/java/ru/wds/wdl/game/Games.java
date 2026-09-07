package ru.wds.wdl.game;

import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.game.engine.Colors;
import ru.wds.wdl.game.engine.Engine;
import ru.wds.wdl.game.script.NativeCanvas;
import ru.wds.wdl.game.script.NativeSprite;
import ru.wds.wdl.game.script.NativeWorld;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Встроенный модуль {@code game}: движок WDGame, отданный скрипту.
 *
 * <pre>{@code
 * import game as g
 *
 * world = new g.World(title: "Пинг-понг", width: 800, height: 480)
 * ball = world.spawn("ball", x: 396, y: 236, w: 12, h: 12, shape: "oval")
 * ball.vx = 260
 *
 * world.onUpdate(def (dt) {
 *     if (ball.y < 0 || ball.bottom > world.height) ball.vy = -ball.vy
 * })
 * world.run()
 * }</pre>
 *
 * <h2>Зачем это здесь</h2>
 * Модуль не входит в стандартную библиотеку и никогда не войдёт: игровой движок
 * — не часть языка. Он живёт отдельным модулем сборки и служит <b>проверкой
 * встраивания</b>: игра — самое требовательное приложение из тех, что зовут скрипт,
 * потому что зовёт его шестьдесят раз в секунду, из своего цикла, и ждёт ответа
 * до следующего кадра. Всё, что в таком режиме окажется неудобным или дорогим,
 * должно быть найдено здесь, а не в чужой игре.
 *
 * <h2>Три типа и две функции</h2>
 * {@code World} — мир и его жизненный цикл, {@code Sprite} — объект на сцене,
 * {@code Canvas} — холст кадра. Классы стоят в области рядом с миром, а не прячутся
 * за фабриками: {@code s is game.Sprite} — законный вопрос, и ответить на него
 * можно только именем, которое у скрипта есть.
 *
 * <h2>Окна закрываются вместе с запуском</h2>
 * И, в отличие от {@code sys.gui}, закрытие модуля <b>не ждёт</b> пользователя.
 * Ждать нечего: игра держит поток скрипта в {@code world.run()}, и раз запуск дошёл
 * до закрытия — цикл кадров уже кончился. Оставшееся окно в этот момент означает
 * только одно: скрипт его создал и не показал.
 */
public final class Games {

    /** Имя модуля так, как его пишут в {@code import}. */
    public static final String NAME = "game";

    /** Миры этого запуска: их окна гасить, если скрипт этого не сделал. */
    private final List<Engine> worlds = new CopyOnWriteArrayList<>();

    private Games() {
    }

    /** Библиотека для этого запуска — своя на каждый, как и все остальные. */
    public static Library library() {
        return new Games().module();
    }

    /**
     * Реестр из одного модуля — то, что отдают движку рядом с {@code Sys.modules()}.
     * <p>
     * Карта, а не единственная фабрика, потому что так же выглядит набор
     * стандартной библиотеки: приложение складывает наборы, а не разбирает их
     * по одному имени.
     */
    public static Map<String, Supplier<Library>> registry() {
        return Map.of(NAME, Games::library);
    }

    private Library module() {
        return Module.named(NAME)
                .doc("движок WDGame: окно, сцена со спрайтами, цикл кадров и холст")
                .type("Sprite", scope -> NativeSprite.build())
                .doc("объект на сцене: положение, размер, скорость, цвет и форма")
                .type("Canvas", scope -> NativeCanvas.build())
                .doc("холст кадра: заливка, точки, фигуры, линии и текст")
                .type("World", scope -> NativeWorld.build(Module.typeIn(scope, "Sprite"),
                        Module.typeIn(scope, "Canvas"), worlds::add))
                .doc("мир игры: окно, сцена и жизненный цикл")

                .function("rgb", Signature.of(Param.required("red"), Param.required("green"),
                                Param.required("blue")),
                        (context, arguments, span) -> StringValue.of(Colors.hex(
                                (int) arguments.integer(0, "красный"),
                                (int) arguments.integer(1, "зелёный"),
                                (int) arguments.integer(2, "синий"))))
                .doc("цвет строкой #rrggbb по трём составляющим 0..255")

                .function("clamp", Signature.of(Param.required("value"), Param.required("min"),
                                Param.required("max")),
                        (context, arguments, span) -> {
                            NumberValue value = arguments.number(0, "значение");
                            double low = arguments.real(1, "минимум");
                            double high = arguments.real(2, "максимум");
                            if (low > high) {
                                throw arguments.bad(1, "минимум", "ожидалось значение не больше максимума");
                            }
                            double held = Math.max(low, Math.min(high, value.asDouble()));
                            // Целое остаётся целым — то же правило, что у арифметики
                            // языка: ракетка, загнанная в границы, не должна вдруг
                            // становиться дробной.
                            return value.isInteger() && held == Math.rint(held)
                                    ? IntValue.of((long) held) : FloatValue.of(held);
                        })
                .doc("загоняет значение в границы: ракетку — в поле, скорость — в предел")

                // Окна, оставшиеся от скрипта, гасятся вместе с запуском: висящее
                // окно не даёт процессу выйти, а решать это за пользователя игры
                // движок не вправе.
                .onClose(this::closeAll)
                .build();
    }

    private void closeAll() {
        for (Engine world : worlds) {
            world.close();
        }
        worlds.clear();
    }
}
