package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Binding;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ModuleValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>
 * Отсюда же и способ защиты от потоков — не {@code ConcurrentHashMap}, как в обычной
 * {@link Scope}, а {@link Collections#synchronizedMap обёртка} поверх
 * {@code LinkedHashMap}: конкурентная карта порядок вставки не хранит, а он здесь
 * часть обещания. Монитор у обёртки — она сама, поэтому перебор снаружи
 * ({@link ru.wds.wdl.value.types.ModuleValue#names()}) синхронизируется по тому же
 * объекту, и снимок имён нельзя застать на середине.
 * <p>
 * <b>Что модуль принёс себе развёрнутым импортом</b>, лежит третьей картой — связками,
 * а не копиями, — и уходит наружу вместе со своими именами. Поэтому реэкспорт сквозной:
 * {@code lib.x} после {@code import base} внутри {@code lib} — та же ячейка, что
 * {@code base.x}, и присваивание любой из них видят обе.
 */
final class ModuleScope implements Environment {

    private final Environment root;
    private final Map<String, Value> members = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<String> constants = Collections.synchronizedSet(new LinkedHashSet<>(4));
    /** Имена, пришедшие развёрнутым импортом. Не ленивая: область модуля одна на файл. */
    private final Map<String, Binding> aliases = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ModuleValue module;

    ModuleScope(String name, Environment root) {
        this.root = Objects.requireNonNull(root, "root");
        this.module = new ModuleValue(Objects.requireNonNull(name, "name"), members, constants, aliases);
    }

    /** Значение модуля: живой вид на эту же таблицу. */
    ModuleValue module() {
        return module;
    }

    @Override
    public Value lookup(String name) {
        Objects.requireNonNull(name, "name");
        Value value = members.get(name);
        if (value != null) {
            return value;
        }
        Binding alias = aliases.get(name);
        return alias != null ? alias.value() : root.lookup(name);
    }

    @Override
    public Set<String> namesHere() {
        Set<String> names;
        synchronized (members) {
            names = new LinkedHashSet<>(members.keySet());
        }
        synchronized (aliases) {
            names.addAll(aliases.keySet());
        }
        return Collections.unmodifiableSet(names);
    }

    @Override
    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>(namesHere());
        names.addAll(root.names());
        return Collections.unmodifiableSet(names);
    }

    @Override
    public Value lookupHere(String name) {
        Objects.requireNonNull(name, "name");
        Value value = members.get(name);
        if (value != null) {
            return value;
        }
        Binding alias = aliases.get(name);
        return alias != null ? alias.value() : null;
    }

    @Override
    public boolean isDefined(String name) {
        return lookup(name) != null;
    }

    @Override
    public void define(String name, Value value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        aliases.remove(name);
        members.put(name, value);
    }

    @Override
    public void defineConstant(String name, Value value) {
        define(name, value);
        constants.add(name);
    }

    @Override
    public void defineAlias(String name, Binding binding) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(binding, "binding");
        members.remove(name);
        constants.remove(name);
        aliases.put(name, binding);
    }

    @Override
    public boolean isConstantHere(String name) {
        Objects.requireNonNull(name, "name");
        if (constants.contains(name)) {
            return true;
        }
        Binding alias = aliases.get(name);
        return alias != null && alias.constant();
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
        if (constants.contains(name)) {
            return Assignment.CONSTANT;
        }
        // Атомарно, как и в Scope: между «есть ли имя» и «записать» соседний поток
        // вправе его завести или заменить связкой.
        if (members.replace(name, value) != null) {
            return Assignment.DONE;
        }
        Binding alias = aliases.get(name);
        if (alias != null) {
            return alias.set(value) ? Assignment.DONE : Assignment.CONSTANT;
        }
        return root.assign(name, value);
    }

    /** Реестр членов у модуля тот же, что у корня запуска: таблица одна на запуск. */
    @Override
    public ru.wds.wdl.value.MemberRegistry members() {
        return root.members();
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
