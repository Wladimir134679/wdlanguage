package ru.wds.wdl.stdlib;

import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.module.Library;
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
public final class Std {

    private Std() {
    }

    /**
     * Библиотека для этого запуска.
     * <p>
     * Каждый раз новая, и это не расточительство: классы она собирает свои,
     * а класс — место, куда скрипт вправе писать. Общий на процесс {@code File}
     * переносил бы {@code File.mark = 1} из одного скрипта в следующий.
     */
    public static Library library() {
        return Module.named("std")
                // Классы модуль берёт у области: если они там уже есть (скажем,
                // 'import std as s' после установки в корень), это те же самые
                // классы, и 'f is File' не врёт.
                //
                // File собран построителем, Random открыт мостом — и это одна
                // и та же сущность: класс от приложения, у которого члены пришли
                // в первом случае лямбдами, во втором от Java-типа. Ни в объявлении,
                // ни в установке разницы нет и быть не должно.
                .type("File", scope -> Files.build())
                .type("Random", scope -> Randoms.build())

                // Математика: целое остаётся целым там, где это точно —
                // pow(2, 10) это 1024, а не 1024.0. Правило то же, что у арифметики
                // языка, и по той же причине: скрипт не должен знать, что внутри
                // long, а что double.
                .function("pow", Arity.exactly(2), (context, arguments, span) -> {
                    NumberValue base = arguments.number(0, "основание");
                    NumberValue exponent = arguments.number(1, "показатель");
                    return power(base, exponent, span);
                })

                .function("sqrt", Arity.exactly(1), (context, arguments, span) -> {
                    double value = arguments.real(0, "аргумент");
                    if (value < 0) {
                        throw arguments.bad(0, "аргумент", "ожидалось неотрицательное число");
                    }
                    return FloatValue.of(Math.sqrt(value));
                })

                .function("abs", Arity.exactly(1), (context, arguments, span) -> {
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
                })

                .build();
    }

    /** Кладёт всю стандартную библиотеку в область видимости. */
    public static Environment install(Environment scope) {
        return library().installTo(scope);
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
