package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.value.PropertyRequirement;
import ru.wds.wdl.value.Requirement;
import ru.wds.wdl.value.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Форма трейта, объявленного приложением ({@code bridge.NativeTrait}).
 * <p>
 * Для {@link Linker} она такая же, как форма трейта на wdl: те же требования, та же
 * таблица полей, та же проверка на строке {@code class}. Отличий два, и оба следуют
 * из того, что дерева у нативного трейта нет:
 * <ul>
 *   <li><b>значение объявленного поля приходит готовым</b>, а не вычисляется заново
 *       на каждом создании — {@link #defaultOf(String)} вместо выражения;</li>
 *   <li><b>методов с телом не бывает</b>: метод в плоской таблице держит
 *       {@code FunctionExpr}, а у Java-метода его нет. Нативный трейт — контракт
 *       и заготовки полей, и только.</li>
 * </ul>
 * Заголовка у такого трейта нет, поэтому {@link #params()} пуст: {@link FieldSlot#param()}
 * для его полей не зовут — значение берут из {@link #defaultOf(String)}.
 */
public final class NativeTraitShape implements TraitShape {

    private final String name;
    private final List<String> requiredFields;
    private final List<Requirement> requiredMethods;
    private final List<PropertyRequirement> requiredProperties;
    private final Map<String, Value> declaredFields;
    private final Map<String, FieldSlot> fields;

    /**
     * @param declaredFields поля со значением: имя → готовое значение, в порядке объявления
     */
    public NativeTraitShape(String name, List<String> requiredFields,
                            List<Requirement> requiredMethods, Map<String, Value> declaredFields) {
        this(name, requiredFields, requiredMethods, List.of(), declaredFields);
    }

    /**
     * @param requiredProperties имена, которые класс обязан уметь читать или писать
     */
    public NativeTraitShape(String name, List<String> requiredFields,
                            List<Requirement> requiredMethods,
                            List<PropertyRequirement> requiredProperties,
                            Map<String, Value> declaredFields) {
        this.name = Objects.requireNonNull(name, "name");
        this.requiredFields = List.copyOf(requiredFields);
        this.requiredMethods = List.copyOf(requiredMethods);
        this.requiredProperties = List.copyOf(requiredProperties);
        this.declaredFields = Collections.unmodifiableMap(new LinkedHashMap<>(declaredFields));

        Map<String, FieldSlot> slots = new LinkedHashMap<>();
        int index = 0;
        for (String field : this.declaredFields.keySet()) {
            slots.put(field, new FieldSlot(field, this, index++));
        }
        this.fields = Collections.unmodifiableMap(slots);
    }

    /** Поля со значением: имя → готовое значение, в порядке объявления. */
    public Map<String, Value> declaredFields() {
        return declaredFields;
    }

    /** Готовое значение объявленного поля. */
    public Value defaultOf(String field) {
        Value value = declaredFields.get(field);
        if (value == null) {
            throw new IllegalStateException("у трейта '" + name + "' нет поля '" + field + "'");
        }
        return value;
    }

    @Override
    public List<String> requiredFields() {
        return requiredFields;
    }

    @Override
    public List<Requirement> requiredMethods() {
        return requiredMethods;
    }

    @Override
    public String name() {
        return name;
    }

    /** Заголовка у нативного трейта нет: значения полей приходят готовыми, а не деревом. */
    @Override
    public List<FunctionExpr.Param> params() {
        return List.of();
    }

    @Override
    public Map<String, FieldSlot> fields() {
        return fields;
    }

    /** Методов с телом у нативного трейта не бывает — см. описание класса. */
    @Override
    public Map<String, MethodSlot> methods() {
        return Map.of();
    }

    @Override
    public List<PropertyRequirement> requiredProperties() {
        return requiredProperties;
    }

    /**
     * Свойств с телом у нативного трейта не бывает — по той же причине, что методов:
     * слот держит объявление из дерева, а у Java-кода его нет. Требовать свойство
     * такой трейт может, дать — нет.
     */
    @Override
    public Map<String, PropertySlot> properties() {
        return Map.of();
    }

    @Override
    public String toString() {
        return "trait " + name;
    }
}
