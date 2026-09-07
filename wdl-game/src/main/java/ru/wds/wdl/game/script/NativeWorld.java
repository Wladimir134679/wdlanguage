package ru.wds.wdl.game.script;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.bridge.Params;
import ru.wds.wdl.game.engine.Engine;
import ru.wds.wdl.game.engine.Keys;
import ru.wds.wdl.game.engine.Scene;
import ru.wds.wdl.game.engine.Sprite;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Класс {@code World}: мир игры и её жизненный цикл.
 *
 * <pre>{@code
 * world = new game.World(title: "Пинг-понг", width: 800, height: 480)
 * ball = world.spawn("ball", x: 396, y: 236, w: 12, h: 12, shape: "oval")
 *
 * world.onUpdate(def (dt) {
 *     if (ball.y < 0) ball.vy = -ball.vy
 * })
 * world.run()
 * }</pre>
 *
 * <h2>{@code run()} не возвращает управления, пока игра идёт</h2>
 * И это правильно: цикл кадров крутится в том самом потоке, который его позвал,
 * — значит, ошибка обработчика доходит до автора скрипта обычным образом,
 * с местом в коде и через {@code try}/{@code catch}, а не теряется в чужом потоке.
 * Окно при этом закрывается в любом случае.
 *
 * <h2>Игру можно прогнать без окна</h2>
 * {@code step(dt)} делает то же, что кадр, минус графика: ввод, движение,
 * {@code onUpdate}. Ради этого сцена и отделена от окна — правила игры
 * проверяются обычным тестом там, где графической среды нет вовсе.
 */
public final class NativeWorld {

    private NativeWorld() {
    }

