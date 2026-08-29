package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.Library;
import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.interop.JavaClass;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;

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

    private Std() {
    }

    /**
     * Библиотека для этого запуска.
     * <p>
     * Каждый раз новая, и это не расточительство: классы она собирает свои,
     * а класс — место, куда скрипт вправе писать. Общий на процесс {@code File}
     * переносил бы {@code File.mark = 1} из одного скрипта в следующий.
     */
    public static Std library() {
        return new Std();
    }

    /** Кладёт всю стандартную библиотеку в область видимости. */
    public static Environment install(Environment scope) {
        return library().installTo(scope);
    }

    @Override
    public String name() {
        return "std";
    }

    @Override
    public Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");
        math(scope);
        // Классы берутся у области: если они там уже есть (скажем, 'import std as s'
        // после установки в корень), это те же самые классы, и 'f is File' не врёт.
        NativeClass file = Files.in(scope);
        // File собран построителем, Random открыт мостом — для скрипта это два
        // одинаковых класса, и здесь видно, что разницы в установке тоже нет.
        JavaClass random = Randoms.in(scope);
        scope.define(file.name(), file);
        scope.define(random.name(), random);
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
            NumberValue base = arguments.number(0, "основание");
            NumberValue exponent = arguments.number(1, "показатель");
            return power(base, exponent, span);
        }));

        scope.define("sqrt", BuiltinFunction.of("sqrt", Arity.exactly(1), (context, arguments, span) -> {
            double value = arguments.real(0, "аргумент");
            if (value < 0) {
                throw arguments.bad(0, "аргумент", "ожидалось неотрицательное число");
            }
            return FloatValue.of(Math.sqrt(value));
        }));

        scope.define("abs", BuiltinFunction.of("abs", Arity.exactly(1), (context, arguments, span) -> {
            NumberValue value = arguments.number(0, "аргумент");
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
            throw new WdlRuntimeError(ErrorKind.VALUE, span,
                    "pow(" + base + ", " + exponent + ") не определено");
        }
        return FloatValue.of(result);
    }

}
