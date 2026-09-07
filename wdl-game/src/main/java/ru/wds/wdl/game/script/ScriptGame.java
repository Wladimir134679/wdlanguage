package ru.wds.wdl.game.script;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.game.engine.Engine;
import ru.wds.wdl.game.engine.Game;
import ru.wds.wdl.game.engine.Painter;
import ru.wds.wdl.game.engine.Sprite;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Игра, написанная на wdl: то, что движок видит как {@link Game}.
 * <p>
 * Весь мост между языком и движком — это один класс: движок зовёт свои пять методов,
 * а здесь на их месте оказываются функции скрипта. Ни {@code Engine}, ни {@code Scene}
 * про язык не знают, и снять этот слой можно, не тронув движка, — ради такой проверки
 * модуль и написан.
 *
 * <h2>Контекст берётся у вызова, а не у регистрации</h2>
 * {@code world.onUpdate(fn)} только запоминает функцию. Позвать её нужно от лица
 * <b>того</b> вызова, в котором идёт игра, — {@code run()} или {@code step()}, —
 * потому что от контекста зависит вывод: {@code println} внутри обработчика обязан
 * печатать туда же, куда печатает остальной скрипт.
 *
 * <h2>Обработчик вправе не брать аргументов</h2>
 * {@code onUpdate(def () { ... })} — законная запись: игре, которая двигает всё
 * скоростями, время кадра не нужно. Лишние аргументы отсекаются по {@code arity}
 * функции, а не приводят к отказу «ожидалось 0 аргументов, передан 1»: движок
 * предлагает данные, а не требует их принять.
 */
final class ScriptGame implements Game {

    private final Engine engine;
    private final NativeClass spriteClass;
    private final NativeClass canvasClass;

    private volatile FunctionValue onStart;
    private volatile FunctionValue onUpdate;
    private volatile FunctionValue onDraw;
    private volatile FunctionValue onKey;
    private volatile FunctionValue onStop;

    /** Вызов, от лица которого идёт игра: живёт от {@code run()}/{@code step()} до конца. */
    private volatile CallContext context;
    private volatile Span span;

    ScriptGame(Engine engine, NativeClass spriteClass, NativeClass canvasClass) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.spriteClass = Objects.requireNonNull(spriteClass, "spriteClass");
        this.canvasClass = Objects.requireNonNull(canvasClass, "canvasClass");
    }

    Engine engine() {
        return engine;
    }

    /** Запоминает обработчик; {@code null} снимает ранее заданный. */
    void handler(Stage stage, FunctionValue handler) {
        switch (stage) {
            case START -> onStart = handler;
            case UPDATE -> onUpdate = handler;
            case DRAW -> onDraw = handler;
            case KEY -> onKey = handler;
            case STOP -> onStop = handler;
        }
    }

    /** Ставит вызов, от лица которого пойдут обработчики. */
    void within(CallContext callContext, Span callSpan) {
        this.context = callContext;
        this.span = callSpan;
    }

    /**
     * Значение спрайта для скрипта — одно и то же на один спрайт.
     * <p>
     * Обёртка лежит в самом спрайте ({@link Sprite#binding()}), поэтому
     * {@code world.get("ball") == ball} истинно, а снятый со сцены спрайт уносит
     * её с собой: отдельной карты, которую пришлось бы чистить, нет.
     */
    Value wrap(Sprite sprite) {
        Object bound = sprite.binding();
        if (bound instanceof Value value) {
            return value;
        }
        NativeInstance wrapper = spriteClass.wrapping(sprite);
        sprite.binding(wrapper);
        return wrapper;
    }

    @Override
    public void start() {
        call(onStart);
    }

    @Override
    public void update(double dt) {
        call(onUpdate, FloatValue.of(dt));
    }

    @Override
    public void draw(Painter canvas) {
        // Обёртка холста создаётся на кадр: холст живёт кадром, а держать его
        // дольше незачем — рисовать вне кадра всё равно нельзя.
        call(onDraw, canvasClass.wrapping(canvas));
    }

    @Override
    public void key(String key, boolean down) {
        call(onKey, StringValue.of(key), BoolValue.of(down));
    }

    @Override
    public void stop() {
        call(onStop);
    }

    private void call(FunctionValue handler, Value... arguments) {
        FunctionValue function = handler;
        if (function == null) {
            return;
        }
        CallContext callContext = context;
        Span callSpan = span;
        if (callContext == null || callSpan == null) {
            throw new IllegalStateException("обработчик позван вне игры: "
                    + "мир не знает, от лица какого вызова его выполнять");
        }
        Callback.of(function, callContext, callSpan).call(fit(function, arguments));
    }

    /** Обрезает список аргументов до того, сколько функция готова принять. */
    private static List<Value> fit(FunctionValue function, Value[] arguments) {
        int max = function.arity().max();
        if (max >= arguments.length) {
            return List.of(arguments);
        }
        List<Value> trimmed = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            trimmed.add(arguments[i]);
        }
        return trimmed;
    }

    /** Точка жизненного цикла, на которую вешается обработчик. */
    enum Stage {
        START, UPDATE, DRAW, KEY, STOP
    }
}
