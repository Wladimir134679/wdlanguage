package ru.wds.wdl.runtime.members;

import ru.wds.wdl.runtime.Types;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.ValueType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Основание таблицы членов: наборы ядра по типам значений.
 * <p>
 * <b>Статикой, и это не нарушает инварианта.</b> Под запретом изменяемая статика,
 * а здесь неизменяемые наборы без состояния — тот же случай, что таблицы
 * {@code parser.Operators} и дескрипторы в {@link Types}. Пересобирать их на каждый
 * запуск незачем: они одинаковы для всех интерпретаторов процесса. Изменяемая
 * надстройка — то, что добавили {@code extend} и приложение, — принадлежит запуску
 * и живёт в {@link MemberTable}.
 * <p>
 * <b>Перекрыть основание нельзя ничем.</b> Скрипт, переопределивший встроенный член,
 * ломает не себя, а всё, что работает с такими значениями в этом запуске, включая
 * библиотеки. Запрет проверяется при объявлении — см. {@link MemberTable#declare}.
 * <p>
 * Универсальный член {@code type} добавляется здесь одной строкой на все типы, а не
 * пишется в каждом наборе: он одинаков у всех, и десять копий значили бы десять
 * возможностей ошибиться. У {@code null} его нет вместе со всеми остальными —
 * обращение к {@code null} обязано ломаться там, где написано, за тем {@code null}
 * и нужен.
 */
public final class BuiltinMembers {

    private static final Map<ValueType, MemberSet> SETS;

    static {
        EnumMap<ValueType, MemberSet> sets = new EnumMap<>(ValueType.class);
        sets.put(ValueType.NULL, MemberSet.EMPTY);
        // У логического своих членов нет, и это ответ, а не пропуск: отрицание уже
        // есть оператором, а приведения к числу язык не позволяет намеренно.
        sets.put(ValueType.BOOL, withType(MemberSet.EMPTY));
        sets.put(ValueType.NUMBER, withType(NumberMembers.set()));
        sets.put(ValueType.STRING, withType(StringMembers.set()));
        sets.put(ValueType.ARRAY, withType(ArrayMembers.set()));
        sets.put(ValueType.OBJECT, withType(ObjectMembers.set()));
        sets.put(ValueType.FUNCTION, withType(FunctionMembers.set()));
        sets.put(ValueType.CLASS, withType(ClassMembers.set()));
        sets.put(ValueType.TRAIT, withType(TraitMembers.set()));
        sets.put(ValueType.MODULE, withType(ModuleMembers.set()));
        sets.put(ValueType.RANGE, withType(RangeMembers.set()));
        SETS = Collections.unmodifiableMap(sets);
    }

    private BuiltinMembers() {
    }

    /** Набор ядра для этого типа; пустой у {@code null}. */
    public static MemberSet of(ValueType type) {
        return SETS.getOrDefault(type, MemberSet.EMPTY);
    }

    /**
     * Тот же набор плюс член {@code type}, дающий дескриптор.
     * <p>
     * Стоит последним, а не первым: набор, объявивший {@code type} сам, получит
     * ошибку при сборке класса, а не тихое перекрытие.
     */
    private static MemberSet withType(MemberSet base) {
        return MemberSet.builder()
                .include(base)
                .property("type", (receiver, context, span) -> Types.of(receiver))
                .build();
    }
}
