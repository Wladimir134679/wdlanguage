package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Форма трейта.
 * <p>
 * Отличие от класса ровно одно, и оно определяет всё остальное: и поле, и метод
 * здесь бывают <b>требованием</b>. Параметр заголовка со значением ({@code count = 0})
 * даёт полю готовое значение и попадает в {@link #fields()}; параметр без значения
 * ({@code limit}) — это требование к классу и слота в таблице полей <b>не занимает</b>.
 * Иначе класс получил бы лишнее поле, которого никто не объявлял.
 * <p>
 * Трейт ни от чего не зависит: ни родителя, ни подмешанных трейтов у него нет.
 * Поэтому все трейты строятся до всех классов, и порядок объявлений для них
 * не значит ничего.
 */
public final class TraitShape implements Shape {

    private final TraitDeclStmt declaration;
    private final Map<String, FieldSlot> fields;
    private final Map<String, MethodSlot> methods;
    private final List<FunctionExpr.Param> requiredFields;

    TraitShape(TraitDeclStmt declaration) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");

        Map<String, FieldSlot> withValue = new LinkedHashMap<>();
        List<FunctionExpr.Param> required = new java.util.ArrayList<>();
        List<FunctionExpr.Param> params = declaration.params();
        for (int i = 0; i < params.size(); i++) {
            FunctionExpr.Param param = params.get(i);
            if (param.hasDefault()) {
                withValue.put(param.name(), new FieldSlot(param.name(), this, i));
            } else {
                required.add(param);
            }
        }
        this.fields = Collections.unmodifiableMap(withValue);
        this.requiredFields = List.copyOf(required);

        Map<String, MethodSlot> table = new LinkedHashMap<>();
        for (FunctionExpr method : declaration.methods()) {
            table.put(method.name(), new MethodSlot(method.name(), method, this));
        }
        this.methods = Collections.unmodifiableMap(table);
    }

    public TraitDeclStmt declaration() {
        return declaration;
    }

    /** Поля, которые класс обязан объявить сам. */
    public List<FunctionExpr.Param> requiredFields() {
        return requiredFields;
    }

    /** Методы, которые класс обязан объявить сам. */
    public List<TraitDeclStmt.Requirement> requiredMethods() {
        return declaration.requirements();
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
