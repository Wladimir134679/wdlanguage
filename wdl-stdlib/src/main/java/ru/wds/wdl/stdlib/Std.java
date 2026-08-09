package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.Library;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.Objects;

/**
 * Стандартная библиотека: то, что есть в языке всегда.
 * <p>
 * Подключается по умолчанию — консольным запуском и будущим движком, — но остаётся
 * обычной {@link Library}: кладёт имена в область видимости и ничего больше.
 * Приложение, встраивающее wdl, может её не подключать или заменить своей.
 * <p>
 * Здесь только то, что показывает возможности встраивания: немного математики,
 * работа с файлом и генератор случайных чисел. Настоящие math, string, array и io
 * появятся отдельными библиотеками рядом — заводятся они ровно так же.
 */
public final class Std implements Library {

    private static final Std INSTANCE = new Std();

    private Std() {
    }

    public static Std library() {
        return INSTANCE;
    }

    /** Кладёт всю стандартную библиотеку в область видимости. */
    public static Environment install(Environment scope) {
        return INSTANCE.installTo(scope);
    }

    @Override
    public String name() {
        return "std";
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");
        math(scope);
        scope.define(Files.CLASS.name(), Files.CLASS);
        scope.define(Randoms.CLASS.name(), Randoms.CLASS);
        return scope;
    }

    /**
     * Математика.
     * <p>
     * Целое остаётся целым там, где это точно: {@code pow(2, 10)} — это {@code 1024},
     * а не {@code 1024.0}. Правило то же, что у арифметики языка, и заведено оно
     * по той же причине: скрипт не должен знать, что внутри {@code long}
     * или {@code double}.
     */
    private static void math(Environment scope) {
        scope.define("pow", BuiltinFunction.of("pow", Arity.exactly(2), (context, arguments, span) -> {
            NumberValue base = number(arguments.get(0), span, "pow", "основание");
            NumberValue exponent = number(arguments.get(1), span, "pow", "показатель");
            return power(base, exponent, span);
        }));

        scope.define("sqrt", BuiltinFunction.of("sqrt", Arity.exactly(1), (context, arguments, span) -> {
            double value = number(arguments.get(0), span, "sqrt", "аргумент").asDouble();
            if (value < 0) {
                throw new WdlRuntimeError(span, "квадратный корень из отрицательного числа: " + value);
            }
            return FloatValue.of(Math.sqrt(value));
        }));

        scope.define("abs", BuiltinFunction.of("abs", Arity.exactly(1), (context, arguments, span) -> {
            NumberValue value = number(arguments.get(0), span, "abs", "аргумент");
            if (!value.isInteger()) {
                return FloatValue.of(Math.abs(value.asDouble()));
            }
            long number = value.asLong();
            // Long.MIN_VALUE по модулю в long не влезает — тот же случай, что
            // и переполнение в арифметике языка, и решается так же.
            return number == Long.MIN_VALUE
                    ? FloatValue.of(Math.abs((double) number))
                    : IntValue.of(Math.abs(number));
        }));
    }

    private static Value power(NumberValue base, NumberValue exponent, Span span) {
        if (base.isInteger() && exponent.isInteger() && exponent.asLong() >= 0) {
            long result = 1;
            long multiplier = base.asLong();
            for (long left = exponent.asLong(); left > 0; left--) {
                try {
                    result = Math.multiplyExact(result, multiplier);
                } catch (ArithmeticException overflow) {
                    // Не влезло в long — продолжаем в double: потерю точности видно,
                    // а заворот разрядов — нет.
                    return FloatValue.of(Math.pow(base.asDouble(), exponent.asDouble()));
                }
            }
            return IntValue.of(result);
        }
        double result = Math.pow(base.asDouble(), exponent.asDouble());
        if (Double.isNaN(result)) {
            throw new WdlRuntimeError(span, "pow(" + base + ", " + exponent + ") не определено");
        }
        return FloatValue.of(result);
    }

    /** Проверка аргумента с сообщением на языке скрипта: имя функции и роль аргумента. */
    static NumberValue number(Value value, Span span, String function, String role) {
        if (value instanceof NumberValue number) {
            return number;
        }
        throw new WdlRuntimeError(span, function + "(): " + role + " должен быть числом, а здесь "
                + value.type().title() + " (" + value + ")");
    }

    /** То же для строкового аргумента — например пути к файлу. */
    static String text(Value value, Span span, String what) {
        if (value instanceof StringValue string) {
            return string.value();
        }
        throw new WdlRuntimeError(span, what + " должен быть строкой, а здесь "
                + value.type().title() + " (" + value + ")");
    }

}
