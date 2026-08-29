package ru.wds.wdl.interop;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Неизменяемое описание Java-типа: что у него есть и как это позвать.
 * <p>
 * <b>Кэшируется на процесс</b> — в отличие от {@link JavaClass}, который собирается
 * на каждый запуск. Развилка та же, что у {@code embed.NativeClass}: рефлексия дорога,
 * а её результат от запуска не зависит; зато поля самого класса ({@code statics})
 * скрипт вправе менять, и общий на процесс класс переносил бы это из одного запуска
 * в другой. Инвариант «никакой изменяемой статики» здесь не нарушен: кэш держит
 * только неизменяемые описания — ровно как таблицы в {@code parser.Operators}.
 *
 * <h2>Что сюда не попадает никогда</h2>
 * {@code getClass}, {@code wait}, {@code notify}, {@code notifyAll}. Первый — потому,
 * что через него скрипт за два шага доходит до загрузчика классов и до всего остального;
 * остальные — потому, что монитор объекта это не то, чем должен управлять скрипт:
 * для этого у языка есть {@code synchronized} и {@code th.lock()}.
 * Это не настройка и политикой не открывается.
 *
 * <h2>Непубличный класс заменяется публичным видом</h2>
 * Объект, пришедший из библиотеки, часто оказывается экземпляром непубличного класса:
 * {@code List.of(1)} — это {@code ImmutableCollections$List12}. Рефлексия по такому
 * классу даёт методы, которые нельзя позвать, поэтому описание строится
 * по ближайшему <b>публичному</b> предку или интерфейсу — для скрипта это тот же
 * список с теми же методами.
 */
public final class JavaShape {

    /** Имена, которых у обёртки не будет ни при какой политике. */
    static final Set<String> FORBIDDEN = Set.of("getClass", "wait", "notify", "notifyAll");

    /**
     * Кэш описаний — {@link java.lang.ClassValue}, а не карта.
     * <p>
     * Карта с ключом {@code Class} держит класс, класс держит свой загрузчик,
     * а загрузчик — весь его код. В приложении, где скрипты приходят с плагинами
     * и плагины перезагружаются (а wdl метит именно туда), это утечка загрузчика:
     * старый не соберётся никогда. {@code ClassValue} для того и сделан — значение
     * живёт ровно столько, сколько сам класс, и уходит вместе с ним.
     * <p>
     * Тёзка языкового {@code ru.wds.wdl.value.ClassValue} здесь ни при чём, поэтому
     * тип написан полным именем.
     */
    private static final java.lang.ClassValue<JavaShape> CACHE = new java.lang.ClassValue<>() {

        @Override
        protected JavaShape computeValue(Class<?> type) {
            return new JavaShape(type);
        }
    };

    private final Class<?> type;
    private final Map<String, List<JavaExecutable>> methods;
    private final Map<String, List<JavaExecutable>> staticMethods;
    private final List<JavaExecutable> constructors;
    private final Map<String, JavaFieldAccess> fields;
    private final Map<String, JavaFieldAccess> staticFields;
    private final Map<String, Bean> beans;

    private JavaShape(Class<?> type) {
        this.type = type;

        Map<String, List<JavaExecutable>> instance = new LinkedHashMap<>();
        Map<String, List<JavaExecutable>> statics = new LinkedHashMap<>();
        for (Method method : type.getMethods()) {
            if (FORBIDDEN.contains(method.getName()) || method.isSynthetic()) {
                continue;
            }
            Map<String, List<JavaExecutable>> table =
                    Modifier.isStatic(method.getModifiers()) ? statics : instance;
            table.computeIfAbsent(method.getName(), name -> new ArrayList<>())
                    .add(JavaExecutable.of(method));
        }
        this.methods = freeze(instance);
        this.staticMethods = freeze(statics);

        List<JavaExecutable> found = new ArrayList<>();
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
            for (Constructor<?> constructor : type.getConstructors()) {
                found.add(JavaExecutable.of(constructor));
            }
        }
        this.constructors = List.copyOf(found);

