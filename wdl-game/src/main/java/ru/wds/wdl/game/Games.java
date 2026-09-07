package ru.wds.wdl.game;

import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.game.engine.Colors;
import ru.wds.wdl.game.script.NativeCanvas;
import ru.wds.wdl.game.script.NativeSprite;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Встроенный модуль {@code game}: словарь движка WDGame для скрипта.
 *
 * <pre>{@code
 * import game as g
 *
 * def speed(match, ball, paddle) {
 *     gap = ball.centerY - paddle.centerY
 *     return g.clamp(gap * 6, -320, 320)
 * }
 * }</pre>
 *
 * <h2>Модуль не создаёт игру, а описывает её</h2>
 * Мира, сцены и цикла кадров здесь нет — они у приложения. Скрипт получает готовые
 * объекты аргументами хука и работает с ними: {@code Sprite} — спрайт на сцене,
 * {@code Canvas} — холст кадра. Так и выглядит встраивание в чужую игру: движок
 * зовёт скрипт, а не наоборот, и класть в скриптовый модуль кнопку «запустить игру»
 * не за чем.
 *
 * <h2>Классы отдаются хозяину</h2>
 * Обёртки для своих спрайтов делает приложение, а класс — один на запуск, поэтому
 * взять чужой нельзя: {@code ball is g.Sprite} обязан отвечать правду. Отсюда
 * {@link #library(Consumer)}: собранные классы уезжают хозяину сразу после
 * установки модуля, и заворачивает он ими же.
 */
public final class Games {

    /** Имя модуля так, как его пишут в {@code import}. */
    public static final String NAME = "game";

    /** Классы этого запуска: ими приложение заворачивает свои объекты. */
    public record Types(NativeClass sprite, NativeClass canvas) {

        public Types {
            Objects.requireNonNull(sprite, "sprite");
            Objects.requireNonNull(canvas, "canvas");
        }
    }

    private Games() {
    }

    /** Библиотека для этого запуска — своя на каждый, как и все остальные. */
    public static Library library() {
        return library(types -> {
        });
    }

    /**
     * То же самое, но собранные классы уезжают слушателю.
     * <p>
     * Слушатель зовётся при установке модуля, то есть на {@code import game}
     * в скрипте, — до того, как приложение позовёт первый хук.
     */
    public static Library library(Consumer<Types> listener) {
        Objects.requireNonNull(listener, "listener");
        return Module.named(NAME)
                .doc("словарь движка WDGame: спрайты, холст и мелочи к ним")
                .type("Sprite", scope -> NativeSprite.build())
                .doc("объект на сцене: положение, размер, скорость, цвет и форма")
                .type("Canvas", scope -> NativeCanvas.build())
                .doc("холст кадра: заливка, точки, фигуры, линии и текст")

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

                // Классы уезжают хозяину последним шагом установки: к этому моменту
                // оба уже стоят в области модуля.
                .install(scope -> listener.accept(new Types(
                        Module.typeIn(scope, "Sprite"), Module.typeIn(scope, "Canvas"))))
                .build();
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
}
