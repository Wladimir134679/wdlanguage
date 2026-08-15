package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arguments;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;

import java.util.List;

/**
 * Раскладка аргументов вызова по позициям параметров — единственное место, где имя
 * превращается в позицию.
 * <p>
 * Здесь же и вся цена именованных аргументов: дальше связывателя имена не проходят.
 * Ни функция, ни класс не знают, как их позвали, — они получают
 * {@linkplain Arguments набор по позициям}, и вызов остаётся тем же, чем был.
 * <p>
 * <b>Порядок проверок зафиксирован, и это важнее, чем кажется:</b>
 * <ol>
 *   <li>имя → позиция ({@link Signature#indexOf(String)}); такого параметра нет — ошибка;</li>
 *   <li>позиция уже занята — ошибка: аргумент задан и позиционно, и по имени;</li>
 *   <li>незаполненная позиция: отложенное значение по умолчанию оставляет пропуск,
 *       готовое — подставляется здесь, а обязательный параметр даёт ошибку;</li>
 *   <li>хвост из непереданных обрезается — см. {@link Arguments.Builder#build()}.</li>
 * </ol>
 * Первые три шага <b>не смотрят на значение аргумента</b>. Это правило придётся охранять,
 * когда появятся типы ({@code def f(x is int)}): проверка типа встанет сюда четвёртым
 * шагом, но выбирать позицию по типу нельзя — так начинается разрешение перегрузок,
 * а перегрузок в языке быть не может, функция это значение в переменной, и одно имя
 * означает ровно одно значение.
 * <p>
 * Позиционный вызов сюда не заходит вовсе: {@code Interpreter} проверяет его прежним
 * способом, по {@link ru.wds.wdl.value.Arity}, и отдаёт список как есть. Иначе
 * подстановка значений по умолчанию снаружи молча поменяла бы поведение встроенных
 * функций, которые определяют «аргумент не передан» по длине списка.
 */
final class Binder {

    private Binder() {
    }

    /**
     * Кого зовут — в двух падежах.
     * <p>
     * Согласование живёт здесь по той же причине, по которой число аргументов
     * согласуется в {@link ru.wds.wdl.value.Arity#describeArguments()}: сообщения
     * собираются в одном месте на функции и классы разом, и склейка из «функция»
     * и «параметр 'x' ... не передан» дала бы «параметр 'x' функция 'greet'».
     *
     * @param nominative «функция 'greet'», «класс 'Point'»
     * @param genitive   «функции 'greet'», «класса 'Point'»
     */
    record Callee(String nominative, String genitive) {

        static Callee function(String name) {
            return new Callee("функция '" + name + "'", "функции '" + name + "'");
        }

        static Callee klass(String name) {
            return new Callee("класс '" + name + "'", "класса '" + name + "'");
        }
    }

    /** Есть ли в списке хоть один именованный аргумент — то есть нужен ли связыватель. */
    static boolean anyNamed(List<Argument> arguments) {
        for (Argument argument : arguments) {
            if (argument.isNamed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Раскладывает уже вычисленные аргументы по позициям.
     *
     * @param signature контракт вызываемого
     * @param arguments аргументы дерева — нужны имена и места имён для сообщений
     * @param values    их значения, в том же порядке; вычислены по порядку записи
     * @param callee    имя вызываемого для сообщений: «функция 'greet'», «класс 'Point'»
     * @param span      место всего вызова — для ошибок, у которых своего места нет
     */
    static Arguments bind(Signature signature, List<Argument> arguments, List<Value> values,
                          Callee callee, Span span) {
        if (!signature.namesKnown()) {
            Argument named = firstNamed(arguments);
            throw new WdlRuntimeError(ErrorKind.CALL, named.span(), callee.nominative()
                    + " принимает аргументы только по позиции: имена параметров не объявлены");
        }

        List<Signature.Param> params = signature.params();
        Arguments.Builder builder = Arguments.builder(params.size());
        int position = 0;
        for (int i = 0; i < arguments.size(); i++) {
            Argument argument = arguments.get(i);
            int index = argument.isNamed() ? signature.indexOf(argument.name()) : position++;
            if (argument.isNamed() && index < 0) {
                throw new WdlRuntimeError(ErrorKind.CALL, argument.nameSpan(),
                        callee.nominative() + " не принимает параметра '" + argument.name() + "'");
            }
            if (index >= params.size()) {
                // Позиционных больше, чем параметров: сообщение то же, что у обычного
                // вызова, — про число аргументов, а не про имена.
                throw tooMany(signature, arguments.size(), callee, span);
            }
            if (builder.isSet(index)) {
                throw new WdlRuntimeError(ErrorKind.CALL, argument.span(), "параметр '"
                        + params.get(index).name() + "' " + callee.genitive()
                        + " уже задан позиционно");
            }
            builder.set(index, values.get(i));
        }

        int last = lastSet(builder, params.size());
        for (int i = 0; i < params.size(); i++) {
            if (builder.isSet(i)) {
                continue;
            }
            Signature.Param param = params.get(i);
            if (param.isRequired()) {
                throw new WdlRuntimeError(ErrorKind.CALL, span, "обязательный параметр '"
                        + param.name() + "' " + callee.genitive() + " не передан");
            }
            if (param.constant() != null) {
                // Готовое значение подставляется здесь: вызываемый, который его объявил,
                // отличить пропуск от переданного значения всё равно не смог бы —
                // у встроенной функции нет области, в которой считать выражение.
                builder.set(i, param.constant());
                continue;
            }
            if (!param.skippable() && i < last) {
                // Необязательный, но без значения: в хвосте пропустить можно — список
                // просто короче, — а в середине нечем закрыть дыру. Подставить null
                // значило бы соврать телу, которое отличает «не передали» от «передали null».
                throw new WdlRuntimeError(ErrorKind.CALL, span, "параметр '" + param.name()
                        + "' " + callee.genitive() + " нельзя пропустить: "
                        + "у него нет значения по умолчанию");
            }
            // Отложенное значение остаётся пропуском: считать его будет сам вызываемый,
            // в своей области и в свой черёд, — иначе оно перестанет видеть параметры
            // левее себя.
        }
        return builder.build();
    }

    /** Последняя заполненная позиция плюс один — граница, за которой начинается хвост. */
    private static int lastSet(Arguments.Builder builder, int size) {
        int last = 0;
        for (int i = 0; i < size; i++) {
            if (builder.isSet(i)) {
                last = i + 1;
            }
        }
        return last;
    }

    private static WdlRuntimeError tooMany(Signature signature, int given, Callee callee, Span span) {
        return new WdlRuntimeError(ErrorKind.CALL, span, callee.nominative() + " принимает "
                + signature.arity().describeArguments() + ", а передано " + given);
    }

    private static Argument firstNamed(List<Argument> arguments) {
        for (Argument argument : arguments) {
            if (argument.isNamed()) {
                return argument;
            }
        }
        throw new IllegalStateException("связыватель позван без единого именованного аргумента");
    }
}
