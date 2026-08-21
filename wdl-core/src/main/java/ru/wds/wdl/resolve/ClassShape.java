package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.value.Arity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Форма класса: плоские таблицы полей и методов, собранные при объявлении.
 * <p>
 * Неизменяема и построена целиком, без фазы «сначала оболочка, потом дозаполним».
 * Это возможно потому, что форму строит {@link Linker} в момент, когда родитель
 * и трейты уже стали значениями: цепочка предков заведомо готова раньше, чем
 * понадобится, — иначе связывать было бы нечем.
 * <p>
 * <b>Порядок сборки — «родитель → трейты слева направо → сам класс».</b> Одинаковое
 * имя не ошибка: побеждает последний, а позиция остаётся от первого вхождения.
 * Порядок написан прямо в объявлении и читается без всяких правил линеаризации,
 * а ошибка на конфликте вынуждала бы переопределять метод только ради выбора одного
 * из двух — то есть писать код, ничего не решающий по существу.
 */
public final class ClassShape implements Shape {

    private final ClassDeclStmt declaration;
    private final ClassShape parent;
    private final List<TraitShape> traits;
    private final Map<String, FieldSlot> fields;
    private final Map<String, MethodSlot> methods;
    private final Map<String, PropertySlot> properties;
    private final Arity arity;

    ClassShape(ClassDeclStmt declaration, ClassShape parent, List<TraitShape> traits) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.parent = parent;
        this.traits = List.copyOf(traits);
        this.arity = arityOf(declaration.params(), declaration.isVariadic());

        Map<String, FieldSlot> collectedFields = new LinkedHashMap<>();
        Map<String, MethodSlot> collectedMethods = new LinkedHashMap<>();
        Map<String, PropertySlot> collectedProperties = new LinkedHashMap<>();
        if (parent != null) {
            place(collectedFields, collectedProperties, parent.fields, parent.properties);
            collectedMethods.putAll(parent.methods);
        }
        for (TraitShape trait : traits) {
            place(collectedFields, collectedProperties, trait.fields(), trait.properties());
            collectedMethods.putAll(trait.methods());
        }
        // Полями становятся только позиционные параметры: у слота есть номер параметра,
        // а остаток позиции не занимает. См. ast.stmt.ClassDeclStmt.
        Map<String, FieldSlot> ownFields = new LinkedHashMap<>();
        List<FunctionExpr.Param> params = declaration.params();
        for (int i = 0; i < params.size(); i++) {
            String name = params.get(i).name();
            ownFields.put(name, new FieldSlot(name, this, i));
        }
        Map<String, PropertySlot> ownProperties = new LinkedHashMap<>();
        for (PropertyDecl property : declaration.properties()) {
            ownProperties.put(property.name(), new PropertySlot(property.name(), property, this));
        }
        place(collectedFields, collectedProperties, ownFields, ownProperties);
        for (FunctionExpr method : declaration.methods()) {
            collectedMethods.put(method.name(), new MethodSlot(method.name(), method, this));
        }
        this.fields = Collections.unmodifiableMap(collectedFields);
        this.methods = Collections.unmodifiableMap(collectedMethods);
        this.properties = Collections.unmodifiableMap(collectedProperties);
    }

    /**
     * Кладёт поля и свойства одного уровня — родителя, трейта или самого класса.
     * <p>
     * <b>Свойство и поле делят ячейку имени</b>: оба — «место, которое читают и пишут»,
     * и отличается только то, стоит ли за именем значение или код. Поэтому пришедшее
     * с этого уровня вытесняет одноимённое из другой таблицы: побеждает последний,
     * ровно как везде в плоских таблицах. Потомок вправе заменить унаследованное поле
     * вычисляемым свойством и наоборот — это то же самое право, по которому он
     * переопределяет метод.
     * <p>
     * Внутри одного уровня конфликта не бывает: имя в теле объявляется один раз,
     * это проверил разбор.
     */
    private static void place(Map<String, FieldSlot> fields, Map<String, PropertySlot> properties,
                              Map<String, FieldSlot> addedFields,
                              Map<String, PropertySlot> addedProperties) {
        properties.keySet().removeAll(addedFields.keySet());
        fields.keySet().removeAll(addedProperties.keySet());
        fields.putAll(addedFields);
        properties.putAll(addedProperties);
    }

    /**
     * Сколько аргументов принимает {@code new}.
     * <p>
     * Обязательных столько, сколько их до первого со значением по умолчанию: парсер
     * не пропускает обязательный после необязательного, поэтому арность остаётся отрезком.
     * Правило то же, что у функции, — заголовок класса это тот же список параметров.
     */
    static Arity arityOf(List<FunctionExpr.Param> params) {
        return arityOf(params, false);
    }

    /**
     * То же там, где может быть остаток {@code *args}, — у метода и у заголовка класса:
     * верхней границы тогда нет вовсе.
     */
    static Arity arityOf(List<FunctionExpr.Param> params, boolean variadic) {
        int required = 0;
        while (required < params.size() && !params.get(required).hasDefault()) {
            required++;
        }
        return variadic ? Arity.atLeast(required) : Arity.between(required, params.size());
    }

    public ClassDeclStmt declaration() {
        return declaration;
    }

    /** Родитель или {@code null}. Родитель ровно один — из-за конструктора. */
    public ClassShape parent() {
        return parent;
    }

    public List<TraitShape> traits() {
        return traits;
    }

    /** Тело {@code def Имя()} или {@code null}. В таблицу методов конструктор не попадает. */
    public FunctionExpr constructor() {
        return declaration.constructor();
    }

    public List<ClassDeclStmt.Factory> factories() {
        return declaration.factories();
    }

    public Arity arity() {
        return arity;
    }

    /**
     * Имя остаточного параметра {@code *args} или {@code null}.
     * <p>
     * Полем остаток не становится, поэтому в {@link #fields()} его нет и искать
     * его надо здесь. Почему не становится — в {@code ast.stmt.ClassDeclStmt}.
     */
    public String restName() {
        return declaration.rest() == null ? null : declaration.rest().name();
    }

    /** Имя именованного остатка {@code **named} или {@code null}. */
    public String namedRestName() {
        return declaration.namedRest() == null ? null : declaration.namedRest().name();
    }

    /**
     * Цепочка от самого дальнего предка к этому классу.
     * <p>
     * В этом порядке выполняются конструкторы: к телу конструктора объект уже собран
     * целиком, и родительская часть должна быть готова раньше своей.
     */
    public List<ClassShape> lineage() {
        List<ClassShape> chain = new ArrayList<>();
        for (ClassShape shape = this; shape != null; shape = shape.parent) {
            chain.add(shape);
        }
        Collections.reverse(chain);
        return chain;
    }

    /** Есть ли этот класс или трейт среди предков и примесей — ответ оператору {@code is}. */
    public boolean conformsTo(Shape other) {
        for (ClassShape ancestor = this; ancestor != null; ancestor = ancestor.parent) {
            if (ancestor == other) {
                return true;
            }
            for (TraitShape trait : ancestor.traits) {
                if (trait == other) {
                    return true;
                }
            }
        }
        return false;
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
    public Map<String, PropertySlot> properties() {
        return properties;
    }

    @Override
    public String toString() {
        return "class " + name();
    }
}
