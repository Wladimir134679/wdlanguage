package ru.wds.wdl.runtime.members;

import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Как движок рассказывает о себе скрипту: общая форма ответов интроспекции.
 * <p>
 * Форма решается здесь один раз, потому что поменять её потом нельзя — на неё сразу
 * напишут тестовый фреймворк и сборку CLI по функции. Три решения, которые она
 * закрепляет:
 * <ul>
 *   <li><b>параметр — объект, а не строка.</b> В {@link Signature.Param} уже четыре
 *       вида, а следом за именами в контракт придут типы; строка {@code "price"}
 *       закрыла бы дверь в тот же день;</li>
 *   <li><b>«имён нет» и «параметров нет» — разные ответы.</b> Контракт без имён
 *       ({@code println}, функция приложения) отдаёт {@code null}, а не пустой
 *       массив: пустой массив — это ложь про функцию, у которой параметры есть;</li>
 *   <li><b>класс и трейт отдаются значениями, а не именами.</b> По строке нельзя
 *       ни спросить {@code is}, ни создать экземпляр, а реестра имён в языке нет.</li>
 * </ul>
 */
final class Introspection {

    private Introspection() {
    }

    /**
     * Параметры контракта массивом объектов или {@code null}, если имена неизвестны.
     * <p>
     * У параметра с {@linkplain Signature.Param#isLazy() отложенным} значением
     * по умолчанию {@code default} — честный {@code null}: за ним стоит выражение,
     * которое считается в области вызова, и до вызова значения у него нет вовсе.
     * Отличить такой параметр от обязательного даёт {@code required}.
     */
    static Value params(Signature signature) {
        if (!signature.namesKnown()) {
            return NullValue.NULL;
        }
        List<Value> params = new ArrayList<>(signature.params().size());
        for (Signature.Param param : signature.params()) {
            MapValue described = new MapValue();
            described.put("name", StringValue.of(param.name()));
            described.put("required", BoolValue.of(param.isRequired()));
            described.put("default", param.constant() != null ? param.constant() : NullValue.NULL);
            params.add(described);
        }
        return ArrayValue.of(params);
    }

    /**
     * Число аргументов объектом {@code {min, max}}; {@code max} у функции с остатком —
     * {@code null}, потому что верхней границы у неё нет, а огромное число здесь
     * читалось бы как настоящий предел.
     */
    static Value arity(Arity arity) {
        MapValue described = new MapValue();
        described.put("min", IntValue.of(arity.min()));
        described.put("max", arity.max() == Integer.MAX_VALUE
                ? NullValue.NULL
                : IntValue.of(arity.max()));
        return described;
    }

    /** Имена строками — там, где значения не существует: методы, свойства, требования. */
    static Value names(List<String> names) {
        List<Value> values = new ArrayList<>(names.size());
        for (String name : names) {
            values.add(StringValue.of(name));
        }
        return ArrayValue.of(values);
    }

    /** Трейты значениями: {@code Circle.traits[0] is Trait} и {@code p is Circle.traits[0]}. */
    static Value traits(List<TraitValue> traits) {
        List<Value> values = new ArrayList<>(traits.size());
        values.addAll(traits);
        return ArrayValue.of(values);
    }
}
