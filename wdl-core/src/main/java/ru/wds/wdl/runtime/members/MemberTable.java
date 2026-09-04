package ru.wds.wdl.runtime.members;

import ru.wds.wdl.value.MemberLookup;
import ru.wds.wdl.value.MemberRegistry;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Member;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Надстройка над основанием: члены, которые добавили {@code extend} и приложение.
 * <p>
 * <b>Принадлежит запуску, а не процессу.</b> Два интерпретатора в одном процессе
 * не делят расширения — иначе одна строка одного скрипта меняла бы поведение
 * значений в чужом. Основание при этом общее и неизменяемое ({@link BuiltinMembers}),
 * и это правильное разделение: неизменяемое — статикой, изменяемое — на запуск.
 * <p>
 * <b>Конкурентная карта, а не карта под замком.</b> Правило ядра «изменяемое состояние
 * запуска потокобезопасно» распространяется на неё без исключений: расширение может
 * появиться в середине работы — {@code import} законен в теле функции и в потоке,
 * а выполнение модуля это выполнение его верхнего уровня.
 * <p>
 * <b>Ключ — идентичность, а не имя.</b> У типа ключ — {@link ValueType} (расширяются
 * все массивы разом), у класса — само значение класса: два класса {@code Point}
 * из разных модулей расширяются независимо, и общее имя их не роднит.
 */
public final class MemberTable implements MemberRegistry, MemberLookup {

    private final ConcurrentMap<Object, ConcurrentMap<String, Member>> added = new ConcurrentHashMap<>();

    /** Добавленный член или {@code null}. Основание здесь не ищется — оно спрашивается раньше. */
    @Override
    public Member added(Object key, String name) {
        ConcurrentMap<String, Member> members = added.get(key);
        return members == null ? null : members.get(name);
    }

    /**
     * Член, добавленный этому классу или любому его предку.
     * <p>
     * Расширение родителя работает у потомка — иначе {@code extend Shape} было бы
     * правилом с дырой посередине. Точное совпадение проверяется первым, и только
     * промах ведёт к обходу: обход идёт по расширенным классам, а их единицы,
     * тогда как промах по данным — обычное дело.
     */
    @Override
    public Member addedForClass(ClassValue owner, String name) {
        Member exact = added(owner, name);
        if (exact != null || added.isEmpty()) {
            return exact;
        }
        for (Map.Entry<Object, ConcurrentMap<String, Member>> entry : added.entrySet()) {
            if (entry.getKey() instanceof ClassValue key && key != owner && owner.conformsTo(key)) {
                Member member = entry.getValue().get(name);
                if (member != null) {
                    return member;
                }
            }
        }
        return null;
    }

    /**
     * Объявляет член. Отказов ровно два, и оба — ошибка на строке объявления.
     * <p>
     * <b>Встроенное перекрыть нельзя:</b> член ядра — договорённость всего запуска,
     * и переопределивший её скрипт ломает библиотеки, которые работают с теми же
     * значениями. <b>Повторное объявление тем же именем — тоже ошибка</b>, с указанием
     * на первое: тихая победа последнего здесь худший из возможных исходов, потому что
     * оба объявления могут быть в разных файлах и оба выглядеть правильными.
     *
     * @param key      ключ таблицы: {@link ValueType} или значение класса
     * @param label    как назвать цель в сообщении: {@code "Array"}, {@code "Point"}
     * @param reserved имена, которые уже заняты у цели помимо этой таблицы: члены ядра,
     *                 а у класса ещё и его методы со свойствами
     */
    public void declare(Object key, String label, Member member, List<String> reserved, Span span) {
        if (reserved.contains(member.name())) {
            throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "у '" + label + "' уже есть член '"
                    + member.name() + "': встроенное и объявленное в классе перекрывать нельзя — "
                    + "это сломало бы чужой код, который работает с такими же значениями");
        }
        ConcurrentMap<String, Member> members = added.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        if (members.putIfAbsent(member.name(), member) != null) {
            throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "член '" + member.name()
                    + "' у '" + label + "' уже объявлен в этом запуске");
        }
    }

    /**
     * Установка от приложения: тот же путь, что у {@code extend}, но отказ — обычное
     * {@link IllegalArgumentException}, а не ошибка скрипта.
     * <p>
     * Разница не косметическая: у скрипта есть место в исходнике и человек, который
     * его напишет, а у библиотеки — стек Java и разработчик, который читает его в логе.
     * Заворачивать второе в {@code WdlRuntimeError} со спаном «ниоткуда» значило бы
     * врать про место ошибки.
     */
    @Override
    public void install(ValueType type, MemberSet members) {
        install(type, type.title(), members, BuiltinMembers.of(type).names());
    }

    @Override
    public void install(ClassValue owner, MemberSet members) {
        List<String> reserved = new ArrayList<>(owner.methodNames());
        reserved.addAll(owner.propertyNames());
        reserved.addAll(BuiltinMembers.of(ValueType.OBJECT).names());
        install(owner, owner.name(), members, reserved);
    }

    private void install(Object key, String label, MemberSet members, java.util.Collection<String> reserved) {
        ConcurrentMap<String, Member> table = added.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        for (String name : members.names()) {
            if (reserved.contains(name)) {
                throw new IllegalArgumentException("член '" + name + "' у '" + label
                        + "' уже есть: встроенное перекрывать нельзя");
            }
            if (table.putIfAbsent(name, members.get(name)) != null) {
                throw new IllegalArgumentException("член '" + name + "' у '" + label
                        + "' уже объявлен в этом запуске");
            }
        }
    }

    /** Имена, добавленные этому ключу, — для сообщения «а есть вот что». */
    public List<String> names(Object key) {
        ConcurrentMap<String, Member> members = added.get(key);
        return members == null ? List.of() : new ArrayList<>(members.keySet());
    }

    /**
     * Все имена, видимые у типа: основание плюс надстройка. Считается только на пути
     * ошибки — на удачном этого кода нет.
     */
    public List<String> allNames(ValueType type) {
        MemberSet builtin = BuiltinMembers.of(type);
        List<String> names = new ArrayList<>(builtin.names());
        names.addAll(names(type));
        return names;
    }
}