        Map<String, JavaFieldAccess> ownFields = new LinkedHashMap<>();
        Map<String, JavaFieldAccess> staticOnes = new LinkedHashMap<>();
        for (Field field : type.getFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            (Modifier.isStatic(field.getModifiers()) ? staticOnes : ownFields)
                    .put(field.getName(), JavaFieldAccess.of(field));
        }
        this.fields = Collections.unmodifiableMap(ownFields);
        this.staticFields = Collections.unmodifiableMap(staticOnes);
        this.beans = beansOf(this.methods);
    }

    private static Map<String, List<JavaExecutable>> freeze(Map<String, List<JavaExecutable>> table) {
        Map<String, List<JavaExecutable>> result = new LinkedHashMap<>(table.size());
        table.forEach((name, overloads) -> result.put(name, List.copyOf(overloads)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Пары {@code getX()/setX(v)} и {@code isX()} — заготовка для схемы.
     * <p>
     * Только заготовка: сама по себе такая пара свойством <b>не</b> становится.
     * Автоматическое правило «начинается на get — значит свойство» превратило бы
     * в свойство и {@code getAndIncrement()}, а свойство обещает, что за именем ничего
     * не происходит. Решает это схема, то есть человек.
     */
    private static Map<String, Bean> beansOf(Map<String, List<JavaExecutable>> methods) {
        Map<String, Bean> result = new LinkedHashMap<>();
        methods.forEach((name, overloads) -> {
            String property = propertyName(name);
            if (property == null) {
                return;
            }
            for (JavaExecutable candidate : overloads) {
                if (name.startsWith("set") && candidate.count() == 1) {
                    result.merge(property, new Bean(null, candidate), Bean::merge);
                } else if (!name.startsWith("set") && candidate.count() == 0) {
                    result.merge(property, new Bean(candidate, null), Bean::merge);
                }
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static String propertyName(String method) {
        String rest;
        if (method.startsWith("get") && method.length() > 3) {
            rest = method.substring(3);
        } else if (method.startsWith("is") && method.length() > 2) {
            rest = method.substring(2);
        } else if (method.startsWith("set") && method.length() > 3) {
            rest = method.substring(3);
        } else {
            return null;
        }
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    /** Описание типа — из кэша, а при первом обращении рефлексией. */
    public static JavaShape of(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return CACHE.get(publicView(type));
    }

    /**
     * Ближайший публичный вид типа: сам тип или самый содержательный из его
     * публичных предков и интерфейсов.
     * <p>
     * «Самый содержательный» — тот, у кого больше методов, и выбор именно такой,
     * потому что первый попавшийся почти всегда не тот: у
     * {@code ImmutableCollections$List12} первым интерфейсом стоит
     * {@code Serializable}, у которого нет ничего, а нужен {@code List} — то, чем
     * этот объект и является для того, кто его получил.
     */
    static Class<?> publicView(Class<?> type) {
        if (Modifier.isPublic(type.getModifiers())) {
            return type;
        }
        Class<?> best = Object.class;
        int bestSize = -1;
        for (Class<?> candidate : supertypes(type)) {
            if (!Modifier.isPublic(candidate.getModifiers()) || candidate == Object.class) {
                continue;
            }
            int size = candidate.getMethods().length;
            if (size > bestSize) {
                bestSize = size;
                best = candidate;
            }
        }
        return best;
    }

    /** Все предки и интерфейсы, включая унаследованные. */
    private static Set<Class<?>> supertypes(Class<?> type) {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            found.add(current);
            collectInterfaces(current, found);
        }
        return found;
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> found) {
        for (Class<?> face : type.getInterfaces()) {
            if (found.add(face)) {
                collectInterfaces(face, found);
            }
        }
    }

    public Class<?> type() {
        return type;
    }

    /** Перегрузки метода экземпляра или пустой список. */
    public List<JavaExecutable> methods(String name) {
        return methods.getOrDefault(name, List.of());
    }

    public List<JavaExecutable> staticMethods(String name) {
        return staticMethods.getOrDefault(name, List.of());
    }

    public Set<String> methodNames() {
        return methods.keySet();
    }

    public Set<String> staticMethodNames() {
        return staticMethods.keySet();
    }

    public List<JavaExecutable> constructors() {
        return constructors;
    }

    public JavaFieldAccess field(String name) {
        return fields.get(name);
    }

    public Set<String> fieldNames() {
        return fields.keySet();
    }

    public JavaFieldAccess staticField(String name) {
        return staticFields.get(name);
    }

    public Set<String> staticFieldNames() {
        return staticFields.keySet();
    }

    /** Пара аксессоров под этим именем или {@code null}. */
    public Bean bean(String name) {
        return beans.get(name);
    }

    public Set<String> beanNames() {
        return beans.keySet();
    }

    /** Все имена, которые тип готов отдать скрипту, — для сообщения о промахе. */
    public Set<String> allNames() {
        Set<String> names = new LinkedHashSet<>(fields.keySet());
        names.addAll(methods.keySet());
        return Collections.unmodifiableSet(names);
    }

    @Override
    public String toString() {
        return "JavaShape[" + type.getName() + "]";
    }

    /** Пара «читать/писать» под одним именем: {@code getTitle()} и {@code setTitle(v)}. */
    public record Bean(JavaExecutable getter, JavaExecutable setter) {

        Bean merge(Bean other) {
            return new Bean(getter != null ? getter : other.getter,
                    setter != null ? setter : other.setter);
        }
    }
}
