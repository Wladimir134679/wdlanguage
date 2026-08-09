package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Value;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Область видимости: таблица имён плюс ссылка на внешнюю область.
 * <p>
 * Никакой статики: корневая область создаётся вызовом {@link #root()}, и сколько
 * таких корней живёт в процессе — столько независимых друг от друга запусков скриптов.
 * Именно это делает изоляцию интерпретаторов настоящей, а не декларируемой.
 * <p>
 * Реализация нарочно самая простая, какая работает. Оптимизировать поиск имени
 * имеет смысл после того, как появятся функции и циклы, — и делать это надо будет
 * не здесь, а в резолвере, который заменит имена на номера слотов.
 */
public final class Scope implements Environment {

    private final Environment parent;
    private final Map<String, Value> values = new HashMap<>();

    /**
     * Имена, замороженные {@code const}. Отдельным множеством, а не признаком рядом
     * со значением, потому что {@link #lookup} — самая горячая операция интерпретатора,
     * и платить в ней за возможность, которой пользуется одна инструкция из ста, незачем.
     * <p>
     * Поле заводится лениво и остаётся {@code null}, пока констант нет: областей
     * создаётся по одной на вызов функции, блок и итерацию перебора, и в подавляющем
     * большинстве из них не объявляют ничего.
     */
    private Set<String> constants;

    private Scope(Environment parent) {
        this.parent = parent;
    }

    /** Новая независимая корневая область. */
    public static Scope root() {
        return new Scope(null);
    }

    /**
     * Область поверх произвольного окружения.
     * <p>
     * Нужна там, где внешним окружением служит не другая {@code Scope}, а
     * {@link InstanceScope}: локальные имена вызова метода садятся сюда, а поля
     * и методы находятся снаружи. Отсюда и «новое имя полем не становится» —
     * {@code длина = x + y} внутри метода заводит имя здесь, а не в экземпляре.
     */
    static Scope under(Environment parent) {
        return new Scope(Objects.requireNonNull(parent, "parent"));
    }

    @Override
    public Value lookup(String name) {
        Objects.requireNonNull(name, "name");
        Value value = values.get(name);
        if (value != null) {
            return value;
        }
        return parent != null ? parent.lookup(name) : null;
    }

    @Override
    public boolean isDefined(String name) {
        return lookup(name) != null;
    }

    @Override
    public void define(String name, Value value) {
        values.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
    }

    @Override
    public void defineConstant(String name, Value value) {
        define(name, value);
        if (constants == null) {
            constants = new HashSet<>(4);
        }
        constants.add(name);
    }

    @Override
    public boolean isConstantHere(String name) {
        Objects.requireNonNull(name, "name");
        return constants != null && constants.contains(name);
    }

    @Override
    public Assignment assign(String name, Value value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (values.containsKey(name)) {
            if (isConstantHere(name)) {
                return Assignment.CONSTANT;
            }
            values.put(name, value);
            return Assignment.DONE;
        }
        return parent != null ? parent.assign(name, value) : Assignment.ABSENT;
    }

    @Override
    public Environment child() {
        return new Scope(this);
    }

    @Override
    public String toString() {
        return "Scope[" + values.size() + " имён" + (parent != null ? ", вложенная]" : ", корневая]");
    }
}
