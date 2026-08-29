package ru.wds.wdl.interop;

import ru.wds.wdl.embed.Library;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.NullValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Мост в Java: что открыто скрипту и как открытое выглядит.
 * <p>
 * Обычная {@link Library}, а не «режим движка»: подключили к этому запуску — есть,
 * не подключили — нет; отдельной таблицы у языка не появляется, как её нет
 * ни у функций, ни у классов.
 * <pre>{@code
 * JavaBridge bridge = JavaBridge.open()
 *         .expose(LocalDate.class, "Date")
 *         .expose(JavaSchema.of(Connection.class).as("Db")
 *                 .method("commit")
 *                 .bean("autoCommit")
 *                 .noConstructors())
 *         .build();
 *
 * bridge.installTo(scope);
 * scope.define("now", bridge.wrap(Instant.now()));   // и готовый объект тоже
 * }</pre>
 *
 * <h2>Открытый тип — это ещё и шаблон обёртки</h2>
 * Объект, вернувшийся из метода, почти никогда не того класса, который открывали:
 * открыли {@code Connection}, а пришёл {@code JdbcConnection}. Поэтому обёртка ищется
 * не по точному классу, а по <b>самому близкому</b> из открытых типов, которому объект
 * принадлежит, — и схема, написанная для {@code Connection}, работает для всех
 * его реализаций.
 *
 * <h2>Классы принадлежат запуску</h2>
 * Мост собирает {@link JavaClass} на себя, а себя — на запуск. Причина та же, что
 * у {@code embed.NativeClass}: {@link JavaClass#statics()} у класса свои, и общий
 * на процесс класс переносил бы состояние одного скрипта в другой. Кэшируется между
 * запусками только неизменяемое описание типа ({@link JavaShape}).
 */
public final class JavaBridge implements Library {

    private final JavaPolicy policy;
    private final Map<String, Value> names;
    /** Открытые типы: тип Java → класс языка. Порядок объявления сохранён. */
    private final Map<Class<?>, JavaClass> exposed;
    /**
     * Чем оборачивать объект этого класса — одна таблица на все случаи.
     * <p>
     * Здесь и открытые типы, и найденные для них наследники, и собранные
     * при {@code wrapUnknown}. Одна таблица, а не две, потому что вопрос один:
     * «класс объекта — а какой у него класс языка». Она же и кэш: без неё поиск
     * ближайшего открытого типа шёл бы перебором на <b>каждую</b> обёртку,
     * то есть на каждый возврат объекта в цикле.
     */
    private final Map<Class<?>, JavaClass> wrappers = new ConcurrentHashMap<>();
    private final Marshal marshal;

    private JavaBridge(Builder builder) {
        this.policy = builder.policy;
        this.marshal = Marshal.of(this::wrapObject);
        this.exposed = new LinkedHashMap<>();
        this.names = new LinkedHashMap<>();

        for (JavaSchema schema : builder.schemas) {
            if (JavaPolicy.forbidden(schema.type())) {
                throw new IllegalArgumentException("тип " + schema.type().getName()
                        + " не открывается мостом ни при какой политике");
            }
            JavaClass declared = new JavaClass(schema, this);
            exposed.put(schema.type(), declared);
            put(schema.name(), declared);
        }
        wrappers.putAll(exposed);
        // Статика собирается после того, как все классы уже в таблице: константа
        // бывает того же типа, что её класс, и обёртку для неё надо где-то найти.
        exposed.values().forEach(JavaClass::initStatics);
        builder.traits.forEach((traitName, type) -> put(traitName, new JavaTrait(traitName, type)));
    }

    private void put(String name, Value value) {
        if (names.putIfAbsent(name, value) != null) {
            throw new IllegalArgumentException("имя '" + name + "' мост уже занял: "
                    + "укажите другое через JavaSchema.as(...)");
        }
    }

    public static Builder open() {
        return new Builder();
    }

    @Override
    public String name() {
        return "java";
    }

    @Override
    public Environment installTo(Environment scope) {
        names.forEach(scope::define);
        return scope;
    }

    /**
     * Отпускает собранное.
     * <p>
     * Java-объекты мост не держит: обёртки живут в скрипте и уходят вместе с ним.
     * Здесь освобождаются только классы запуска — чтобы движок, закрытый и забытый,
     * не держал через них ничего.
     */
    @Override
    public void close() {
        wrappers.clear();
    }

    /** Политика этого моста. */
    public JavaPolicy policy() {
        return policy;
    }

    /**
     * Готовый Java-объект как значение языка.
     * <p>
     * То, ради чего половина моста и написана: библиотека вернула соединение, момент
     * времени, результат запроса — и это надо отдать скрипту, не описывая руками.
     * Строки, числа и коллекции переводятся, остальное оборачивается.
     */
    public Value wrap(Object object) {
        return object == null ? NullValue.NULL : marshal.toValue(object, Span.NONE);
    }

    /**
     * Модуль {@code sys.java} — доступ к типам по имени: {@code java.type("java.time.LocalDate")}.
     * <p>
     * <b>Отдельно и по умолчанию нигде не зарегистрирован.</b> Регистрирует его
     * приложение — {@code NativeModules.of(Map.of("sys/java", bridge::lookupModule))} —
     * и только вместе с {@link JavaPolicy.Builder#allowLookup}, иначе каждый вызов
     * будет отказом. Причина в том, что доступ по имени нужен инструментам
     * (REPL, отладка, генератор), а обычному скрипту — почти никогда: ему тип
     * открывает приложение, зная зачем.
     * <p>
     * В стандартную библиотеку это не попало намеренно: {@code sys.io} и {@code sys.json}
     * есть у всех, а список доступных из скрипта Java-пакетов — решение конкретного
     * приложения, и его нельзя принять один раз за всех.
     */
    public Library lookupModule() {
        return new Library() {

            @Override
            public String name() {
                return "sys/java";
            }

            @Override
            public Environment installTo(Environment scope) {
                scope.define("type", BuiltinFunction.of("type", Arity.exactly(1),
                        (context, args, span) -> lookup(args.string(0, "имя класса"), span)));
                return scope;
            }
        };
    }

    /**
     * Тип по полному имени класса.
     * <p>
     * Три отказа, и все три разные по причине: пакет не разрешён политикой, тип
     * стоит в чёрном списке, класса нет вовсе. Одно сообщение на три случая
     * заставляло бы гадать, что чинить.
     */
    private ClassValue lookup(String className, Span span) {
        if (!policy.lookupAllowed(className)) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "тип '" + className
                    + "' скрипту не доступен: приложение не разрешило искать типы"
                    + (policy.lookupPackages().isEmpty() ? " ни в одном пакете"
                    : " вне пакетов " + String.join(", ", policy.lookupPackages())));
        }
        Class<?> found;
        try {
            found = Class.forName(className, false, loader());
        } catch (ClassNotFoundException | LinkageError absent) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span,
                    "класса '" + className + "' нет в этом приложении");
        }
        if (JavaPolicy.forbidden(found)) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "тип '" + className
                    + "' не открывается мостом ни при какой политике");
        }
        JavaClass known = wrappers.get(found);
        return known != null ? known : wrapperFor(JavaShape.publicView(found));
    }

    /**
     * Загрузчик приложения, а не потока: контекстный загрузчик в сервере приложений
     * принадлежит чужому запросу, и брать типы оттуда — не то, о чём просили.
     */
    private static ClassLoader loader() {
        ClassLoader own = JavaBridge.class.getClassLoader();
        return own != null ? own : ClassLoader.getSystemClassLoader();
    }

    /** Класс языка для этого Java-типа или {@code null}, если тип не открыт. */
    public JavaClass classOf(Class<?> type) {
        return exposed.get(type);
    }

    /** Имена, которые мост кладёт в область видимости. */
    public Map<String, Value> names() {
        return Map.copyOf(names);
    }

    Marshal marshal() {
        return marshal;
    }

    /**
     * Можно ли показать этот объект скрипту.
     * <p>
     * Спрашивается попыткой, а не разбором случаев: правила перевода живут
     * в {@link Marshal}, и второй их список здесь разошёлся бы с первым.
     */
    boolean canRepresent(Object object) {
        try {
            marshal.toValue(object, Span.NONE);
            return true;
        } catch (WdlRuntimeError refused) {
            return false;
        }
    }

    /**
     * Обёртка вокруг объекта, которому прямого перевода нет.
     *
     * @return {@code null}, если оборачивать нельзя — тогда {@link Marshal} скажет
     *         об этом сам, с именем класса и подсказкой
     */
    private Value wrapObject(Object object, Span span) {
        JavaClass known = classFor(object.getClass());
        return known == null ? null : new JavaInstance(known, object);
    }

    /**
     * Класс языка для объектов этого Java-класса или {@code null}, если такого нет
     * и заводить его политика не разрешает.
     * <p>
     * Три шага, и каждый следующий дороже предыдущего: таблица, поиск ближайшего
     * открытого типа, сборка нового класса. Результат любого шага попадает в таблицу,
     * поэтому дорогие шаги делаются один раз на класс, а не один раз на объект.
     */
    private JavaClass classFor(Class<?> actual) {
        JavaClass known = wrappers.get(actual);
        if (known != null) {
            return known;
        }
        if (JavaPolicy.forbidden(actual)) {
            return null;
        }
        known = closest(actual);
        if (known == null) {
            if (!policy.wrapUnknown()) {
                return null;
            }
            Class<?> view = JavaShape.publicView(actual);
            if (JavaPolicy.forbidden(view)) {
                return null;
            }
            known = wrapperFor(view);
        }
        wrappers.putIfAbsent(actual, known);
        return known;
    }

    /**
     * Класс для типа, которого никто не открывал.
     * <p>
     * Через {@code get} и {@code putIfAbsent}, а не {@code computeIfAbsent}: сборка
     * класса читает его константы, а чтение константы может потребовать обёртки —
     * то есть снова этой же таблицы. {@code computeIfAbsent} на такой вложенности
     * падает с «Recursive update», и это не теоретический случай, а первый же
     * класс с константой своего типа.
     */
    private JavaClass wrapperFor(Class<?> view) {
        JavaClass known = wrappers.get(view);
        if (known != null) {
            return known;
        }
        JavaClass built = new JavaClass(JavaSchema.all(view), this);
        JavaClass raced = wrappers.putIfAbsent(view, built);
        JavaClass result = raced != null ? raced : built;
        result.initStatics();
        return result;
    }

    /**
     * Самый близкий из открытых типов, которому объект принадлежит.
     * <p>
     * «Самый близкий» — тот, чей тип наследник остальных подошедших: открыты
     * и {@code Object}, и {@code Connection} — берётся второй, потому что его схема
     * написана про это, а не про всё сразу.
     */
    private JavaClass closest(Class<?> actual) {
        JavaClass best = null;
        for (Map.Entry<Class<?>, JavaClass> candidate : exposed.entrySet()) {
            if (!candidate.getKey().isAssignableFrom(actual)) {
                continue;
            }
            if (best == null || best.javaType().isAssignableFrom(candidate.getKey())) {
                best = candidate.getValue();
            }
        }
        return best;
    }

    public static final class Builder {

        private final List<JavaSchema> schemas = new java.util.ArrayList<>();
        private final Map<String, Class<?>> traits = new LinkedHashMap<>();
        private JavaPolicy policy = JavaPolicy.strict();

        private Builder() {
        }

        /** Открыть тип целиком: все его публичные члены под простым именем класса. */
        public Builder expose(Class<?> type) {
            return expose(JavaSchema.all(type));
        }

        /** То же, но под своим именем: {@code expose(LocalDate.class, "Date")}. */
        public Builder expose(Class<?> type, String scriptName) {
            return expose(JavaSchema.allOf(type).as(scriptName).build());
        }

        /** Открыть тип по схеме: видно ровно то, что она перечисляет. */
        public Builder expose(JavaSchema schema) {
            schemas.add(Objects.requireNonNull(schema, "schema"));
            return this;
        }

        /**
         * Открыть интерфейс как трейт — только для {@code is}, без членов.
         * <p>
         * Нужно там, где интерфейс это вопрос, а не набор возможностей:
         * {@code if (x is Closeable)}. Когда нужны и методы, открывается
         * обычным {@link #expose(Class)} — создать интерфейс всё равно нельзя.
         */
        public Builder exposeTrait(Class<?> type, String scriptName) {
            if (!type.isInterface()) {
                throw new IllegalArgumentException("трейтом открывается интерфейс, а "
                        + type.getName() + " им не является");
            }
            traits.put(scriptName, type);
            return this;
        }

        public Builder policy(JavaPolicy chosen) {
            this.policy = Objects.requireNonNull(chosen, "policy");
            return this;
        }

        public JavaBridge build() {
            return new JavaBridge(this);
        }
    }
}
