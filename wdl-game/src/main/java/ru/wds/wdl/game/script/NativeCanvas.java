package ru.wds.wdl.game.script;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.game.engine.Painter;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;

/**
 * Класс {@code Canvas}: холст кадра, каким его видит скрипт.
 *
 * <pre>{@code
 * world.onDraw(def (c) {
 *     c.text(20, 40, "счёт: " + score, size: 24, color: "#7ec8ff")
 *     for (y = 0; y < c.height; y = y + 6) c.pixel(c.width / 2, y, "#2a3038")
 * })
 * }</pre>
 *
 * <h2>Холст приходит аргументом, а не берётся у мира</h2>
 * Рисовать имеет смысл только внутри кадра: следующий начинается с заливки фоном,
 * и всё, нарисованное между кадрами, исчезнет, не показавшись. Свойство
 * {@code world.canvas} обещало бы обратное — что рисовать можно когда угодно, —
 * поэтому его нет, а холст отдаётся туда, где он работает: в обработчик
 * {@code onDraw}. Попытка рисовать вне кадра отвечает отказом, а не тишиной.
 *
 * <h2>Цвет — необязательный аргумент</h2>
 * У каждого метода он последний и может быть пропущен: тогда рисуют цветом,
 * заданным {@code c.color(...)}. Так строчка с координатами не тонет в повторении
 * одного и того же {@code "#ffffff"}.
 */
public final class NativeCanvas {

    private NativeCanvas() {
    }

    /** Класс {@code Canvas} этого запуска. */
    public static NativeClass build() {
        return NativeClass.named("Canvas")
                .doc("холст кадра: заливка, точки, фигуры, линии и текст")
                .wrapper(Painter.class)

                .property("width", (self, context, span) -> IntValue.of(painter(self).width()))
                .doc("ширина холста в пикселях")
                .property("height", (self, context, span) -> IntValue.of(painter(self).height()))
                .doc("высота холста в пикселях")

                .method("color", Signature.of(Param.required("color")),
                        (self, context, args, span) -> {
                            painter(self).color(Fields.color(args, 0));
                            return NullValue.NULL;
                        })
                .doc("цвет по умолчанию: им рисует всё, что позвали без своего цвета")

                .method("clear", Signature.of(Param.optional("color", NullValue.NULL)),
                        (self, context, args, span) -> {
                            painter(self).clear(Fields.color(args, 0));
                            return NullValue.NULL;
                        })
                .doc("заливает холст целиком")

                .method("pixel", Signature.of(Param.required("x"), Param.required("y"),
                                Param.optional("color", NullValue.NULL)),
                        (self, context, args, span) -> {
                            painter(self).pixel(args.real(0, "x"), args.real(1, "y"),
                                    Fields.color(args, 2));
                            return NullValue.NULL;
                        })
                .doc("одна точка: пишется прямо в картинку кадра, без сглаживания")

                .method("rect", Signature.of(Param.required("x"), Param.required("y"),
                                Param.required("w"), Param.required("h"),
                                Param.optional("color", NullValue.NULL)),
                        (self, context, args, span) -> {
                            painter(self).rect(args.real(0, "x"), args.real(1, "y"),
                                    args.real(2, "w"), args.real(3, "h"), Fields.color(args, 4));
                            return NullValue.NULL;
                        })
                .doc("закрашенный прямоугольник")

                .method("frame", Signature.of(Param.required("x"), Param.required("y"),
                                Param.required("w"), Param.required("h"),
                                Param.optional("color", NullValue.NULL),
                                Param.optional("thickness", 1)),
                        (self, context, args, span) -> {
                            painter(self).frame(args.real(0, "x"), args.real(1, "y"),
                                    args.real(2, "w"), args.real(3, "h"), Fields.color(args, 4),
                                    args.real(5, "толщина", 1));
                            return NullValue.NULL;
                        })
                .doc("контур прямоугольника")

                .method("oval", Signature.of(Param.required("x"), Param.required("y"),
                                Param.required("w"), Param.required("h"),
                                Param.optional("color", NullValue.NULL)),
                        (self, context, args, span) -> {
                            painter(self).oval(args.real(0, "x"), args.real(1, "y"),
                                    args.real(2, "w"), args.real(3, "h"), Fields.color(args, 4));
                            return NullValue.NULL;
                        })
                .doc("закрашенный эллипс, вписанный в прямоугольник")

                .method("line", Signature.of(Param.required("x1"), Param.required("y1"),
                                Param.required("x2"), Param.required("y2"),
                                Param.optional("color", NullValue.NULL),
                                Param.optional("thickness", 1)),
                        (self, context, args, span) -> {
                            painter(self).line(args.real(0, "x1"), args.real(1, "y1"),
                                    args.real(2, "x2"), args.real(3, "y2"), Fields.color(args, 4),
                                    args.real(5, "толщина", 1));
                            return NullValue.NULL;
                        })
                .doc("отрезок между двумя точками")

                .method("text", Signature.of(Param.required("x"), Param.required("y"),
                                Param.required("text"), Param.optional("size", 16),
                                Param.optional("color", NullValue.NULL)),
                        (self, context, args, span) -> {
                            painter(self).text(args.real(0, "x"), args.real(1, "y"),
                                    args.at(2).display(), args.real(3, "размер", 16),
                                    Fields.color(args, 4));
                            return NullValue.NULL;
                        })
                .doc("текст: координата — левый край базовой линии")

                .method("textWidth", Signature.of(Param.required("text"),
                                Param.optional("size", 16)),
                        (self, context, args, span) -> IntValue.of(
                                painter(self).textWidth(args.at(0).display(),
                                        args.real(1, "размер", 16))))
                .doc("ширина строки в пикселях — без неё текст не поставить по центру")

                .build();
    }

    private static Painter painter(NativeInstance self) {
        return self.state(Painter.class);
    }
}
