package ru.wds.wdl.game.script;

import ru.wds.wdl.game.engine.Colors;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.StringValue;

/**
 * Проверка значений на границе скрипта и движка.
 * <p>
 * {@link Args} умеет то же самое для аргументов вызова, но {@code ball.x = "далеко"}
 * — это не вызов, и сообщение о нём должно выглядеть так же, как у аргумента:
 * что за имя, чего ждали, что пришло. Форма сообщения общая на весь язык —
 * {@link Args#because}, — поэтому здесь только несколько строк поверх неё.
 * <p>
 * Здесь же переводятся отказы движка: {@link Colors} не знает ни про язык,
 * ни про место в исходнике и бросает обычное {@link IllegalArgumentException}.
 * Без перевода оно дошло бы до скрипта как ошибка Java — с чужим текстом
 * и без внятного места; поймать её было бы можно, но понять — нет.
 */
final class Fields {

    /** Чего ждут от цвета — одна фраза на аргумент и на свойство. */
    private static final String COLOR = "ожидалось #rrggbb, #rgb, #aarrggbb или имя цвета";

    private Fields() {
    }

    static double number(Value value, String subject, Span span) {
        if (value instanceof NumberValue number) {
            return number.asDouble();
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, span,
                Args.because(subject, "ожидалось число", value));
    }

    static String text(Value value, String subject, Span span) {
        if (value instanceof StringValue string) {
            return string.value();
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, span,
                Args.because(subject, "ожидалась строка", value));
    }

    static boolean flag(Value value, String subject, Span span) {
        if (value instanceof BoolValue bool) {
            return bool.value();
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, span,
                Args.because(subject, "ожидалось логическое значение", value));
    }

    /** Цвет из свойства: строка, которую движок умеет разобрать. */
    static String color(Value value, String subject, Span span) {
        String color = text(value, subject, span);
        try {
            Colors.of(color);
        } catch (IllegalArgumentException wrong) {
            throw bad(subject, COLOR, value, span);
        }
        return color;
    }

    /**
     * Цвет из аргумента; {@code null}, если аргумент не передан, — тогда рисуют
     * цветом холста по умолчанию.
     */
    static String color(Args args, int index) {
        if (!args.has(index)) {
            return null;
        }
        String color = args.string(index, "цвет");
        try {
            Colors.of(color);
        } catch (IllegalArgumentException wrong) {
            throw args.bad(index, "цвет", COLOR);
        }
        return color;
    }

    /** Ошибка значения, которое по типу подходит, а по смыслу нет: чужой цвет, чужая форма. */
    static WdlRuntimeError bad(String subject, String expected, Value value, Span span) {
        return new WdlRuntimeError(ErrorKind.VALUE, span, Args.because(subject, expected, value));
    }
}
