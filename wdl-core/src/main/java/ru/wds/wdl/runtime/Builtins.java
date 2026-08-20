package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.StringValue;
import ru.wds.wdl.value.Value;

import java.util.List;
import java.util.Objects;

/**
 * Функции, которые есть в языке всегда: вывод и пара базовых вопросов о значении.
 * <p>
 * Их немного и будет немного: всё остальное — дело {@code wdl-stdlib}, который
 * подключается по желанию и теми же средствами (положить {@link BuiltinFunction}
 * в область видимости). Здесь остаётся лишь то, без чего нельзя написать первый
 * скрипт и посмотреть, что получилось.
 * <p>
 * Функции кладутся в обычные переменные обычной области видимости. Никакой отдельной
 * таблицы встроенных функций нет — иначе {@code println} нельзя было бы ни передать
 * аргументом, ни временно подменить в тесте.
 */
public final class Builtins {

    private Builtins() {
    }

    /**
     * Кладёт встроенные функции в область видимости.
     *
     * @return та же область — чтобы вызов удобно встраивался в цепочку
     */
    public static Environment installTo(Environment scope) {
        Objects.requireNonNull(scope, "scope");

        // println("итого = ", 5) печатает аргументы подряд, без разделителей:
        // разделитель по умолчанию пришлось бы каждый раз отключать при сборке строк.
        scope.define("println", BuiltinFunction.of("println", Arity.any(), (context, arguments, span) -> {
            context.write(join(arguments) + System.lineSeparator());
            return NullValue.NULL;
        }));

        scope.define("print", BuiltinFunction.of("print", Arity.any(), (context, arguments, span) -> {
            context.write(join(arguments));
            return NullValue.NULL;
        }));

        // typeof и len — минимум, без которого динамический язык неудобно отлаживать.
        scope.define("typeof", BuiltinFunction.of("typeof", Arity.exactly(1),
                (context, arguments, span) -> StringValue.of(arguments.get(0).type().id())));

        scope.define("len", BuiltinFunction.of("len", Arity.exactly(1), (context, arguments, span) -> {
            Value value = arguments.get(0);
            return switch (value) {
                case StringValue string -> IntValue.of(string.length());
                case ArrayValue array -> IntValue.of(array.size());
                case MapValue object -> IntValue.of(object.size());
                default -> throw new WdlRuntimeError(ErrorKind.TYPE, span,
                        "len() работает со строкой, массивом или объектом, а здесь " + value.type().title());
            };
        }));

        // like — единственная встроенная, существующая ради сообщений об ошибках:
        // без неё декоратор молча съедает проверку числа аргументов. См. LikeFunction.
        scope.define("like", BuiltinFunction.of("like", Arity.exactly(2), (context, arguments, span) -> {
            if (!(arguments.get(0) instanceof FunctionValue target)) {
                throw new WdlRuntimeError(ErrorKind.TYPE, span, "like(): первым аргументом идёт"
                        + " цель — функция, а здесь " + arguments.get(0).type().title());
            }
            if (!(arguments.get(1) instanceof FunctionValue wrapper)) {
                throw new WdlRuntimeError(ErrorKind.TYPE, span, "like(): вторым аргументом идёт"
                        + " обёртка — функция, а здесь " + arguments.get(1).type().title());
            }
            return LikeFunction.of(target, wrapper, span);
        }));

        return scope;
    }

    /** Аргументы подряд, в пользовательском виде: строки — без кавычек. */
    private static String join(List<Value> arguments) {
        if (arguments.size() == 1) {
            return arguments.get(0).display();
        }
        StringBuilder sb = new StringBuilder(32);
        for (Value argument : arguments) {
            sb.append(argument.display());
        }
        return sb.toString();
    }
}
