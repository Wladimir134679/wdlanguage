package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arguments;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Обёртка, представляющаяся целью: результат встроенной {@code like(цель, обёртка)}.
 * <p>
 * <b>Зачем она нужна.</b> Проверка числа аргументов в языке стоит <b>до</b> входа
 * в функцию, и сообщение «функция 'command' принимает не больше 2 аргументов»
 * указывает на строку вызова. Декоратор ломает это одним движением: под именем
 * оказывается {@code def(*args, **named)} с арностью «сколько угодно», проверка
 * молча пропускает всё, и ошибка всплывает уже внутри чужой обёртки — на строке
 * {@code meta.target(...)}, в файле, которого автор вызова не писал.
 * <pre>
 * def timer(meta) {
 *     return like(meta.target, def (*args, **named) => meta.target(*args, **named));
 * }
 * </pre>
 * Снаружи такая обёртка — это цель: то же имя, та же арность, тот же контракт имён.
 * Внутри — обычная функция, и вызывается она обычным способом.
 * <p>
 * <b>Обёртка обязана быть вариативной обеими формами.</b> Через неё проходит всё,
 * что примет цель, включая имена, которых у самой обёртки нет; собрать это можно
 * только в {@code *args} и {@code **named}. Требование проверяется при создании,
 * а не при вызове: ошибка «обёртка не собирает аргументы» полезна там, где обёртку
 * написали, а не там, где её через час позвали.
 * <p>
 * <b>Раскладка разбирается обратно.</b> Вызывающий разложил аргументы по контракту
 * <i>цели</i> — по её позициям и её именам. Обёртка этих позиций не имеет, поэтому
 * набор собирается заново: подряд идущие позиции уходят в {@code *args}, всё
 * остальное — в {@code **named} под именами параметров цели. Пропуск в середине
 * не заполняется: значение по умолчанию посчитает сама цель, когда обёртка её
 * позовёт, — и посчитает в своей области, как и положено.
 */
final class LikeFunction implements FunctionValue {

    private final FunctionValue target;
    private final FunctionValue wrapper;

    private LikeFunction(FunctionValue target, FunctionValue wrapper) {
        this.target = Objects.requireNonNull(target, "target");
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper");
    }

    /**
     * Создаёт обёртку, представляющуюся целью.
     *
     * @throws WdlRuntimeError если обёртка не объявила {@code *args} и {@code **named}
     */
    static FunctionValue of(FunctionValue target, FunctionValue wrapper, Span span) {
        Signature signature = wrapper.signature();
        if (!signature.hasRest() || !signature.hasNamedRest()) {
            throw new WdlRuntimeError(ErrorKind.CALL, span, "обёртка для like() должна собирать"
                    + " аргументы целиком: объявите её как 'def (*args, **named)'."
                    + " Через неё проходит всё, что принимает '" + target.name() + "',"
                    + " включая имена, которых у самой обёртки нет");
        }
        return new LikeFunction(target, wrapper);
    }

    @Override
    public String name() {
        return target.name();
    }

    @Override
    public Arity arity() {
        return target.arity();
    }

    @Override
    public Signature signature() {
        return target.signature();
    }

    @Override
    public boolean anonymous() {
        return target.anonymous();
    }

    /**
     * Вызов плотным списком — снаружи или без имён. Раскладывать нечего: все значения
     * позиционные, и все они уходят в {@code *args} обёртки.
     */
    @Override
    public Value call(CallContext context, List<Value> arguments, Span span) {
        Arguments.Builder builder = Arguments.builder(0);
        arguments.forEach(builder::addRest);
        return wrapper.call(context, builder.build(), span);
    }

    @Override
    public Value call(CallContext context, Arguments arguments, Span span) {
        return wrapper.call(context, flatten(arguments, span), span);
    }

    /**
     * Собирает набор заново — под обёртку, у которой позиций нет вовсе.
     * <p>
     * Позиции идут в {@code *args} ровно до первого пропуска: дальше отдавать
     * позиционно нельзя, значения сдвинулись бы. Всё, что после пропуска, уходит
     * по имени — тому самому, под которым его знает цель.
     */
    private Arguments flatten(Arguments arguments, Span span) {
        Signature contract = target.signature();
        Arguments.Builder builder = Arguments.builder(0);
        boolean contiguous = true;
        for (int i = 0; i < arguments.size(); i++) {
            if (!arguments.has(i)) {
                // Пропуск заполнит сама цель, когда обёртка её позовёт: значение
                // по умолчанию — выражение, и считать его надо в области цели.
                contiguous = false;
                continue;
            }
            if (contiguous) {
                builder.addRest(arguments.get(i));
            } else {
                builder.putNamedRest(nameOf(contract, i, span), arguments.get(i));
            }
        }
        arguments.rest().forEach(builder::addRest);
        for (Map.Entry<String, Value> named : arguments.namedRest().entrySet()) {
            builder.putNamedRest(named.getKey(), named.getValue());
        }
        return builder.build();
    }

    /**
     * Имя параметра цели по номеру.
     * <p>
     * Пропуск в середине бывает только у контракта с именами — его создаёт связывание
     * по именам, — поэтому попасть сюда с безымянным контрактом нельзя. Проверка
     * стоит затем, что «нельзя» здесь держится на рассуждении, а не на типе.
     */
    private static String nameOf(Signature contract, int index, Span span) {
        if (!contract.namesKnown() || index >= contract.params().size()) {
            throw new WdlRuntimeError(ErrorKind.CALL, span, "like(): у аргумента " + (index + 1)
                    + " функции '" + contract + "' нет имени, а передать его позиционно уже нельзя");
        }
        return contract.params().get(index).name();
    }

    /** Печатается как цель: снаружи это она и есть. */
    @Override
    public String display() {
        return target.display();
    }
}
