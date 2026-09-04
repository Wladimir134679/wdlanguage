package ru.wds.wdl.value;

/**
 * Поиск в надстройке членов: то, что добавили запуску {@code extend} и приложение.
 * <p>
 * Отдельно от {@link MemberRegistry}, хотя таблица одна и та же. Причина — стороны
 * разные: {@code MemberRegistry} видит приложение, и там только установка; здесь же
 * чтение, и нужно оно тому, кто выполняет выражение. Слить их в один интерфейс значило
 * бы показать библиотекам ключи чужой таблицы без всякой на то нужды.
 * <p>
 * <b>Зачем это в {@code CallContext}.</b> Оператор класса спрашивается не только
 * из интерпретатора: {@code a.sort()} обязан упорядочивать ровно тем же
 * {@code `<=>`}, что и {@code a[0] < a[1]}, а тело члена типа получает от среды
 * один {@link CallContext}. Не будь поиска в контексте, у сортировки не осталось бы
 * способа спросить класс — и она разошлась бы с оператором на первом же массиве
 * экземпляров.
 */
public interface MemberLookup {

    /** Пустая надстройка: ничего не добавлено. */
    static MemberLookup none() {
        return Empty.INSTANCE;
    }

    /**
     * Член, добавленный этому ключу, или {@code null}.
     * <p>
     * Ключ — {@link ValueType} у типа и само значение класса у класса: два
     * одноимённых класса из разных модулей расширяются независимо.
     */
    Member added(Object key, String name);

    /** Член, добавленный этому классу или любому его предку, или {@code null}. */
    Member addedForClass(ClassValue owner, String name);

    /** Реализация «ничего не добавлено» — для контекста без запуска. */
    final class Empty implements MemberLookup {

        private static final Empty INSTANCE = new Empty();

        private Empty() {
        }

        @Override
        public Member added(Object key, String name) {
            return null;
        }

        @Override
        public Member addedForClass(ClassValue owner, String name) {
            return null;
        }
    }
}
