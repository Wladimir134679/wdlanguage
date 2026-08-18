package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arguments;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Раскладка аргументов вызова по позициям параметров — единственное место, где имя
 * превращается в позицию, а раскрытый контейнер — в отдельные аргументы.
 * <p>
 * Здесь же и вся цена именованных аргументов: дальше связывателя ни имена, ни звёздочки
 * не проходят. Ни функция, ни класс не знают, как их позвали, — они получают
 * {@linkplain Arguments набор по позициям}, и вызов остаётся тем же, чем был.
 * <p>
 * <b>Работа идёт по слотам, а не по узлам дерева.</b> Аргумент даёт один слот,
 * {@code *values} — по слоту на элемент массива, {@code **options} — по слоту на пару
 * объекта. После этого раскладка не знает и не хочет знать, что откуда взялось, —
 * и оттого «коллизия после раскрытия — та же коллизия» получается сама, без второй
 * ветки правил.
 * <p>
 * <b>Порядок проверок зафиксирован, и это важнее, чем кажется:</b>
 * <ol>
 *   <li>имя → позиция ({@link Signature#indexOf(String)}); такого параметра нет —
 *       либо остаток {@code **named}, либо ошибка;</li>
 *   <li>позиция уже занята — ошибка: аргумент задан дважды;</li>
 *   <li>лишний позиционный — либо остаток {@code *args}, либо ошибка про число аргументов;</li>
 *   <li>незаполненная позиция: отложенное значение по умолчанию оставляет пропуск,
 *       готовое — подставляется здесь, а обязательный параметр даёт ошибку;</li>
 *   <li>хвост из непереданных обрезается — см. {@link Arguments.Builder#build()}.</li>
 * </ol>
 * Сначала коллизии, потом недостача — это следствие того, что первые три шага идут
 * одним циклом, а четвёртый вторым: {@code f(*[1], **{a: 2})} сообщает про повтор
 * {@code a}, а не про пропущенный {@code b}.
 * <p>
 * Шаги 1–3 <b>не смотрят на значение аргумента</b>. Это правило придётся охранять,
 * когда появятся типы ({@code def f(x is int)}): проверка типа встанет сюда шестым
 * шагом, но выбирать позицию по типу нельзя — так начинается разрешение перегрузок,
 * а перегрузок в языке быть не может, функция это значение в переменной, и одно имя
 * означает ровно одно значение.
 * <p>
 * Обычный позиционный вызов вызываемого без остатка сюда не заходит вовсе:
 * {@code Interpreter} проверяет его прежним способом, по {@link ru.wds.wdl.value.Arity},
 * и отдаёт список как есть. Иначе подстановка значений по умолчанию снаружи молча
 * поменяла бы поведение встроенных функций, которые определяют «аргумент не передан»
 * по длине списка.
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

    /**
     * Аргумент, готовый к раскладке: откуда он взялся — уже неважно.
     *
     * @param name  имя параметра или {@code null}, если аргумент занимает позицию
     * @param span  что подчеркнуть в сообщении: сам аргумент или раскрытие, из которого
     *              он вышел
     * @param value вычисленное значение
     */
    private record Slot(String name, Span span, Value value) {
    }

    /**
     * Нужен ли связыватель, или вызов пойдёт прежним, позиционным путём.
     * <p>
     * Третье слагаемое — остаток у вызываемого — обязательно: у функции с {@code *args}
     * даже полностью позиционный вызов надо разделить на «первые N по позициям» и «хвост
     * в остаток», а сделать это больше некому.
     */
    static boolean needed(Signature signature, List<Argument> arguments) {
        if (signature.hasRest() || signature.hasNamedRest()) {
            return true;
        }
        for (Argument argument : arguments) {
            if (argument.isNamed() || argument.isSpread()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Раскладывает уже вычисленные аргументы по позициям.
     *
     * @param signature контракт вызываемого
     * @param arguments аргументы дерева — нужны имена, звёздочки и места для сообщений
     * @param values    их значения, в том же порядке; вычислены по порядку записи
     * @param callee    имя вызываемого для сообщений: «функция 'greet'», «класс 'Point'»
     * @param span      место всего вызова — для ошибок, у которых своего места нет
     */
    static Arguments bind(Signature signature, List<Argument> arguments, List<Value> values,
                          Callee callee, Span span) {
        return layout(signature, slots(arguments, values, callee), callee, span);
    }

    /**
     * То же для вызова снаружи, из приложения: имён и раскрытий там нет, а вот остаток
     * есть — и без раскладки он потерялся бы молча, потому что тело идёт по своим
     * параметрам и лишнего не видит.
     */
    static Arguments bindPositional(Signature signature, List<Value> values,
                                    Callee callee, Span span) {
        List<Slot> slots = new ArrayList<>(values.size());
        for (Value value : values) {
            slots.add(new Slot(null, span, value));
        }
        return layout(signature, slots, callee, span);
    }

    /**
     * Разворачивает аргументы в слоты: раскрытие превращается в отдельные аргументы,
     * остальное переходит один в один.
     * <p>
     * Снимок контейнера получается даром: {@link ArrayValue#items()} и
     * {@link MapValue#entries()} и без того отдают копию, поэтому изменение массива
     * уже начатый вызов не тронет.
     */
    private static List<Slot> slots(List<Argument> arguments, List<Value> values, Callee callee) {
        List<Slot> slots = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Argument argument = arguments.get(i);
            Value value = values.get(i);
            switch (argument.kind()) {
                case POSITIONAL -> slots.add(new Slot(null, argument.span(), value));
                case NAMED -> slots.add(new Slot(argument.name(), argument.span(), value));
                case SPREAD -> {
                    if (!(value instanceof ArrayValue array)) {
                        throw new WdlRuntimeError(ErrorKind.TYPE, argument.span(),
                                "раскрыть в аргументы можно только массив, а здесь "
                                        + value.type().title() + " (" + value.display() + ")");
                    }
                    for (Value item : array.items()) {
                        slots.add(new Slot(null, argument.span(), item));
                    }
                }
                case NAMED_SPREAD -> namedSpread(slots, argument, value, callee);
            }
        }
        return slots;
    }

    /**
     * Раскрытие объекта по именам.
     * <p>
     * Экземпляр класса объектом настроек не считается, хотя и наследует
     * {@link MapValue}: у него есть свой тип, свои методы и свой смысл, и превращать
     * его поля в аргументы значит путать данные с их описанием. Ключ обязан быть
     * строкой — числовому ключу в имени параметра соответствовать нечему.
     */
    private static void namedSpread(List<Slot> slots, Argument argument, Value value,
                                    Callee callee) {
        if (value instanceof InstanceObjectValue instance) {
            // Тип у экземпляра — тот же 'объект', поэтому сказать «а здесь объект»
            // значило бы не сказать ничего: называем класс.
            throw new WdlRuntimeError(ErrorKind.TYPE, argument.span(),
                    "раскрыть по именам можно только объект, а здесь экземпляр класса '"
                            + instance.owner().name() + "'");
        }
        if (!(value instanceof MapValue object)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, argument.span(),
                    "раскрыть по именам можно только объект, а здесь "
                            + value.type().title() + " (" + value.display() + ")");
        }
        for (Map.Entry<Value, Value> entry : object.entries().entrySet()) {
            if (!(entry.getKey() instanceof StringValue key)) {
                throw new WdlRuntimeError(ErrorKind.TYPE, argument.span(),
                        "ключ объекта при раскрытии по именам должен быть строкой, а здесь "
                                + entry.getKey().display() + " — " + callee.nominative()
                                + " принимает аргументы по именам");
            }
            slots.add(new Slot(key.value(), argument.span(), entry.getValue()));
        }
    }

    /** Раскладка слотов по позициям — общая для вызова из скрипта и вызова из приложения. */
    private static Arguments layout(Signature signature, List<Slot> slots,
                                    Callee callee, Span span) {
        if (!signature.namesKnown()) {
            return positionalOnly(signature, slots, callee, span);
        }

        List<Signature.Param> params = signature.params();
        Arguments.Builder builder = Arguments.builder(params.size());
        Slot[] source = new Slot[params.size()];
        int position = 0;
        for (Slot slot : slots) {
            if (slot.name() == null) {
                int index = position++;
                if (index >= params.size()) {
                    if (!signature.hasRest()) {
                        // Позиционных больше, чем параметров: сообщение то же, что
                        // у обычного вызова, — про число аргументов, а не про имена.
                        throw tooMany(signature, slots.size(), callee, span);
                    }
                    builder.addRest(slot.value());
                    continue;
                }
                builder.set(index, slot.value());
                source[index] = slot;
                continue;
            }

            int index = signature.indexOf(slot.name());
            if (index < 0) {
                if (!signature.hasNamedRest()) {
                    throw new WdlRuntimeError(ErrorKind.CALL, slot.span(),
                            callee.nominative() + " не принимает параметра '" + slot.name() + "'");
                }
                if (builder.hasNamedRest(slot.name())) {
                    throw new WdlRuntimeError(ErrorKind.CALL, slot.span(), "аргумент '"
                            + slot.name() + "' " + callee.genitive() + " передан дважды");
                }
                builder.putNamedRest(slot.name(), slot.value());
                continue;
            }
            if (builder.isSet(index)) {
                throw taken(params.get(index).name(), source[index], callee, slot);
            }
            builder.set(index, slot.value());
            source[index] = slot;
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

    /**
     * Вызываемый, который имён не объявил: сюда попадает только раскрытие массива.
     * <p>
     * {@code println(*values)} обязано работать — раскрытие говорит, сколько аргументов,
     * а не как их зовут. Имя же взяться неоткуда: его не примет ни встроенная функция,
     * ни функция приложения.
     */
    private static Arguments positionalOnly(Signature signature, List<Slot> slots,
                                            Callee callee, Span span) {
        List<Value> values = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.name() != null) {
                throw new WdlRuntimeError(ErrorKind.CALL, slot.span(), callee.nominative()
                        + " принимает аргументы только по позиции: имена параметров не объявлены");
            }
            values.add(slot.value());
        }
        if (!signature.arity().accepts(values.size())) {
            throw tooMany(signature, values.size(), callee, span);
        }
        return Arguments.positional(values);
    }

    /**
     * Позиция занята дважды. Чем именно она занята в первый раз, знает слот, —
     * поэтому сообщение говорит правду и после раскрытия: {@code f(a: 1, **{a: 2})}
     * жалуется на имя, а {@code f(1, a: 2)} по-прежнему на позицию.
     */
    private static WdlRuntimeError taken(String param, Slot first, Callee callee, Slot second) {
        String how = first == null || first.name() == null ? "позиционно" : "по имени";
        return new WdlRuntimeError(ErrorKind.CALL, second.span(), "параметр '" + param + "' "
                + callee.genitive() + " уже задан " + how);
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
}
