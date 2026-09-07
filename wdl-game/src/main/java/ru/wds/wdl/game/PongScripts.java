package ru.wds.wdl.game;

import ru.wds.wdl.api.Stdlib;
import ru.wds.wdl.api.WdlCallable;
import ru.wds.wdl.api.WdlEngine;
import ru.wds.wdl.api.WdlInstance;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.game.engine.Painter;
import ru.wds.wdl.game.engine.Sprite;
import ru.wds.wdl.game.pong.Match;
import ru.wds.wdl.game.pong.PongHooks;
import ru.wds.wdl.game.script.NativeMatch;
import ru.wds.wdl.module.ModuleSource;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.StringValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Встроенные скрипты пинг-понга: то, чем игра отвечает на свои же вопросы.
 * <p>
 * Здесь встраивание и происходит. {@link ru.wds.wdl.game.pong.Pong} знает интерфейс
 * {@link PongHooks} из пяти методов; за этими пятью методами стоят функции wdl,
 * лежащие в ресурсах модуля — {@code pong.wdl} и три файла рядом с ним. Заменить
 * их можно, не пересобирая игру, а снять весь этот класс — не тронув ни игры,
 * ни движка.
 *
 * <h2>Один запуск на все скрипты</h2>
 * Файлов четыре, а {@link WdlInstance} — один: корневой {@code pong.wdl} импортирует
 * остальные обычным {@code import}, а исходники к нему приходят не из каталога,
 * а из ресурсов jar — {@link ModuleSource} для того и один метод. Разложить хуки
 * по отдельным запускам было бы хуже, чем кажется: класс — значение запуска, и один
 * и тот же спрайт получил бы в каждом из них свою обёртку своего класса, а
 * {@code ball is g.Sprite} перестал бы значить хоть что-нибудь.
 *
 * <h2>Обёртка на объект одна</h2>
 * Мяч заворачивается один раз за игру: значение лежит в самом спрайте
 * ({@link Sprite#binding()}), поэтому скрипт, положивший {@code ball} в переменную,
 * получит тот же объект и на следующем кадре. Холст — исключение: он живёт кадром,
 * и обёртка ему нужна на кадр.
 */
public final class PongScripts implements PongHooks, AutoCloseable {

    /** Каталог ресурсов со скриптами — рядом с этим классом. */
    private static final String HOME = "pong/";
    /** Корневой скрипт: в нём игра ищет свои пять имён. */
    private static final String ROOT = "pong.wdl";

    private final WdlInstance instance;
    private final NativeClass spriteClass;
    private final NativeClass canvasClass;
    private final NativeClass matchClass;

    private final WdlCallable serve;
    private final WdlCallable bounce;
    private final WdlCallable rival;
    private final WdlCallable point;
    private final WdlCallable hud;

    /** Обёртка партии: партия одна на игру, значит и обёртка одна. */
    private Match wrappedMatch;
    private Value matchValue;

    private PongScripts(WdlInstance instance, Games.Types types, NativeClass matchClass) {
        this.instance = instance;
        this.spriteClass = types.sprite();
        this.canvasClass = types.canvas();
        this.matchClass = matchClass;
        this.serve = hook("serve");
        this.bounce = hook("bounce");
        this.rival = hook("rival");
        this.point = hook("point");
        this.hud = hook("hud");
    }

    /**
     * Собирает движок wdl, выполняет встроенные скрипты и достаёт из них хуки.
     * <p>
     * Скрипты выполняются <b>до</b> первого кадра — на этом шаге они объявляют
     * свои константы и функции. Дальше игра только зовёт: разбор и выполнение
     * файла шестьдесят раз в секунду не повторяются.
     *
     * @param output куда печатает {@code println} из скриптов
     */
    public static PongScripts embedded(Output output) {
        Objects.requireNonNull(output, "output");
        // Классы модуля собираются при его установке, а нужны они здесь: ими
        // заворачиваются спрайты игры. Массив — самая короткая передача «наружу
        // из лямбды», и живёт он до конца этого метода.
        Games.Types[] types = new Games.Types[1];
        WdlEngine engine = WdlEngine.builder()
                // Стандартная библиотека — потому что правила пишутся как обычный
                // скрипт: соперник считает abs, подача берёт Random.
                .stdlib(Stdlib.STANDARD)
                .output(output)
                // Модули берутся из ресурсов, а не из каталога: игра, разложенная
                // по jar, файловой системы под собой не имеет.
                .sources(PongScripts::resource)
                .module(Games.NAME, () -> Games.library(found -> types[0] = found))
                .build();

        WdlInstance instance = engine.compile(read(ROOT), ROOT).instance();
        // Тип партии кладёт приложение: движок про пинг-понг не знает, и в модуле
        // game ему места нет. Класть надо до выполнения — иначе скрипт его не увидит.
        NativeClass matchClass = NativeMatch.build();
        instance.defineValue("Match", matchClass);
        try {
            instance.execute();
            if (types[0] == null) {
                throw new IllegalStateException("встроенный скрипт " + ROOT
                        + " обязан импортировать модуль game: из него берутся типы "
                        + "Sprite и Canvas");
            }
            return new PongScripts(instance, types[0], matchClass);
        } catch (RuntimeException failure) {
            instance.close();
            throw failure;
        }
    }

    @Override
    public void serve(Match match, Sprite ball) {
        serve.call(match(match), wrap(ball));
    }

    @Override
    public void bounce(Match match, Sprite ball, Sprite paddle) {
        bounce.call(match(match), wrap(ball), wrap(paddle));
    }

    @Override
    public double rival(Match match, Sprite ball, Sprite paddle) {
        Value answer = rival.call(match(match), wrap(ball), wrap(paddle));
        if (answer instanceof NumberValue speed) {
            return speed.asDouble();
        }
        throw wrongAnswer("rival", "скорость числом", answer);
    }

    @Override
    public boolean point(Match match, Match.Side side) {
        Value answer = point.call(match(match), StringValue.of(side.title()));
        if (answer instanceof BoolValue over) {
            return over.value();
        }
        throw wrongAnswer("point", "true или false", answer);
    }

    @Override
    public void hud(Match match, Painter canvas) {
        // Холст живёт кадром, обёртка ему нужна на кадр: держать её дольше значило бы
        // обещать, что рисовать можно и между кадрами.
        hud.call(match(match), canvasClass.wrapping(canvas));
    }

    /** Останавливает потоки скриптов и закрывает их модули. */
    @Override
    public void close() {
        instance.close();
    }

    private Value match(Match match) {
        if (wrappedMatch != match) {
            wrappedMatch = match;
            matchValue = matchClass.wrapping(match);
        }
        return matchValue;
    }

    private Value wrap(Sprite sprite) {
        if (sprite.binding() instanceof Value ready) {
            return ready;
        }
        Value wrapper = spriteClass.wrapping(sprite);
        sprite.binding(wrapper);
        return wrapper;
    }

    private WdlCallable hook(String name) {
        try {
            return instance.function(name);
        } catch (IllegalStateException missing) {
            throw new IllegalStateException("встроенный скрипт " + ROOT + ": "
                    + missing.getMessage(), missing);
        }
    }

    private static IllegalStateException wrongAnswer(String name, String expected, Value answer) {
        return new IllegalStateException("встроенный скрипт " + ROOT + ": функция '" + name
                + "' должна вернуть " + expected + ", а вернула "
                + answer.type().title() + " (" + answer.display() + ")");
    }

    /** Исходник модуля из ресурсов: ключ {@code pong/rules} — это {@code pong/rules.wdl}. */
    private static Source resource(String key) {
        String file = key + ".wdl";
        String text = find(file);
        return text == null ? null : new Source(file, text);
    }

    /** Обязательный ресурс: его отсутствие — сломанная сборка, а не ошибка скрипта. */
    private static String read(String file) {
        String text = find(HOME + file);
        if (text == null) {
            throw new IllegalStateException("встроенный скрипт не найден в ресурсах: "
                    + HOME + file);
        }
        return text;
    }

    private static String find(String file) {
        try (InputStream stream = PongScripts.class.getResourceAsStream(file)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("не прочитать встроенный скрипт " + file, unreadable);
        }
    }
}
