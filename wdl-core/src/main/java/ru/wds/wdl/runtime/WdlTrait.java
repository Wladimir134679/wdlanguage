package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.resolve.MethodSlot;
import ru.wds.wdl.resolve.TraitShape;
import ru.wds.wdl.value.TraitValue;

import java.util.ArrayList;
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

    private final TraitShape shape;
    private final Environment closure;
    /** Файл, где трейт объявлен: в нём выполняются его методы и значения по умолчанию. */
    private final Unit unit;
    private final Map<String, Method> methods;

    WdlTrait(TraitShape shape, Environment closure, Unit unit) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.closure = Objects.requireNonNull(closure, "closure");
        this.unit = Objects.requireNonNull(unit, "unit");

        Map<String, Method> table = new LinkedHashMap<>();
        for (MethodSlot slot : shape.methods().values()) {
            // super внутри метода трейта запрещён разбором, поэтому его класса здесь нет.
            table.put(slot.name(), new Method(slot.declaration(), closure, unit, null));
        }
        this.methods = Collections.unmodifiableMap(table);
    }

    TraitShape shape() {
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

    @Override
    public List<String> requiredMethods() {
        List<String> names = new ArrayList<>(shape.requiredMethods().size());
        shape.requiredMethods().forEach(requirement -> names.add(requirement.name()));
        return List.copyOf(names);
    }

    @Override
    public String toString() {
        return display();
    }
}