    /**
     * Класс {@code World} этого запуска.
     *
     * @param spriteClass класс, которым оборачиваются спрайты, — тот же, что стоит
     *                    в области модуля: {@code s is game.Sprite} обязано быть правдой
     * @param canvasClass класс холста, приходящего в {@code onDraw}
     * @param opened      куда сообщить о созданном мире: закрытие запуска должно
     *                    гасить окна, оставшиеся от скрипта
     */
    public static NativeClass build(NativeClass spriteClass, NativeClass canvasClass,
                                    Consumer<Engine> opened) {
        return NativeClass.named("World")
                .doc("мир игры: окно, сцена со спрайтами и цикл кадров")
                .backing(ScriptGame.class)
                .init(Params.of()
                                .optional("title", "wdl game")
                                .optional("width", 800)
                                .optional("height", 600)
                                .optional("background", "#101418")
                                .optional("fps", 60),
                        (self, context, args, span) -> {
                            String title = args.string(0, "заголовок");
                            int width = size(args, 1, "ширина");
                            int height = size(args, 2, "высота");
                            String background = Fields.color(args, 3);
                            int fps = (int) args.integer(4, "частота кадров");
                            if (fps < 0 || fps > 1000) {
                                throw args.bad(4, "частота кадров",
                                        "ожидалось от 0 (без ограничения) до 1000");
                            }
                            Engine engine = new Engine(title, width, height, background, fps);
                            self.state(new ScriptGame(engine, spriteClass, canvasClass));
                            opened.accept(engine);
                            return NullValue.NULL;
                        })

                // --- свойства мира ------------------------------------------

                .property("width", (self, context, span) -> IntValue.of(scene(self).width()))
                .doc("ширина сцены в пикселях")
                .property("height", (self, context, span) -> IntValue.of(scene(self).height()))
                .doc("высота сцены в пикселях")

                .property("title", (self, context, span) ->
                        StringValue.of(engine(self).title()),
                        (self, value, context, span) ->
                                engine(self).title(Fields.text(value, "World.title", span)))
                .doc("заголовок окна; меняется и во время игры")

                .property("background", (self, context, span) ->
                        StringValue.of(scene(self).background()),
                        (self, value, context, span) ->
                                scene(self).background(Fields.color(value, "World.background", span)))
                .doc("цвет, которым заливается кадр перед отрисовкой")

                .property("fps", (self, context, span) -> FloatValue.of(engine(self).fps()))
                .doc("измеренная частота кадров за последнюю секунду")
                .property("frames", (self, context, span) -> IntValue.of(engine(self).frames()))
                .doc("сколько кадров прошло с начала игры")
                .property("time", (self, context, span) -> FloatValue.of(engine(self).time()))
                .doc("игровое время в секундах: сумма шагов, а не показания часов")
                .property("running", (self, context, span) -> BoolValue.of(engine(self).running()))
                .doc("идёт ли цикл кадров")
                .property("count", (self, context, span) -> IntValue.of(scene(self).count()))
                .doc("сколько спрайтов на сцене")

                .property("mouseX", (self, context, span) ->
                        IntValue.of(engine(self).input().mouseX()))
                .doc("положение мыши по горизонтали")
                .property("mouseY", (self, context, span) ->
                        IntValue.of(engine(self).input().mouseY()))
                .doc("положение мыши по вертикали")
                .property("mouseDown", (self, context, span) ->
                        BoolValue.of(engine(self).input().mouseDown()))
                .doc("нажата ли кнопка мыши")

                // --- спрайты ------------------------------------------------

                .method("spawn", Signature.of(Param.required("name"),
                                Param.optional("x", 0), Param.optional("y", 0),
                                Param.optional("w", 16), Param.optional("h", 16),
                                Param.optional("color", "#ffffff"),
                                Param.optional("shape", "rect")),
                        (self, context, args, span) -> spawn(self, args, span))
                .doc("заводит спрайт и отдаёт его")

                .method("get", Signature.of(Param.required("name")),
                        (self, context, args, span) -> {
                            Sprite found = scene(self).get(args.string(0, "имя"));
                            return found == null ? NullValue.NULL : game(self).wrap(found);
                        })
                .doc("спрайт по имени или null; это тот же объект, что вернул spawn")

                .method("all", Arity.exactly(0), (self, context, args, span) -> {
                    List<Value> sprites = new ArrayList<>();
                    for (Sprite sprite : scene(self).sprites()) {
                        sprites.add(game(self).wrap(sprite));
                    }
                    return ArrayValue.of(sprites);
                })
                .doc("все спрайты в порядке отрисовки")

                .method("remove", Signature.of(Param.required("sprite")),
                        (self, context, args, span) -> {
                            Sprite sprite = NativeSprite.spriteOf(args.at(0));
                            if (sprite == null) {
                                throw args.wrong(0, "спрайт", "ожидался спрайт");
                            }
                            return BoolValue.of(scene(self).remove(sprite));
                        })
                .doc("снимает спрайт со сцены")

                .method("clear", Arity.exactly(0), (self, context, args, span) -> {
                    scene(self).clear();
                    return NullValue.NULL;
                })
                .doc("убирает со сцены всё: перезапуск уровня")

                // --- ввод ---------------------------------------------------

                .method("down", Signature.of(Param.required("key")),
                        (self, context, args, span) -> BoolValue.of(
                                engine(self).input().down(key(args, 0))))
                .doc("держат ли клавишу прямо сейчас")

                .method("pressed", Signature.of(Param.required("key")),
                        (self, context, args, span) -> BoolValue.of(
                                engine(self).input().pressed(key(args, 0))))
                .doc("нажали ли клавишу именно в этом кадре")

                // --- жизненный цикл -----------------------------------------

                .method("onStart", Signature.of(Param.required("handler")),
                        (self, context, args, span) -> on(self, args, ScriptGame.Stage.START))
                .doc("обработчик перед первым кадром")

                .method("onUpdate", Signature.of(Param.required("handler")),
                        (self, context, args, span) -> on(self, args, ScriptGame.Stage.UPDATE))
                .doc("обработчик кадра; получает время кадра в секундах")

                .method("onDraw", Signature.of(Param.required("handler")),
                        (self, context, args, span) -> on(self, args, ScriptGame.Stage.DRAW))
                .doc("своя графика поверх спрайтов; получает холст")

                .method("onKey", Signature.of(Param.required("handler")),
                        (self, context, args, span) -> on(self, args, ScriptGame.Stage.KEY))
                .doc("нажатие и отпускание клавиши; получает имя и признак нажатия")

                .method("onStop", Signature.of(Param.required("handler")),
                        (self, context, args, span) -> on(self, args, ScriptGame.Stage.STOP))
                .doc("обработчик после штатного конца игры")

                .method("run", Arity.exactly(0), (self, context, args, span) -> {
                    ScriptGame game = game(self);
                    game.within(context, span);
                    try {
                        game.engine().run(game);
                    } catch (IllegalStateException refused) {
                        throw new WdlRuntimeError(ErrorKind.VALUE, span,
                                "World.run(): " + refused.getMessage());
                    }
                    return NullValue.NULL;
                })
                .doc("открывает окно и крутит игру, пока окно не закроют или не позовут stop()")

                .method("step", Signature.of(Param.optional("dt", 1.0 / 60)),
                        (self, context, args, span) -> {
                            double dt = args.real(0, "время кадра", 1.0 / 60);
                            if (dt < 0) {
                                throw args.bad(0, "время кадра", "ожидалось неотрицательное число");
                            }
                            ScriptGame game = game(self);
                            game.within(context, span);
                            game.engine().step(game, dt);
                            return NullValue.NULL;
                        })
                .doc("один шаг симуляции без окна: ввод, движение, onUpdate")

                .method("stop", Arity.exactly(0), (self, context, args, span) -> {
                    engine(self).stop();
                    return NullValue.NULL;
                })
                .doc("просит цикл закончиться: текущий кадр досчитается, следующего не будет")

                .method("close", Arity.exactly(0), (self, context, args, span) -> {
                    engine(self).close();
                    return NullValue.NULL;
                })
                .doc("закрывает окно; цикл кадров заканчивается сам")

                .build();
    }

