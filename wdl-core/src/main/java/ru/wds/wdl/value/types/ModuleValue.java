package ru.wds.wdl.value.types;

import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.Collections;
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
 * ведёт себя иначе, и обе разницы намеренные:
 * <ul>
 *   <li>у модуля <b>нельзя</b> спросить несуществующее имя. Состав объекта скрипт
 *       собирает сам и вправе проверять ключи, а состав модуля задан его файлом:
 *       {@code m.add} с опечаткой — ошибка автора, и говорить о ней надо здесь,
 *       а не через два шага на попытке вызвать {@code null};</li>
 *   <li>модулю <b>нельзя</b> присвоить. Иначе один скрипт менял бы то, что видят все
 *       остальные: модуль выполняется один раз за запуск, и значение у всех, кто его
 *       импортировал, общее.</li>
 * </ul>
 * <b>Таблица здесь — живая.</b> Это та же карта, в которой лежат переменные модуля,
 * а не её снимок: функция модуля, меняющая свою переменную, обязана быть видна снаружи
 * через {@code m.имя} — иначе счётчик, накопитель и любое состояние модуля работали бы
 * наполовину.
 */
public final class ModuleValue implements Value {

    private final String name;
    private final Map<String, Value> members;
    private final Set<String> constants;

    /**
     * @param name      ключ модуля, каким его нашли: {@code "lib/math"}
     * @param members   таблица имён модуля — <b>та самая</b>, в которую пишет его область
     *                  видимости, а не копия
     * @param constants имена, замороженные {@code const}; тоже живое множество —
     *                  развёрнутый импорт по нему решает, чем заводить имя у себя
     */
    public ModuleValue(String name, Map<String, Value> members, Set<String> constants) {
        this.name = Objects.requireNonNull(name, "name");
        this.members = Objects.requireNonNull(members, "members");
        this.constants = Objects.requireNonNull(constants, "constants");
    }

    public String name() {
        return name;
    }

    /** Значение имени или {@code null}, если такого имени в модуле нет. */
    public Value get(String member) {
        return members.get(Objects.requireNonNull(member, "member"));
    }

    public boolean has(String member) {
        return members.containsKey(member);
    }

    public boolean isConstant(String member) {
        return constants.contains(member);
    }

    /** Имена модуля в порядке объявления — их перебирает развёрнутый импорт. */
    public Map<String, Value> members() {
        return Collections.unmodifiableMap(members);
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
