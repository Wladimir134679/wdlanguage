package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.value.ValueType;

import java.util.Collection;
import java.util.List;

/**
 * Имена, которых в файле нет: встроенные функции, библиотеки хозяина движка,
 * встроенные модули.
 * <p>
 * <b>Каталог снимается с движка, а не пишется рядом с ним.</b> Всё, кроме описания
 * словами, уже лежит в живых значениях: состав модуля отдаёт
 * {@link ru.wds.wdl.value.types.ModuleValue#members()}, имена параметров —
 * {@link ru.wds.wdl.value.Signature}, форму класса — {@link ru.wds.wdl.value.ClassValue},
 * членов типа — {@link ru.wds.wdl.runtime.members.BuiltinMembers}. Второй список,
 * написанный руками рядом с первым, умеет ровно одно — разойтись с ним. Снимает его
 * {@link Catalogs}.
 * <p>
 * <b>Каталог подаётся снаружи.</b> Здесь только интерфейс и снимающая машинка над
 * типами ядра: какие библиотеки и модули есть в этом запуске, знает тот, кто его
 * собрал, — консоль, сервер, приложение со встроенным движком. Значение
 * по умолчанию — {@link #empty()}, и с ним всё работает, просто молча.
 * <p>
 * <b>Промах — не ошибка.</b> Каталог отвечает «вот это имя — встроенная функция»,
 * но не отвечает «такого имени нет»: полноты он не обещает, потому что хозяин движка
 * кладёт в корень что хочет и когда хочет. Диагностика «имя не определено» вправе
 * опираться на каталог только тогда, когда он сам сказал {@link #complete()}.
 */
public interface Catalog {

    /** Имена корневой области: {@code println}, {@code Number}, {@code sqrt}. */
    Collection<SymbolDescriptor> roots();

    /** Имя корневой области или {@code null}. */
    default SymbolDescriptor root(String name) {
        for (SymbolDescriptor descriptor : roots()) {
            if (descriptor.name().equals(name)) {
                return descriptor;
            }
        }
        return null;
    }

    /**
     * Состав модуля по ключу ({@code "sys/io"}) или {@code null}, если такого модуля
     * каталог не знает.
     * <p>
     * Спрашивается лениво и по одному: снять модуль — значит установить библиотеку,
     * а это выполнение нашего кода ({@code sys.gui} тянет Swing). Снимается тот,
     * о котором спросили.
     */
    ModuleDescriptor module(String key);

    /** Ключи известных модулей: из них собирается дополнение путей после {@code import}. */
    default Collection<String> moduleKeys() {
        return List.of();
    }

    /** Члены типа: {@code a.size}, {@code text.upper()}. */
    default List<MemberDescriptor> members(ValueType type) {
        return Catalogs.members(type);
    }

    /**
     * Обещает ли каталог полноту: перечислено ли в нём всё, что окажется в корневой
     * области запуска.
     * <p>
     * По умолчанию — нет, и это честный ответ для почти любого каталога: приложение
     * вправе завести имя после его снятия. Полноту заявляет тот, кто собрал запуск
     * целиком и знает, что больше туда никто не пишет; только на такой ответ вправе
     * опереться инспекция «имя не определено».
     */
    default boolean complete() {
        return false;
    }

    /** Каталог, который ничего не знает: рабочее значение по умолчанию. */
    static Catalog empty() {
        return EmptyCatalog.INSTANCE;
    }

    /**
     * Несколько каталогов одним: встроенное плюс библиотеки плюс модули.
     * <p>
     * Первый сильнее: имя, найденное раньше, вытесняет одноимённое из следующих
     * каталогов — то же правило, по которому ближняя область видимости сильнее
     * дальней. Порядок сохраняется.
     */
    static Catalog merged(Catalog... catalogs) {
        return MergedCatalog.of(List.of(catalogs));
    }

    /** То же сложение списком. */
    static Catalog merged(List<Catalog> catalogs) {
        return MergedCatalog.of(catalogs);
    }
}