    private static Value spawn(NativeInstance self, Args args, Span span) {
        String name = args.string(0, "имя");
        double x = args.real(1, "x", 0);
        double y = args.real(2, "y", 0);
        double width = args.real(3, "w", 16);
        double height = args.real(4, "h", 16);
        String color = args.has(5) ? Fields.color(args, 5) : "#ffffff";
        Sprite.Shape shape = Sprite.Shape.byName(args.string(6, "форма", "rect"));
        if (shape == null) {
            throw args.bad(6, "форма", "ожидалось rect или oval");
        }
        Sprite sprite;
        try {
            sprite = scene(self).spawn(name, x, y, width, height, color);
        } catch (IllegalStateException taken) {
            // Имя занято: на сцене уже стоит спрайт с таким именем. Это опечатка,
            // а не второй мяч, — поэтому отказ, а не тихое переименование.
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "World.spawn(): " + taken.getMessage());
        }
        sprite.shape(shape);
        return game(self).wrap(sprite);
    }

    private static Value on(NativeInstance self, Args args, ScriptGame.Stage stage) {
        game(self).handler(stage, args.function(0, "обработчик"));
        return NullValue.NULL;
    }

    /** Код клавиши по имени из скрипта; чужое имя — ошибка, а не вечное «не нажата». */
    private static int key(Args args, int index) {
        try {
            return Keys.codeOf(args.string(index, "клавиша"));
        } catch (IllegalArgumentException unknown) {
            throw args.bad(index, "клавиша", "ожидалось имя клавиши: left, right, up, down, "
                    + "space, enter, escape, a-z, 0-9, f1-f12");
        }
    }

    private static int size(Args args, int index, String role) {
        long value = args.integer(index, role);
        if (value <= 0 || value > 10_000) {
            throw args.bad(index, role, "ожидалось от 1 до 10000");
        }
        return (int) value;
    }

    private static ScriptGame game(NativeInstance self) {
        return self.state(ScriptGame.class);
    }

    private static Engine engine(NativeInstance self) {
        return game(self).engine();
    }

    private static Scene scene(NativeInstance self) {
        return engine(self).scene();
    }
}
