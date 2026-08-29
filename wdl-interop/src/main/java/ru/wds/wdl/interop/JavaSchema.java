package ru.wds.wdl.interop;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Что именно у Java-типа видно скрипту.
 * <p>
 * Два режима, и разница между ними — это разница между «покажи всё» и «покажи вот это»:
 * <pre>{@code
 * JavaSchema.all(LocalDate.class).as("Date")          // все public члены
 *
 * JavaSchema.of(Connection.class).as("Db")            // только перечисленное
 *         .method("prepareStatement")
 *         .method("commit")
 *         .bean("autoCommit")                         // getAutoCommit/setAutoCommit → .autoCommit
 *         .noConstructors()
 * }</pre>
 *
 * <h2>Схема проверяется при сборке моста</h2>
 * Метода с таким именем нет, поле не существует, у пары аксессоров не тот вид —
 * исключение при старте приложения, а не через месяц у автора скрипта. Это то же
 * обещание и тот же момент, что у {@code embed.NativeClass} с требованиями трейта:
 * ошибку в описании должен ловить тот, кто описание писал.
 *
 * <h2>Свойство из аксессоров — только по просьбе</h2>
 * {@link Builder#bean(String)} объявляется руками даже в режиме «показать всё».
 * Автоматическое правило «начинается на get — это свойство» превратило бы в свойство
 * и {@code getAndIncrement()}, а свойство языка обещает, что за именем ничего
 * не происходит ({@code docs/members.md}). Решать это должен человек.
 */
public final class JavaSchema {

    private final Class<?> type;
    private final String name;
    private final boolean all;
    /** Имя в скрипте → имя метода в Java. */
    private final Map<String, String> methods;
    private final Set<String> fields;
    /** Имя свойства → пара аксессоров. */
    private final Map<String, String> beans;
    private final Set<String> readOnly;
    private final boolean constructors;
    /** Имя в скрипте → имя статического метода Java. */
    private final Map<String, String> factories;

    private JavaSchema(Builder builder) {
        this.type = builder.type;
        this.name = builder.name;
        this.all = builder.all;
        this.methods = Map.copyOf(builder.methods);
        this.fields = Set.copyOf(builder.fields);
        this.beans = Map.copyOf(builder.beans);
        this.readOnly = Set.copyOf(builder.readOnly);
        this.constructors = builder.constructors;
        this.factories = Map.copyOf(builder.factories);
    }

    /** Схема «всё, что public»: имя класса берётся простое — {@code LocalDate}. */
    public static JavaSchema all(Class<?> type) {
        return new Builder(type, true).build();
    }

    /** Схема, в которой видно только перечисленное. */
    public static Builder of(Class<?> type) {
        return new Builder(type, false);
    }

    /** Схема «всё, что public», которую ещё нужно дополнить свойствами или именем. */
    public static Builder allOf(Class<?> type) {
        return new Builder(type, true);
    }

    public Class<?> type() {
        return type;
    }

    public String name() {
        return name;
    }

    public boolean showsEverything() {
        return all;
    }

    public boolean constructorsAllowed() {
        return constructors;
    }

    /** Имя метода Java под этим именем в скрипте или {@code null}, если он не открыт. */
    String methodOf(String scriptName) {
        if (beans.containsKey(scriptName)) {
            return null;
        }
        String renamed = methods.get(scriptName);
        if (renamed != null) {
            return renamed;
        }
        return all && !JavaShape.FORBIDDEN.contains(scriptName) ? scriptName : null;
    }

    boolean fieldAllowed(String fieldName) {
        return all || fields.contains(fieldName);
    }

    /** Имя пары аксессоров под этим именем свойства или {@code null}. */
    String beanOf(String scriptName) {
        return beans.get(scriptName);
    }

    boolean readOnly(String scriptName) {
        return readOnly.contains(scriptName);
    }

    Map<String, String> factories() {
        return factories;
    }

    Set<String> declaredMethods() {
        return methods.keySet();
    }

    Set<String> declaredFields() {
        return fields;
    }

    Map<String, String> declaredBeans() {
        return beans;
    }

    Set<String> readOnlyNames() {
        return readOnly;
    }

    public static final class Builder {

        private final Class<?> type;
        private final boolean all;
        private String name;
        private final Map<String, String> methods = new LinkedHashMap<>();
        private final Set<String> fields = new LinkedHashSet<>();
        private final Map<String, String> beans = new LinkedHashMap<>();
        private final Set<String> readOnly = new LinkedHashSet<>();
        private final Map<String, String> factories = new LinkedHashMap<>();
        private boolean constructors = true;

        private Builder(Class<?> type, boolean all) {
            this.type = Objects.requireNonNull(type, "type");
            this.all = all;
            this.name = type.getSimpleName();
        }

        /** Имя, под которым тип встанет в область видимости скрипта. */
        public Builder as(String scriptName) {
            this.name = Objects.requireNonNull(scriptName, "name");
            return this;
        }

        /** Открыть метод — все его перегрузки. */
        public Builder method(String methodName) {
            return methodAs(methodName, methodName);
        }

        /**
         * Открыть метод под другим именем: {@code getConnection} → {@code open}.
         *
         * @param scriptName как метод зовут в скрипте
         * @param javaName   как он называется в Java
         */
        public Builder methodAs(String scriptName, String javaName) {
            methods.put(scriptName, javaName);
            return this;
        }

        /** Открыть публичное поле. */
        public Builder field(String fieldName) {
            fields.add(fieldName);
            return this;
        }

        /** Сделать свойством пару {@code getX()/setX(v)} или один {@code isX()}. */
        public Builder bean(String propertyName) {
            return beanAs(propertyName, propertyName);
        }

        /** То же, но имя свойства в скрипте отличается от имени в Java. */
        public Builder beanAs(String scriptName, String javaProperty) {
            beans.put(scriptName, javaProperty);
            return this;
        }

        /**
         * Запретить запись в это поле или свойство.
         * <p>
         * Не то же, что {@code final} в Java: там запрет физический, а здесь —
         * решение приложения. Сообщение автор скрипта получит одно и то же —
         * «только для чтения», как у свойства на wdl.
         */
        public Builder readOnly(String memberName) {
            readOnly.add(memberName);
            return this;
        }

        /**
         * Запретить {@code new}: объекты этого типа приходят только из фабрик
         * или от приложения.
         */
        public Builder noConstructors() {
            this.constructors = false;
            return this;
        }

        /** Открыть статический метод как фабрику: {@code Date.of(2026, 8, 29)}. */
        public Builder factory(String factoryName) {
            return factoryAs(factoryName, factoryName);
        }

        public Builder factoryAs(String scriptName, String javaName) {
            factories.put(scriptName, javaName);
            return this;
        }

        public JavaSchema build() {
            return new JavaSchema(this);
        }
    }
}
