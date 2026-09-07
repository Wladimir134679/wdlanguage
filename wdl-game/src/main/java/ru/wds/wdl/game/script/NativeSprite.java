package ru.wds.wdl.game.script;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.game.engine.Sprite;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.function.ObjDoubleConsumer;
import java.util.function.ToDoubleFunction;

/**
 * Класс {@code Sprite}: игровой объект глазами скрипта.
 *
 * <pre>{@code
 * ball = world.spawn("ball", x: 100, y: 100, w: 12, h: 12, shape: "oval")
 * ball.vx = 260
 * if (ball.hits(paddle)) ball.vx = -ball.vx
 * }</pre>
 *
 * <h2>Всё — свойства, ни одного поля</h2>
 * Класс объявлен обёрткой ({@code wrapper}), и это не формальность. Положение
 * спрайта меняет движок — каждый кадр, — а поле экземпляра хранит снимок, сделанный
 * при создании. Такой снимок устарел бы к первому же обращению, а запись в него
 * ({@code ball.x = 10}) не дошла бы до движка вовсе. Свойство — это пара вызовов,
 * поэтому ответ всегда свежий, а присваивание доходит.
 *
 * <h2>Создаются миром, а не {@code new}</h2>
 * Спрайт без сцены не имеет смысла: его некому двигать и негде рисовать. Поэтому
 * конструктора у класса нет, а объекты приходят из {@code world.spawn(...)}
 * и {@code world.get(...)} — причём один и тот же объект на один спрайт
 * (см. {@link Sprite#binding()}).
 */
public final class NativeSprite {

    private NativeSprite() {
    }

    /** Класс {@code Sprite} этого запуска. */
    public static NativeClass build() {
        NativeClass.Builder builder = NativeClass.named("Sprite")
                .doc("игровой объект: положение, размер, скорость, цвет и форма")
                .wrapper(Sprite.class);

        number(builder, "x", Sprite::x, Sprite::x, "координата левого края");
        number(builder, "y", Sprite::y, Sprite::y, "координата верхнего края");
        number(builder, "w", Sprite::width, Sprite::width, "ширина");
        number(builder, "h", Sprite::height, Sprite::height, "высота");
        number(builder, "vx", Sprite::vx, Sprite::vx, "скорость по горизонтали, пикселей в секунду");
        number(builder, "vy", Sprite::vy, Sprite::vy, "скорость по вертикали, пикселей в секунду");

        return builder
                .property("name", (self, context, span) -> StringValue.of(sprite(self).name()))
                .doc("имя, под которым спрайт заведён на сцене")

                .property("color", (self, context, span) -> StringValue.of(sprite(self).color()),
                        (self, value, context, span) ->
                                sprite(self).color(Fields.color(value, "Sprite.color", span)))
                .doc("цвет: #rrggbb, #rgb, #aarrggbb или имя цвета")

                .property("shape", (self, context, span) ->
                        StringValue.of(sprite(self).shape().title()),
                        (self, value, context, span) -> shape(self, value, span))
                .doc("форма отрисовки: rect или oval")

                .property("visible", (self, context, span) ->
                        BoolValue.of(sprite(self).visible()),
                        (self, value, context, span) ->
                                sprite(self).visible(Fields.flag(value, "Sprite.visible", span)))
                .doc("рисовать ли спрайт; невидимый продолжает двигаться и сталкиваться")

                .property("right", (self, context, span) -> FloatValue.of(sprite(self).right()))
                .doc("координата правого края: x + w")
                .property("bottom", (self, context, span) -> FloatValue.of(sprite(self).bottom()))
                .doc("координата нижнего края: y + h")
                .property("centerX", (self, context, span) -> FloatValue.of(sprite(self).centerX()))
                .doc("центр по горизонтали")
                .property("centerY", (self, context, span) -> FloatValue.of(sprite(self).centerY()))
                .doc("центр по вертикали")
                .property("alive", (self, context, span) -> BoolValue.of(sprite(self).alive()))
                .doc("стоит ли спрайт ещё на сцене")

                .method("moveTo", Signature.of(Param.required("x"), Param.required("y")),
                        (self, context, args, span) -> {
                            sprite(self).moveTo(args.real(0, "x"), args.real(1, "y"));
                            return NullValue.NULL;
                        })
                .doc("переносит спрайт в точку")

                .method("move", Signature.of(Param.required("dx"), Param.required("dy")),
                        (self, context, args, span) -> {
                            sprite(self).move(args.real(0, "dx"), args.real(1, "dy"));
                            return NullValue.NULL;
                        })
                .doc("сдвигает спрайт на заданное расстояние")

                .method("stop", Arity.exactly(0), (self, context, args, span) -> {
                    sprite(self).halt();
                    return NullValue.NULL;
                })
                .doc("обнуляет скорость, не трогая положение")

                .method("hits", Signature.of(Param.required("other")),
                        (self, context, args, span) -> {
                            Sprite other = spriteOf(args.at(0));
                            if (other == null) {
                                throw args.wrong(0, "спрайт", "ожидался спрайт");
                            }
                            return BoolValue.of(sprite(self).hits(other));
                        })
                .doc("пересекаются ли прямоугольники двух спрайтов")

                .method("contains", Signature.of(Param.required("x"), Param.required("y")),
                        (self, context, args, span) -> BoolValue.of(
                                sprite(self).contains(args.real(0, "x"), args.real(1, "y"))))
                .doc("лежит ли точка внутри спрайта — вопрос про мышь")

                .method("remove", Arity.exactly(0), (self, context, args, span) ->
                        BoolValue.of(sprite(self).remove()))
                .doc("снимает спрайт со сцены; ссылка на него у скрипта остаётся")

                .build();
    }

    /**
     * Спрайт, лежащий за значением скрипта, или {@code null}.
     * <p>
     * Спрашивается у {@link InstanceObjectValue#identity()}, а не у самого значения:
     * то же правило, по которому получателя достаёт {@code NativeInstance.receiverOf}.
     */
    static Sprite spriteOf(Value value) {
        return value instanceof InstanceObjectValue instance
                && instance.identity() instanceof NativeInstance wrapper
                && wrapper.state() instanceof Sprite sprite ? sprite : null;
    }

    private static Sprite sprite(NativeInstance self) {
        return self.state(Sprite.class);
    }

    /**
     * Числовое свойство с чтением и записью — шесть штук подряд, отличающиеся
     * только парой методов движка.
     */
    private static void number(NativeClass.Builder builder, String name,
                               ToDoubleFunction<Sprite> read, ObjDoubleConsumer<Sprite> write,
                               String documentation) {
        builder.property(name,
                        (self, context, span) -> FloatValue.of(read.applyAsDouble(sprite(self))),
                        (self, value, context, span) -> write.accept(sprite(self),
                                Fields.number(value, "Sprite." + name, span)))
                .doc(documentation);
    }

    private static void shape(NativeInstance self, Value value, Span span) {
        String name = Fields.text(value, "Sprite.shape", span);
        Sprite.Shape shape = Sprite.Shape.byName(name);
        if (shape == null) {
            throw Fields.bad("Sprite.shape", "ожидалось rect или oval", value, span);
        }
        sprite(self).shape(shape);
    }
}
