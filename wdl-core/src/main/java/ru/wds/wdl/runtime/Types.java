package ru.wds.wdl.runtime;

import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Все десять {@linkplain TypeValue дескрипторов типов} — по одному на элемент
 * {@link ValueType}, статикой на весь процесс.
 * <p>
 * Реестра по имени, как у {@link PreludeTypes}, здесь не нужно: прелюдии он нужен
 * потому, что её классы создаются в области видимости заново на каждый запуск,
 * а дескрипторы статические и общие на процесс — движок берёт их прямо из этой
 * таблицы, а не поиском имени в области.
 * <p>
 * Публичный класс: {@code wdl-stdlib} и встраивающее приложение получают дескриптор
 * тем же способом, что и ядро, — без собственной копии таблицы. Сам {@link TypeValue}
 * при этом наружу не выходит: снаружи с дескриптором можно ровно то же, что и с любым
 * другим {@link ClassValue}.
 */
public final class Types {

    private static final Map<ValueType, TypeValue> ALL = build();

    private Types() {
    }

    private static Map<ValueType, TypeValue> build() {
        Map<ValueType, TypeValue> table = new EnumMap<>(ValueType.class);
        for (ValueType type : ValueType.values()) {
            table.put(type, new TypeValue(type));
        }
        return Collections.unmodifiableMap(table);
    }

    /** Дескриптор этого типа — всегда один и тот же экземпляр. */
    public static ClassValue of(ValueType type) {
        return ALL.get(Objects.requireNonNull(type, "type"));
    }

    /** Дескриптор типа этого значения — то же самое, что вернёт {@code typeof(value)}. */
    public static ClassValue of(Value value) {
        return of(Objects.requireNonNull(value, "value").type());
    }

    /**
     * Все десять дескрипторов — чтобы установить их в область видимости одним циклом
     * ({@link Builtins#installTo}) и чтобы новый элемент {@link ValueType} сам собой
     * попадал туда же, без второй правки в вызывающем коде.
     */
    public static Collection<ClassValue> all() {
        return List.copyOf(ALL.values());
    }
}
