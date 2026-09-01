package ru.wds.wdl.bridge.reflect;

import java.util.List;
import java.util.Set;

/**
 * Что мосту разрешено. По умолчанию — почти ничего.
 * <p>
 * Мост, открытый целиком, — это дыра длиной в две строки:
 * {@code obj.getClass().getClassLoader()}, и дальше скрипт делает в процессе что
 * захочет. Поэтому запреты здесь не «настройки по умолчанию», а два разных уровня:
 * <ul>
 *   <li><b>чёрный список</b> ({@link #forbidden}) не отключается ничем. Ни схема,
 *       ни {@code wrapUnknown} не откроют {@code Class}, {@code ClassLoader},
 *       {@code System} или что-нибудь из {@code java.lang.reflect}: открыв один
 *       такой тип, дальше можно не запрещать ничего;</li>
 *   <li><b>остальное</b> закрыто, пока приложение не откроет. Тип, который никто
 *       не открывал, скрипту не виден — даже если Java-метод его вернул.</li>
 * </ul>
 *
 * <h2>Почему неизвестный возврат по умолчанию — отказ</h2>
 * Приложение, открывшее {@code Connection}, редко имеет в виду «и всё, до чего
 * от него можно дойти». Автоматическая обёртка чего угодно превратила бы список
 * открытых типов в список <i>корней</i>, из которых достижимо полJDK. Отказ при этом
 * не тупик: в сообщении сказано, что делать, а {@link Builder#wrapUnknown(boolean)}
 * включает старое поведение одной строкой — для инструментов и отладки, где это
 * как раз удобно.
 */
public final class JavaPolicy {

    /**
     * Пакеты, из которых не открывается ничего.
     * <p>
     * Рефлексия и дескрипторы — потому что через них скрипт обходит сам мост;
     * {@code sun.misc} — потому что там {@code Unsafe}.
     */
    private static final List<String> FORBIDDEN_PACKAGES =
            List.of("java.lang.reflect", "java.lang.invoke", "jdk.internal", "sun.misc");

    private static final Set<Class<?>> FORBIDDEN_TYPES = Set.of(
            Class.class, ClassLoader.class, Module.class, ModuleLayer.class,
            System.class, Runtime.class, ProcessBuilder.class, Process.class,
            Thread.class, ThreadGroup.class);

    private final boolean wrapUnknown;
    private final List<String> lookupPackages;

    private JavaPolicy(Builder builder) {
        this.wrapUnknown = builder.wrapUnknown;
        this.lookupPackages = List.copyOf(builder.lookupPackages);
    }

    /** По умолчанию: открыто только то, что открыли явно. */
    public static JavaPolicy strict() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Оборачивать ли Java-объект, тип которого мосту не открывали. */
    public boolean wrapUnknown() {
        return wrapUnknown;
    }

    /** Пакеты, из которых скрипту разрешено доставать типы по имени. Обычно пуст. */
    public List<String> lookupPackages() {
        return lookupPackages;
    }

    /** Разрешено ли доставать этот тип по имени из скрипта. */
    public boolean lookupAllowed(String className) {
        return lookupPackages.stream().anyMatch(prefix -> className.startsWith(prefix + "."));
    }

    /**
     * Тип, который не открывается никогда и ничем.
     * <p>
     * Проверяется и при {@code expose}, и при попытке обернуть возвращённый объект:
     * запрет, который можно обойти возвратом из метода, — не запрет.
     */
    public static boolean forbidden(Class<?> type) {
        Class<?> subject = type.isArray() ? type.getComponentType() : type;
        if (subject.isPrimitive()) {
            return false;
        }
        if (FORBIDDEN_TYPES.contains(subject)) {
            return true;
        }
        String name = subject.getName();
        for (String prefix : FORBIDDEN_PACKAGES) {
            if (name.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    public static final class Builder {

        private boolean wrapUnknown;
        private List<String> lookupPackages = List.of();

        private Builder() {
        }

        /**
         * Оборачивать ли объект неизвестного мосту типа вместо отказа.
         * <p>
         * Удобно в инструментах и на отладке, опасно в приложении, которое отдаёт
         * скрипт чужим рукам: открытым становится всё, до чего дотягиваются методы
         * открытых типов.
         */
        public Builder wrapUnknown(boolean allowed) {
            this.wrapUnknown = allowed;
            return this;
        }

        /**
         * Разрешает скрипту доставать типы по имени из этих пакетов
         * ({@code java.type("java.time.LocalDate")}).
         * <p>
         * По умолчанию не разрешено ниоткуда: доступ по имени нужен инструментам,
         * а не скриптам, и включать его стоит осознанно.
         */
        public Builder allowLookup(String... packages) {
            this.lookupPackages = List.of(packages);
            return this;
        }

        public JavaPolicy build() {
            return new JavaPolicy(this);
        }
    }
}
