package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.resolve.MethodSlot;
import ru.wds.wdl.resolve.PropertySlot;
import ru.wds.wdl.resolve.ScriptTraitShape;
import ru.wds.wdl.value.PropertyRequirement;
import ru.wds.wdl.value.Requirement;
import ru.wds.wdl.value.TraitValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Трейт во время выполнения: форма плюс область, где он объявлен.
 * <p>
 * Значений у трейта своих нет — есть только заготовки: методы, которые достанутся
 * классу, и значения по умолчанию полей, которые вычисляются в этой самой области
 * и заново на каждом создании экземпляра.
 * <p>
 * Экземпляр трейтом не создаётся: {@code new Counted()} — ошибка. Трейт описывает,
 * чего не хватает классу, а не самостоятельную вещь.
 */
final class WdlTrait implements TraitValue {

    private final ScriptTraitShape shape;
    private final Environment closure;
    /** Файл, где трейт объявлен: в нём выполняются его методы и значения по умолчанию. */
    private final Unit unit;
    private final Map<String, Method> methods;
    /**
     * Свойства трейта: объявление плюс область трейта. Собираются здесь, а вызываются
     * через класс — по той же причине, что методы: {@code super} в трейте запрещён
     * разбором, поэтому класса, от которого его считать, у слота нет.
     */
    private final Map<String, PropertySlot> properties;

    WdlTrait(ScriptTraitShape shape, Environment closure, Unit unit) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.closure = Objects.requireNonNull(closure, "closure");
        this.unit = Objects.requireNonNull(unit, "unit");

        Map<String, Method> table = new LinkedHashMap<>();
        for (MethodSlot slot : shape.methods().values()) {
            // super внутри метода трейта запрещён разбором, поэтому его класса здесь нет.
            table.put(slot.name(), new Method(slot.declaration(), closure, unit, null));
        }
        this.methods = Collections.unmodifiableMap(table);
        this.properties = shape.properties();
    }

    Map<String, PropertySlot> properties() {
        return properties;
    }

    ScriptTraitShape shape() {
        return shape;
    }

    Environment closure() {
        return closure;
    }

    Unit unit() {
        return unit;
    }

    Map<String, Method> methods() {
        return methods;
    }

    /** Значение по умолчанию поля трейта — дерево, вычисляемое на каждом создании. */
    FunctionExpr.Param param(int index) {
        return shape.params().get(index);
    }

    @Override
    public String name() {
        return shape.name();
    }

    /** Интроспекция: ключи таблиц трейта — методы и свойства, объявленные с телом. */
    @Override
    public List<String> methodNames() {
        return List.copyOf(methods.keySet());
    }

    @Override
    public List<String> propertyNames() {
        return List.copyOf(properties.keySet());
    }

    @Override
    public List<Requirement> requiredMethods() {
        return shape.requiredMethods();
    }

    @Override
    public List<String> requiredFields() {
        return shape.requiredFields();
    }

    /**
     * Нужно там же, где остальные требования: трейт на wdl вправе подмешаться
     * в класс, встроенный приложением, и проверить его требования {@code Linker}
     * уже не может — формы у такого класса нет.
     */
    @Override
    public List<PropertyRequirement> requiredProperties() {
        return shape.requiredProperties();
    }

    @Override
    public String toString() {
        return display();
    }
}
