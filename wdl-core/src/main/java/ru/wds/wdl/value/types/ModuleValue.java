package ru.wds.wdl.value.types;

import ru.wds.wdl.value.Binding;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Модуль: имена, объявленные в корне другого файла.
 * <p>
 * Это то, что кладёт в переменную именованный импорт — {@code import lib.math as m}.
 * Дальше никакого особого синтаксиса не нужно: {@code m.PI} — обычное обращение,
 * такое же, как к полю объекта, и {@code new m.Point(1, 2)} работает по той же причине.
 * <p>
 * <b>Почему не объект.</b> Устроен модуль так же — набор пар «имя — значение», — но
 * одно отличие есть, и оно намеренное: у модуля <b>нельзя</b> спросить или завести
 * несуществующее имя. Состав объекта скрипт собирает сам и вправе проверять ключи,
 * а состав модуля задан его файлом: {@code m.add} с опечаткой — ошибка автора,
 * и говорить о ней надо здесь, а не через два шага на попытке вызвать {@code null}.
 * <p>
 * <b>Изменять модуль можно.</b> Снаружи с ним разрешено ровно то же, что его
 * собственному коду: {@code m.a = 10} меняет переменную модуля, {@code m.PI = 4}
 * отказывает, если {@code PI} заморожен {@code const}. Иного правила и не получилось бы
 * объяснить — модуль и есть скрипт, а запрет снаружи означал бы, что импортёр слабее
 * автора файла без всякой причины. Цена честная и следует из однократности выполнения:
 * запись видят все, кто импортировал модуль, — как видят они и работу его функций.
 * <p>
 * <b>Таблица здесь — живая.</b> Это та же карта, в которой лежат переменные модуля,
 * а не её снимок: функция модуля, меняющая свою переменную, обязана быть видна снаружи
 * через {@code m.имя} — иначе счётчик, накопитель и любое состояние модуля работали бы
 * наполовину.
 * <p>
 * <b>Что принёс сам модуль развёрнутым импортом</b>, лежит отдельной картой
 * {@link Binding связок} и спрашивается при промахе по своим именам. Так реэкспорт
 * остаётся сквозным: {@code lib.x} и {@code base.x} — одна ячейка, а не две копии,
 * разошедшиеся после первого присваивания.
 */
public final class ModuleValue implements Value {

    private final String name;
    private final Map<String, Value> members;
    private final Set<String> constants;
    private final Map<String, Binding> aliases;

    /**
     * @param name      ключ модуля, каким его нашли: {@code "lib/math"}
     * @param members   таблица имён модуля — <b>та самая</b>, в которую пишет его область
     *                  видимости, а не копия
     * @param constants имена, замороженные {@code const}; тоже живое множество —
     *                  по нему присваивание решает, отказывать или писать
     * @param aliases   имена, пришедшие в модуль развёрнутым импортом: связки на чужие
     *                  ячейки. Живая карта, как и остальные две
     */
    public ModuleValue(String name, Map<String, Value> members, Set<String> constants,
                       Map<String, Binding> aliases) {
        this.name = Objects.requireNonNull(name, "name");
        this.members = Objects.requireNonNull(members, "members");
        this.constants = Objects.requireNonNull(constants, "constants");
        this.aliases = Objects.requireNonNull(aliases, "aliases");
    }

    /** Модуль без связок: столько нужно тому, кто собирает имена сам. */
    public ModuleValue(String name, Map<String, Value> members, Set<String> constants) {
        this(name, members, constants, Map.of());
    }

    public String name() {
        return name;
    }

    /** Значение имени или {@code null}, если такого имени в модуле нет. */
    public Value get(String member) {
        Objects.requireNonNull(member, "member");
        Value value = members.get(member);
        if (value != null) {
            return value;
        }
        Binding alias = aliases.get(member);
        return alias != null ? alias.value() : null;
    }

    public boolean has(String member) {
        Objects.requireNonNull(member, "member");
        return members.containsKey(member) || aliases.containsKey(member);
    }

    public boolean isConstant(String member) {
        Objects.requireNonNull(member, "member");
        if (constants.contains(member)) {
            return true;
        }
        Binding alias = aliases.get(member);
        return alias != null && alias.constant();
    }

    /**
     * Записывает значение в имя модуля.
     * <p>
     * Отсутствие имени и константа сюда дойти не должны: спрашивает о них тот, у кого
     * есть место в исходнике, — иначе сообщение не на что повесить. Это то же
     * разделение обязанностей, что у окружения, которое не заводит имя само.
     *
     * @throws IllegalStateException если имени нет или оно заморожено {@code const} —
     *                               значит, проверку пропустил движок, а не скрипт
     */
    public void set(String member, Value value) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(value, "value");
        if (members.containsKey(member)) {
            if (constants.contains(member)) {
                throw new IllegalStateException("'" + member + "' в модуле '" + name + "' — константа");
            }
            members.put(member, value);
            return;
        }
        Binding alias = aliases.get(member);
        if (alias == null) {
            throw new IllegalStateException("в модуле '" + name + "' нет имени '" + member + "'");
        }
        if (!alias.set(value)) {
            throw new IllegalStateException("'" + member + "' в модуле '" + name + "' — константа");
        }
    }

    /** Собственные имена модуля в порядке объявления, без пришедших развёрнутым импортом. */
    public Map<String, Value> members() {
        return Collections.unmodifiableMap(members);
    }

    /**
     * Все имена модуля — их и переносит развёрнутый импорт.
     * <p>
     * Свои идут первыми, пришедшие импортом — следом. Порядок внутри каждой группы
     * сохранён, и этого достаточно: он нужен, чтобы вывод не плясал от запуска
     * к запуску, а не чтобы повторять расстановку строк в файле.
     */
    public Set<String> names() {
        if (aliases.isEmpty()) {
            return Collections.unmodifiableSet(members.keySet());
        }
        Set<String> all = new LinkedHashSet<>(members.keySet());
        all.addAll(aliases.keySet());
        return Collections.unmodifiableSet(all);
    }

    @Override
    public ValueType type() {
        return ValueType.MODULE;
    }

    /**
     * Печатается заголовком, а не содержимым: у модуля в корне бывают десятки имён,
     * и {@code println(m)} не должен выливать в консоль весь файл.
     */
    @Override
    public String display() {
        return "module " + name;
    }

    @Override
    public String toString() {
        return display();
    }
}
