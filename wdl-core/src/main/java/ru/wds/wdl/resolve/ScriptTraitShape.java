package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.value.PropertyRequirement;
import ru.wds.wdl.value.Requirement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Форма трейта, написанного на wdl.
 * <p>
 * Параметр заголовка со значением ({@code count = 0}) даёт полю готовое значение
 * и попадает в {@link #fields()}; параметр без значения ({@code limit}) — это
 * требование к классу и слота в таблице полей <b>не занимает</b>. Иначе класс
 * получил бы лишнее поле, которого никто не объявлял.
 * <p>
 * Трейт ни от чего не зависит: ни родителя, ни подмешанных трейтов у него нет.
 * Поэтому все трейты строятся до всех классов, и порядок объявлений для них
 * не значит ничего.
 */
public final class ScriptTraitShape implements TraitShape {

    private final TraitDeclStmt declaration;
    private final Map<String, FieldSlot> fields;
    private final Map<String, MethodSlot> methods;
    private final List<String> requiredFields;
    private final List<Requirement> requiredMethods;
    private final Map<String, PropertySlot> properties;
    private final List<PropertyRequirement> requiredProperties;

    ScriptTraitShape(TraitDeclStmt declaration) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");

        Map<String, FieldSlot> withValue = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<FunctionExpr.Param> params = declaration.params();
        for (int i = 0; i < params.size(); i++) {
            FunctionExpr.Param param = params.get(i);
            if (param.hasDefault()) {
                withValue.put(param.name(), new FieldSlot(param.name(), this, i));
            } else {
                required.add(param.name());
            }
        }
        this.fields = Collections.unmodifiableMap(withValue);
        this.requiredFields = List.copyOf(required);

        List<Requirement> requirements = new ArrayList<>(declaration.requirements().size());
        for (TraitDeclStmt.Requirement requirement : declaration.requirements()) {
            requirements.add(new Requirement(requirement.name(),
                    ClassShape.arityOf(requirement.params(), requirement.variadic())));
        }
        this.requiredMethods = List.copyOf(requirements);

        Map<String, MethodSlot> table = new LinkedHashMap<>();
        for (FunctionExpr method : declaration.methods()) {
            table.put(method.name(), new MethodSlot(method.name(), method, this));
        }
        this.methods = Collections.unmodifiableMap(table);

        // Свойство трейта — то же, что метод трейта: с телом достаётся классу,
        // без тела остаётся требованием. Аксессоры считаются по одному, поэтому
        // 'def get()' без тела рядом с готовым 'def set(value)' — законная запись.
        Map<String, PropertySlot> declared = new LinkedHashMap<>();
        List<PropertyRequirement> demanded = new ArrayList<>();
        for (PropertyDecl property : declaration.properties()) {
            PropertySlot slot = new PropertySlot(property.name(), property, this);
            boolean needsRead = property.getter() != null && property.getter().isRequirement();
            boolean needsWrite = property.setter() != null && property.setter().isRequirement();
            if (needsRead || needsWrite) {
                demanded.add(new PropertyRequirement(property.name(), needsRead, needsWrite));
            }
            // В таблицу попадает только то, у чего есть хоть один готовый аксессор:
            // свойство из одних требований классу отдавать нечего.
            if (slot.readable() || slot.writable()) {
                declared.put(property.name(), slot);
            }
        }
        this.properties = Collections.unmodifiableMap(declared);
        this.requiredProperties = List.copyOf(demanded);
    }

    public TraitDeclStmt declaration() {
        return declaration;
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
    public List<PropertyRequirement> requiredProperties() {
        return requiredProperties;
    }

    @Override
    public Map<String, PropertySlot> properties() {
        return properties;
    }

    @Override
    public String name() {
        return declaration.name();
    }

    @Override
    public List<FunctionExpr.Param> params() {
        return declaration.params();
    }

    @Override
    public Map<String, FieldSlot> fields() {
        return fields;
    }

    @Override
    public Map<String, MethodSlot> methods() {
        return methods;
    }

    @Override
    public String toString() {
        return "trait " + name();
    }
}
