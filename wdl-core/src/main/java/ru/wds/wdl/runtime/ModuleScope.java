package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ModuleValue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Корневая область выполняемого модуля — и одновременно то, что видно снаружи
 * как {@link ModuleValue}.
 * <p>
 * Область и значение здесь <b>одна и та же таблица</b>, а не копия. Иначе пришлось бы
 * выбирать между двумя неправдами: снимок после выполнения не показывал бы работу
 * функций модуля ({@code m.counter} застыл бы на начальном значении), а копирование
 * в обе стороны рано или поздно разошлось бы.
 * <p>
 * Родителем служит корневая область запуска — та, где лежат встроенные функции
 * и библиотеки хозяина. Именно она, а не область, где написан {@code import}:
 * модуль не должен видеть локальные переменные того, кто его импортирует. Иначе
 * один и тот же модуль работал бы по-разному в зависимости от места импорта.
 * <p>
 * Порядок имён сохраняется ({@link LinkedHashMap}): развёрнутый импорт заводит их
 * в том же порядке, в каком они объявлены в файле, и вывод не пляшет от запуска
 * к запуску.
 */
final class ModuleScope implements Environment {

    private final Environment root;
    private final Map<String, Value> members = new LinkedHashMap<>();
    private final Set<String> constants = new HashSet<>(4);
    private final ModuleValue module;

    ModuleScope(String name, Environment root) {
        this.root = Objects.requireNonNull(root, "root");
        this.module = new ModuleValue(Objects.requireNonNull(name, "name"), members, constants);
    }

    /** Значение модуля: живой вид на эту же таблицу. */
    ModuleValue module() {
        return module;
    }

    @Override
    public Value lookup(String name) {
        Objects.requireNonNull(name, "name");
        Value value = members.get(name);
        return value != null ? value : root.lookup(name);
    }

    @Override
    public Value lookupHere(String name) {
        return members.get(Objects.requireNonNull(name, "name"));
    }

    @Override
    public boolean isDefined(String name) {
        return lookup(name) != null;
    }

    @Override
    public void define(String name, Value value) {
        members.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
    }

    @Override
    public void defineConstant(String name, Value value) {
        define(name, value);
        constants.add(name);
    }

    @Override
    public boolean isConstantHere(String name) {
        Objects.requireNonNull(name, "name");
        return constants.contains(name);
    }

    /**
     * Присваивание уходит наружу, если имени здесь нет, — как и у обычной области.
     * <p>
     * Наружу, впрочем, оно попадёт только в корень запуска, и это осознанно: модуль
     * может испортить встроенное имя себе и всем, кто его импортирует, ровно так же,
     * как это может сделать главный скрипт. Отдельного запрета для модулей нет —
     * модуль и есть скрипт.
     */
    @Override
    public Assignment assign(String name, Value value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (members.containsKey(name)) {
            if (constants.contains(name)) {
                return Assignment.CONSTANT;
            }
            members.put(name, value);
            return Assignment.DONE;
        }
        return root.assign(name, value);
    }

    @Override
    public Environment child() {
        return Scope.under(this);
    }

    @Override
    public String toString() {
        return "ModuleScope[" + module.name() + ", " + members.size() + " имён]";
    }
}
