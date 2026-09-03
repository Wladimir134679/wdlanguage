package ru.wds.wdl.bridge;

import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.PropertyRequirement;
import ru.wds.wdl.value.Requirement;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Класс, встроенный приложением: заголовок из полей и методы на Java.
 * <p>
 * Для скрипта он неотличим от класса, написанного на wdl: {@code new File("a.txt")},
 * {@code f.read()}, {@code f.path}, {@code f is File}, {@code println(f)} — всё
 * работает одинаково, потому что интерпретатор видит только {@link ClassValue}.
 * <p>
 * Описывается построителем, а не аннотациями с рефлексией: имена и число аргументов
 * тогда видны в одном месте, опечатку ловит компилятор, а не запуск, и ничего
 * не нужно открывать через {@code module-info}.
 *
 * <h2>Как это выглядит</h2>
 * <pre>{@code
 * NativeClass file = NativeClass.named("File")
 *         .field("path")
 *         .method("read", Arity.exactly(0), (self, ctx, args, span) -> read(self, span))
 *         .method("write", Arity.exactly(1), (self, ctx, args, span) -> write(self, args, span))
 *         .factory("temp", Arity.exactly(0), (type, ctx, args, span) -> ...)
 *         .build();
 *
 * scope.define(file.name(), file);
 * }</pre>
 *
 * <h2>Класс собирается на запуск</h2>
 * Построитель зовут из {@link Library#installTo}, а не из статического поля. Причина
 * та же, по которой в ядре нет изменяемой статики: поля самого класса ({@link #statics()})
 * изменяемы, и скрипт вправе в них писать — значит, класс, общий для всех запусков,
 * переносил бы состояние одного скрипта в другой. Сборка стоит недорого, а фабрика
 * получает свой класс аргументом ({@link NativeFactory}), поэтому ссылаться
 * на статическое поле незачем.
 *
 * <h2>Правила те же, что у классов языка</h2>
 * Заголовок — список полей: сколько их, столько и аргументов у {@code new}, а поле
 * со значением по умолчанию делает аргумент необязательным. Обязательное поле
 * не может идти после необязательного — по той же причине, что и в языке:
 * пропуск в середине нечем записать. Поля пишутся в экземпляр по порядку, и всё,
 * что выразимо значением, лежит именно полем; для остального есть
 * {@link NativeInstance#state()}.
 *
 * <h2>Трейты</h2>
 * Класс может подмешать трейт — {@code .with(closeable)}, — и тогда {@code is}
 * отвечает {@code true} и ему. Требования трейта проверяются <b>при сборке класса</b>,
 * то есть при старте приложения: забыли метод или ошиблись в числе его аргументов —
 * исключение из построителя, а не непонятная ошибка у автора скрипта через месяц.
 * Это то же обещание, то же правило («поле закрывает поле, метод — метод») и тот же
 * момент, что у класса на wdl, где требования проверяет {@code Linker} на строке
 * {@code class}.
 * <p>
 * Поля со значением {@link NativeTrait нативный трейт} классу приносит — они пишутся
 * в экземпляр до своих. А вот <b>методов с телом не приносит ни один трейт</b>: метод
 * в плоской таблице держит кусок дерева, а у Java-метода дерева нет. По той же причине
 * трейт, написанный на wdl (скажем, {@code Closeable} из прелюдии), остаётся здесь
 * только контрактом: его поля — выражения, а вычислять их тут нечем.
 *
 * <h2>Наследование</h2>
 * Внутри Java-модуля иерархия строится обычным {@link Builder#extending}: заголовок
 * родителя продолжается своими полями, таблица методов склеивается плоско,
 * {@code init} выполняются от дальнего предка к потомку, {@code is} отвечает
 * {@code true} предку и его трейтам.
 * <p>
 * <b>Обратного пока нет:</b> класс на wdl не может наследоваться от нативного —
 * плоскую таблицу по Java-методам не собрать, и {@code class My : net.Server}
 * даёт внятный отказ. Композиция через {@link NativeTrait} закрывает этот случай
 * лучше: {@code class My with net.Handler} связывает слабее и читается яснее.
 */
public final class NativeClass implements ClassValue {

    private final String name;
    private final NativeClass parent;
    /** Заголовок целиком: поля родителя, затем свои. */
    private final List<Field> fields;
    /** Конструкторы от дальнего предка к этому классу — в порядке выполнения. */
    private final List<NativeMethod> inits;
    /** Плоская таблица: методы родителя, затем свои — побеждает последний. */
    private final Map<String, Entry> methods;
    /** Свойства: родительские, затем свои — тем же правилом, что методы. */
    private final Map<String, NativeProperty> properties;
    private final List<TraitValue> traits;
    /** Поля, пришедшие от трейтов готовыми значениями: пишутся до своих. */
    private final Map<String, Value> traitFields;
    private final MapValue statics = new MapValue();
    private final Arity arity;
    private final Signature signature;
    /** Тип Java-объекта в {@link NativeInstance#state()} или {@code null}. */
    private final Class<?> backing;
    /** Обёртка над чужим объектом, а не свой класс: см. {@link Builder#wrapper}. */
    private final boolean wrapper;

    private NativeClass(Builder builder, List<Field> allFields, Map<String, Entry> allMethods,
                        Map<String, NativeProperty> allProperties) {
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(allProperties));
        this.name = builder.name;
        this.parent = builder.parent;
        this.fields = List.copyOf(allFields);
        this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(allMethods));
        this.traits = List.copyOf(builder.allTraits());
        this.backing = builder.backing;
        this.wrapper = builder.wrapper;
        this.arity = builder.header != null ? builder.header.arity() : arityOf(fields);
        this.signature = builder.header != null ? builder.header : signatureOf(fields);
        builder.statics.forEach(statics::put);

        List<NativeMethod> chain = new ArrayList<>();
        if (parent != null) {
            chain.addAll(parent.inits);
        }
        if (builder.init != null) {
            chain.add(builder.init);
        }
        this.inits = List.copyOf(chain);

        // Поля, объявленные трейтом со значением, достаются классу — как и классу
        // на wdl. Порядок тот же: трейты слева направо, свои поля последними
        // и потому побеждают.
        Map<String, Value> donated = new LinkedHashMap<>();
        for (TraitValue trait : this.traits) {
            if (trait instanceof NativeTrait declared) {
                donated.putAll(declared.declaredFields());
            }
        }
        this.traitFields = Collections.unmodifiableMap(donated);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    private static Arity arityOf(List<Field> fields) {
        int required = 0;
        while (required < fields.size() && fields.get(required).defaultValue == null) {
            required++;
        }
        return Arity.between(required, fields.size());
    }

    /**
     * Контракт создания собирается из объявленных полей — имена у встроенного класса
     * уже есть, и {@code new Window(title: "Счёт")} достаётся ему даром.
     * <p>
     * Значения по умолчанию тут <b>готовые</b>, а не выражения: их подставит связыватель,
     * и {@code instantiate} получит привычный плотный список. Поэтому встроенному классу
     * не нужно ничего знать про именованные аргументы.
     */
    private static Signature signatureOf(List<Field> fields) {
        List<Signature.Param> params = new ArrayList<>(fields.size());
        for (Field field : fields) {
            params.add(field.defaultValue == null
                    ? Signature.Param.required(field.name)
                    : Signature.Param.optional(field.name, field.defaultValue));
        }
        return Signature.of(params);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Arity arity() {
        return arity;
    }

    @Override
    public Signature signature() {
        return signature;
    }

    @Override
    public MapValue statics() {
        return statics;
    }

    /**
     * Создаёт экземпляр: заполняет поля по порядку и передаёт готовый объект
     * в {@code init}, если тот объявлен.
     * <p>
     * Порядок тот же, что у классов языка: к моменту, когда библиотека получает
     * управление, поля уже записаны. Поэтому {@code init} может проверить их,
     * посчитать производное и завести Java-состояние. С родителем порядок
     * тоже языковой — конструкторы выполняются от дальнего предка к этому классу,
     * и каждый видит объект, собранный целиком.
     */
    @Override
    public Value instantiate(List<Value> arguments, CallContext context, Span span) {
        NativeInstance instance = new NativeInstance(this);
        traitFields.forEach(instance::put);
        List<Value> given = withDefaults(arguments);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (!field.stored) {
                // Параметр без поля: значение уедет в Java-объект, а обратно его
                // отдаст свойство. Записать его сюда значило бы завести вторую
                // копию, которая устареет на первом же изменении.
                continue;
            }
            instance.put(field.name, given.get(i));
        }
        if (!inits.isEmpty()) {
            Args args = Args.of("new " + name, given, context, span);
            for (NativeMethod body : inits) {
                body.call(instance, context, args, span);
            }
        }
        return instance;
    }

    /**
     * Дополняет короткий список аргументов значениями по умолчанию из заголовка.
     * <p>
     * Нужно потому, что {@code Binder} раскладывает аргументы <b>только</b> когда
     * в вызове есть именованные или {@code *}: обычное {@code new Window()} доходит
     * сюда пустым списком. Без этого шага дефолт объявленного параметра применялся бы
     * при {@code new Window(title: "…")} и не применялся при {@code new Window()} —
     * то есть заголовок обещал одно, а класс делал другое, и каждый {@code init}
     * дописывал обещание заново своими руками.
     * <p>
     * Дефолт параметра объявляется <b>один раз</b> — здесь, в заголовке. Явно
     * переданный {@code null} остаётся {@code null}: недостающим считается только
     * то, чего в списке нет.
     */
    private List<Value> withDefaults(List<Value> arguments) {
        if (arguments.size() >= fields.size()) {
            return arguments;
        }
        List<Value> filled = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Value value = i < arguments.size() ? arguments.get(i) : fields.get(i).defaultValue;
            filled.add(value == null ? NullValue.NULL : value);
        }
        return filled;
    }

    /**
     * Экземпляр вокруг готового Java-объекта — без вызова конструктора.
     * <p>
     * Второй вход в класс, и нужен он ровно там, где объекта ещё нет у скрипта,
     * но он уже есть у приложения: метод вернул соединение, библиотека отдала момент
     * времени. Звать {@code instantiate} тут нечего — создавать нечего, объект готов.
     * <p>
     * Только для {@linkplain Builder#wrapper обёрток}: у своего класса за созданием
     * стоит {@code init}, и обойти его значило бы получить экземпляр без состояния,
     * которое этот {@code init} и заводит.
     */
    public NativeInstance wrapping(Object object) {
        if (!wrapper) {
            throw new IllegalStateException("класс '" + name + "' не обёртка: "
                    + "экземпляр создаётся конструктором, а не вокруг готового объекта");
        }
        NativeInstance instance = new NativeInstance(this);
        instance.state(Objects.requireNonNull(object, "object"));
        return instance;
    }

    @Override
    public Property property(String name) {
        return properties.get(name);
    }

    /**
     * Интроспекция: родитель, трейты и имена членов — то же, что отдаёт класс на wdl.
     * <p>
     * Таблицы у нативного класса уже плоские (родительские члены слиты при сборке),
     * поэтому здесь ключи, а не обход иерархии.
     */
    @Override
    public ClassValue parentClass() {
        return parent;
    }

    @Override
    public List<String> methodNames() {
        return List.copyOf(methods.keySet());
    }

    @Override
    public List<String> fieldNames() {
        // Только хранимые: параметр без поля (см. Field#stored) в объекте не лежит,
        // и распаковка по позициям взяла бы у него пустоту вместо значения.
        List<String> names = new ArrayList<>(fields.size());
        for (Field field : fields) {
            if (field.stored) {
                names.add(field.name);
            }
        }
        return List.copyOf(names);
    }

    @Override
    public List<String> propertyNames() {
        return List.copyOf(properties.keySet());
    }

    @Override
    public FunctionValue method(InstanceObjectValue instance, String name) {
        Entry entry = methods.get(name);
        if (entry == null) {
            return null;
        }
        // Попасть в отказ можно только собрав экземпляр в обход instantiate: метод
        // ищется в классе объекта, а этот класс создаёт только NativeInstance.
        return new Bound(entry, NativeInstance.receiverOf(instance, this.name), this.name);
    }

    /** Родитель или {@code null}. Родитель ровно один — из-за заголовка. */
    public NativeClass parent() {
        return parent;
    }

    /**
     * Тип Java-объекта, который экземпляры держат в {@link NativeInstance#state()},
     * или {@code null}, если класс о нём не объявлял. См. {@link Builder#backing}.
     */
    public Class<?> backing() {
        return backing;
    }

    /** Обёртка ли это над чужим объектом: см. {@link Builder#wrapper}. */
    public boolean isWrapper() {
        return wrapper;
    }

    /**
     * Писать в поля класса можно всем, кроме обёрток.
     * <p>
     * У обёртки за статикой стоит статика Java — общая на процесс. Запись из скрипта
     * меняла бы состояние всего приложения, и делала бы это молча, под видом обычного
     * присваивания.
     */
    @Override
    public boolean staticsWritable() {
        return !wrapper;
    }

    /**
     * Сам класс, его предки и подмешанные трейты — свои и доставшиеся от предков.
     * <p>
     * Сравнение по ссылке, а не по имени: класс и трейт собираются на запуск,
     * и собранное для одного запуска честно не совпадает с собранным для другого.
     */
    @Override
    public boolean conformsTo(Value classOrTrait) {
        for (NativeClass ancestor = this; ancestor != null; ancestor = ancestor.parent) {
            if (classOrTrait == ancestor) {
                return true;
            }
        }
        // Два класса над Java-типами сравниваются по живой иерархии, а не по тому,
        // что собрал мост: isAssignableFrom знает про предков и интерфейсы всё,
        // а мост — только про показанное ему. Поэтому 'conn is Closeable' верно
        // и тогда, когда Connection никто не открывал.
        if (backing != null && classOrTrait instanceof NativeClass other
                && other.backing != null && other.backing.isAssignableFrom(backing)) {
            return true;
        }
        // Трейты здесь уже собраны по всей цепочке — см. Builder.allTraits().
        return traits.contains(classOrTrait);
    }

    @Override
    public String display() {
        return "class " + name + fields.stream()
                .map(field -> field.name)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    @Override
    public String toString() {
        return display();
    }

    /**
     * Строка заголовка: имя, значение по умолчанию и то, попадает ли значение
     * в экземпляр.
     *
     * @param stored {@code false} у параметра, которому в объекте соответствует
     *               не поле, а свойство: см. {@link Builder#param}
     */
    private record Field(String name, Value defaultValue, boolean stored) {
    }

    /**
     * Свойство нативного класса: пара Java-лямбд под одним именем.
     * <p>
     * Для интерпретатора это тот же {@link Property}, что свойство на wdl, — как
     * и весь остальной нативный класс есть тот же {@link ClassValue}. Приведение
     * к {@link NativeInstance} безопасно по той же причине, что у метода: свойство
     * ищется в классе объекта, а этот класс создаёт только {@link #instantiate}.
     */
    private record NativeProperty(String name, NativeGetter getter, NativeSetter setter,
                                  String className) implements Property {

        @Override
        public boolean readable() {
            return true;
        }

        @Override
        public boolean writable() {
            return setter != null;
        }

        @Override
        public Value read(Value receiver, CallContext context, Span span) {
            return getter.get(self(receiver), context, span);
        }

        @Override
        public void write(Value receiver, Value value, CallContext context, Span span) {
            setter.set(self(receiver), value, context, span);
        }

        private NativeInstance self(Value receiver) {
            return NativeInstance.receiverOf(receiver, className);
        }
    }

    private record Entry(String name, Signature signature, NativeMethod body) {

        Arity arity() {
            return signature.arity();
        }
    }

    private record FactoryEntry(String name, Signature signature, NativeFactory body) {

        Arity arity() {
            return signature.arity();
        }
    }

    /** Метод, связанный с экземпляром: обычное значение-функция, как и у классов языка. */
    private record Bound(Entry entry, NativeInstance self, String className) implements FunctionValue {

        @Override
        public String name() {
            return className + "." + entry.name();
        }

        @Override
        public Arity arity() {
            return entry.arity();
        }

        @Override
        public Signature signature() {
            return entry.signature();
        }

        @Override
        public Value call(CallContext context, List<Value> arguments, Span span) {
            return entry.body().call(self, context, Args.of(name(), arguments, context, span), span);
        }

        @Override
        public String display() {
            return "def " + name();
        }
    }

    /**
     * Построитель. Порядок вызовов свободен, кроме одного: поля объявляются в том
     * порядке, в каком их принимает {@code new}.
     */
    public static final class Builder {

        private final String name;
        private final List<Field> fields = new ArrayList<>();
        private final Map<String, Entry> methods = new LinkedHashMap<>();
        private final Map<String, NativeProperty> properties = new LinkedHashMap<>();
        private final Map<String, Value> statics = new LinkedHashMap<>();
        private final Map<String, FactoryEntry> factories = new LinkedHashMap<>();
        private final List<TraitValue> traits = new ArrayList<>();
        private NativeMethod init;
        private NativeClass parent;
        private Class<?> backing;
        private Signature header;
        private boolean wrapper;

        private Builder(String name) {
            this.name = requireName(name, "имя класса");
        }

        /**
         * Родитель — класс, собранный этим же построителем.
         * <p>
         * Внутри Java-модуля иерархия строится без участия языка: заголовок родителя
         * продолжается своими полями, таблица методов склеивается плоско (свои
         * побеждают), {@code init} выполняются от дальнего предка к потомку,
         * а {@code is} начинает отвечать {@code true} предку и его трейтам.
         * <p>
         * Поля самого класса ({@code statics}) не наследуются — как и у классов wdl:
         * {@code Base.of} на потомке не появляется, потому что фабрика знает,
         * что создаёт.
         * <p>
         * Наследовать <b>от</b> нативного класса скрипт по-прежнему не может:
         * плоскую таблицу по Java-методам не собрать, и {@code class My : net.Server}
         * даёт внятный отказ.
         */
        public Builder extending(NativeClass superclass) {
            Objects.requireNonNull(superclass, "superclass");
            if (parent != null) {
                throw new IllegalArgumentException("у класса '" + name + "' уже есть родитель '"
                        + parent.name() + "'");
            }
            this.parent = superclass;
            return this;
        }

        /**
         * Тип Java-объекта, который экземпляры держат в {@link NativeInstance#state()}.
         * <p>
         * Объявляется затем, что состояние — единственное место класса, о содержимом
         * которого построитель раньше не знал ничего. Одно слово закрывает три дыры сразу:
         * <ul>
         *   <li>{@link #members(MemberSource) источник членов} сверяет свой тип с этим
         *       <b>при сборке класса</b>. Раньше {@code FromJava.of(JTextArea.class)}
         *       у класса, кладущего в состояние {@code JScrollPane}, собирался молча
         *       и падал у автора скрипта при первом обращении;</li>
         *   <li>{@link NativeInstance#state(Class)} перестаёт быть слепым приведением;</li>
         *   <li>{@code is} начинает отвечать по живой иерархии Java — см.
         *       {@link NativeClass#conformsTo}.</li>
         * </ul>
         */
        public Builder backing(Class<?> stateType) {
            this.backing = Objects.requireNonNull(stateType, "stateType");
            return this;
        }

        /**
         * Класс — обёртка над чужим объектом, а не свой класс.
         * <p>
         * Одно слово с тремя согласованными следствиями, и все три идут из одной мысли:
         * у обёртки нет ничего своего, всё её содержимое — это чужой объект.
         * <ul>
         *   <li>полей нет: {@code x.name} у обёртки — свойство над Java-полем,
         *       а не записанное значение, иначе снимок устареет;</li>
         *   <li>печать — {@code toString()} объекта: перечислять нечего, а
         *       {@code println(date)} обязан показать {@code 2026-08-29};</li>
         *   <li>в поля класса писать нельзя: за ними стоит статика Java, общая
         *       на процесс — см. {@link NativeClass#staticsWritable()}.</li>
         * </ul>
         * Ставит {@link #backing(Class)} заодно: обёртка без объекта бессмысленна.
         */
        public Builder wrapper(Class<?> stateType) {
            this.wrapper = true;
            return backing(stateType);
        }

        /**
         * Заголовок целиком, вместо {@link #field} и {@link #param}.
         * <p>
         * Нужен там, где заголовок не выводится из списка имён: у Java-типа
         * конструкторов бывает несколько, и одна общая подпись — это диапазон
         * {@code Arity}, а не перечень полей. Взаимоисключающ с {@code field}
         * и {@code param}: два источника заголовка означали бы, что один из них врёт.
         */
        public Builder header(Signature headerSignature) {
            this.header = Objects.requireNonNull(headerSignature, "signature");
            return this;
        }

        /** Обязательное поле: аргумент {@code new} без значения по умолчанию. */
        public Builder field(String fieldName) {
            return field(fieldName, null);
        }

        /**
         * Поле со значением по умолчанию — аргумент становится необязательным.
         *
         * @param defaultValue значение или {@code null} для обязательного поля
         */
        public Builder field(String fieldName, Value defaultValue) {
            requireName(fieldName, "имя поля");
            if (fields.stream().anyMatch(field -> field.name.equals(fieldName))) {
                throw new IllegalArgumentException("поле '" + fieldName + "' класса '"
                        + name + "' уже объявлено");
            }
            if (defaultValue == null && !fields.isEmpty()
                    && fields.get(fields.size() - 1).defaultValue != null) {
                // То же правило, что в языке: позиционное создание читается по префиксу
                // списка полей. Именованные аргументы его не отменяют — они дают
                // пропуск записать, но не делают 'new Point(1)' понятнее.
                throw new IllegalArgumentException("поле '" + fieldName + "' класса '" + name
                        + "' без значения по умолчанию не может идти после поля со значением");
            }
            fields.add(new Field(fieldName, defaultValue, true));
            return this;
        }

        /**
         * Параметр создания, который <b>не становится полем</b>: {@code new Button("+1")}.
         * <p>
         * Нужен там, где значение по-настоящему живёт в Java-объекте, а не в экземпляре:
         * текст кнопки хранит {@code JButton}, и его вправе поменять кто угодно —
         * пользователь, чужой код, сам Swing. Полем такое держать нельзя: поле,
         * записанное при создании, к следующему обращению устареет, а запись в него
         * ({@code b.text = "..."}) молча не дойдёт до кнопки.
         * <p>
         * Поэтому пара такая: {@code param} объявляет аргумент конструктора,
         * а значение за тем же именем отдаёт <b>свойство</b> — своё или взятое
         * у чужого типа ({@code FromJava.bean("text")}). В заголовке параметр
         * ведёт себя как поле: считается в {@code Arity}, попадает в {@code Signature},
         * подчиняется правилу «обязательный не после необязательного».
         * <p>
         * Само значение приходит в {@code init} обычным аргументом
         * ({@code args.string(0, "текст", "")}) — оттуда его и берут.
         */
        public Builder param(String paramName, Value defaultValue) {
            requireName(paramName, "имя параметра");
            if (fields.stream().anyMatch(field -> field.name.equals(paramName))) {
                throw new IllegalArgumentException("поле '" + paramName + "' класса '"
                        + name + "' уже объявлено");
            }
            if (defaultValue == null && !fields.isEmpty()
                    && fields.get(fields.size() - 1).defaultValue != null) {
                throw new IllegalArgumentException("параметр '" + paramName + "' класса '" + name
                        + "' без значения по умолчанию не может идти после поля со значением");
            }
            fields.add(new Field(paramName, defaultValue, false));
            return this;
        }

        /**
         * Что сделать после заполнения полей: проверить их, посчитать производное,
         * завести {@link NativeInstance#state() состояние}. Необязателен.
         */
        public Builder init(NativeMethod body) {
            this.init = Objects.requireNonNull(body, "body");
            return this;
        }

        /**
         * То же, но контракт уже собран: {@link Signature} со стороны, разделяемая
         * с чем-то ещё. Когда параметры объявляются здесь же, короче
         * {@link #init(Params, NativeMethod)}.
         * <p>
         * Это в точности {@link #param} по параметру плюс {@link #init(NativeMethod)},
         * и заводится ради чтения: у обёртки над чужим типом полей нет вовсе,
         * и столбик {@code param} перед телом читается как отдельное объявление,
         * хотя описывает <b>тот же</b> конструктор — {@code args.at(3)} в теле
         * относится к четвёртой строке столбика.
         * <p>
         * Параметр объявляется {@link Signature.Param#required обязательным} или
         * {@link Signature.Param#optional(String, Value) с готовым значением}: пропуск
         * заполняет заголовок, и подставить его больше некому — выражения по умолчанию
         * ({@code lazy}) считает вызываемый, а у встроенного класса дерева нет.
         *
         * @throws IllegalArgumentException если параметр объявлен без имени
         *                                  ({@code Signature.positional}) или его пропуск
         *                                  нечем заполнить
         */
        /**
         * Конструктор целиком: параметры цепочкой и тело рядом.
         * <p>
         * Основной вид объявления заголовка у обёрток над чужим типом, где полей нет
         * вовсе: {@link Params} перечисляет параметры тем же словарём, каким
         * {@link MemberSource} перечисляет члены, а тело стоит следом — как у
         * {@link #method(String, Signature, NativeMethod) метода}.
         *
         * <pre>{@code
         * .init(Params.of()
         *         .optional("text", "")
         *         .optional("enabled", true),
         *         (self, context, args, span) -> { ... })
         * }</pre>
         */
        public Builder init(Params constructor, NativeMethod body) {
            Objects.requireNonNull(constructor, "params");
            return init(constructor.signature(), body);
        }

        public Builder init(Signature constructor, NativeMethod body) {
            Objects.requireNonNull(constructor, "signature");
            if (!constructor.namesKnown()) {
                throw new IllegalArgumentException("конструктор класса '" + name
                        + "': параметры без имён — заголовок объявляет их поимённо");
            }
            if (constructor.hasRest() || constructor.hasNamedRest()) {
                throw new IllegalArgumentException("конструктор класса '" + name
                        + "': остаток в заголовке не поддерживается");
            }
            for (Signature.Param declared : constructor.params()) {
                if (!declared.isRequired() && declared.constant() == null) {
                    throw new IllegalArgumentException("параметр '" + declared.name()
                            + "' класса '" + name + "': пропуск нечем заполнить — объявите его"
                            + " обязательным или со значением по умолчанию");
                }
                param(declared.name(), declared.constant());
            }
            return init(body);
        }

        /** Метод экземпляра, который зовут только по позиции. */
        public Builder method(String methodName, Arity methodArity, NativeMethod body) {
            return method(methodName, Signature.positional(
                    Objects.requireNonNull(methodArity, "arity")), body);
        }

        /**
         * Метод экземпляра с объявленными именами параметров:
         * {@code window.size(width: 400, height: 300)}.
         */
        public Builder method(String methodName, Signature methodSignature, NativeMethod body) {
            requireName(methodName, "имя метода");
            Objects.requireNonNull(methodSignature, "signature");
            Objects.requireNonNull(body, "body");
            if (methods.put(methodName, new Entry(methodName, methodSignature, body)) != null) {
                throw new IllegalArgumentException("метод '" + methodName + "' класса '"
                        + name + "' уже объявлен");
            }
            return this;
        }

        /**
         * Фабрика — функция на самом классе: {@code File.temp()}.
         * <p>
         * Не отдельный вид члена, а место записи: она ложится в {@link #statics()}
         * обычным значением, и снаружи то же самое сделало бы присваивание. Собирается
         * она при {@link #build()}, потому что телу нужен готовый класс — см.
         * {@link NativeFactory}.
         */
        public Builder factory(String factoryName, Arity factoryArity, NativeFactory body) {
            return factory(factoryName, Signature.positional(
                    Objects.requireNonNull(factoryArity, "arity")), body);
        }

        /** Фабрика с объявленными именами параметров: {@code File.temp(prefix: "wdl")}. */
        public Builder factory(String factoryName, Signature factorySignature, NativeFactory body) {
            requireName(factoryName, "имя фабрики");
            Objects.requireNonNull(factorySignature, "signature");
            Objects.requireNonNull(body, "body");
            checkStaticFree(factoryName);
            factories.put(factoryName, new FactoryEntry(factoryName, factorySignature, body));
            return this;
        }

        /**
         * Свойство только для чтения: {@code window.width}.
         * <p>
         * Читается тем же обращением, что поле, но за именем стоит вызов — поэтому
         * значение всегда свежее. Перебором и {@code len} свойство не видно: среди
         * пар экземпляра его нет. Когда стоит брать свойство, а когда поле, разобрано
         * в {@link NativeGetter}.
         */
        public Builder property(String propertyName, NativeGetter getter) {
            return property(propertyName, getter, null);
        }

        /** Имя класса, который собирается: источнику членов оно нужно для сообщений. */
        public String name() {
            return name;
        }

        /**
         * Объявленный тип состояния или {@code null}: источник членов сверяет с ним свой.
         * См. {@link #backing(Class)}.
         */
        public Class<?> declaredBacking() {
            return backing;
        }

        /**
         * Члены из чужого источника: {@code .members(FromJava.of(JButton.class).bean("text"))}.
         * <p>
         * Ровно то же, что перечислить их здесь руками, — источник и добавляет их
         * этим же построителем. Смысл в том, откуда берётся тело: не из лямбды,
         * а из чужого типа, у которого этот метод уже написан. См. {@link MemberSource}.
         */
        public Builder members(MemberSource source) {
            Objects.requireNonNull(source, "source");
            source.contributeTo(this);
            return this;
        }

        /** Свойство с чтением и записью: {@code window.title = "..."}. */
        public Builder property(String propertyName, NativeGetter getter, NativeSetter setter) {
            requireName(propertyName, "имя свойства");
            Objects.requireNonNull(getter, "getter");
            // Ячейку имени свойство делит с полем и с методом. У класса на wdl первое
            // разрешает плоская таблица, второе ловит Linker; здесь оба случая видно
            // сразу — заголовок и методы объявлены тем же построителем.
            if (fields.stream().anyMatch(field -> field.name.equals(propertyName) && field.stored)) {
                throw new IllegalStateException("имя '" + propertyName + "' класса '" + name
                        + "' уже занято полем: свойство и поле — одна ячейка");
            }
            if (methods.containsKey(propertyName)) {
                throw new IllegalStateException("имя '" + propertyName + "' класса '" + name
                        + "' уже занято методом: свойство даёт значение, метод — функцию");
            }
            properties.put(propertyName, new NativeProperty(propertyName, getter, setter, name));
            return this;
        }

        /** Поле самого класса: {@code File.SEPARATOR}. */
        public Builder constant(String constantName, Value value) {
            requireName(constantName, "имя поля класса");
            Objects.requireNonNull(value, "value");
            checkStaticFree(constantName);
            statics.put(constantName, value);
            return this;
        }

        /** Заголовок целиком: поля родителя, затем свои. */
        private List<Field> allFields() {
            if (parent == null) {
                return List.copyOf(fields);
            }
            List<Field> merged = new ArrayList<>(parent.fields);
            merged.addAll(fields);
            return merged;
        }

        /** Плоская таблица методов: родительские, затем свои — побеждает последний. */
        private Map<String, Entry> allMethods() {
            if (parent == null) {
                return new LinkedHashMap<>(methods);
            }
            Map<String, Entry> merged = new LinkedHashMap<>(parent.methods);
            merged.putAll(methods);
            return merged;
        }

        /** Плоская таблица свойств: родительские, затем свои — побеждает последний. */
        private Map<String, NativeProperty> allProperties() {
            if (parent == null) {
                return new LinkedHashMap<>(properties);
            }
            Map<String, NativeProperty> merged = new LinkedHashMap<>(parent.properties);
            merged.putAll(properties);
            return merged;
        }

        /** Трейты всей цепочки: родительские, затем свои. */
        private List<TraitValue> allTraits() {
            if (parent == null) {
                return List.copyOf(traits);
            }
            List<TraitValue> merged = new ArrayList<>(parent.traits);
            traits.stream().filter(trait -> !merged.contains(trait)).forEach(merged::add);
            return merged;
        }

        /**
         * Заголовок после склейки с родительским: имена не повторяются, обязательное
         * не идёт после необязательного.
         * <p>
         * Проверка отдельно от {@link #field}, потому что своё поле законно, а вместе
         * с родительским может и не быть: родитель с необязательным полем и потомок
         * с обязательным дают заголовок, который нечем заполнить.
         */
        private void checkHeader(List<Field> header) {
            Field optional = null;
            for (int i = 0; i < header.size(); i++) {
                Field field = header.get(i);
                for (int j = 0; j < i; j++) {
                    if (header.get(j).name.equals(field.name)) {
                        throw new IllegalStateException("поле '" + field.name + "' класса '" + name
                                + "' уже объявлено у родителя '" + parent.name() + "'");
                    }
                }
                if (field.defaultValue != null) {
                    optional = field;
                } else if (optional != null) {
                    throw new IllegalStateException("поле '" + field.name + "' класса '" + name
                            + "' без значения по умолчанию не может идти после поля '"
                            + optional.name + "' со значением");
                }
            }
        }

        /**
         * Проверяет обещания трейта — тем же правилом, что {@code Linker} у классов
         * на wdl: поле закрывает только поле, метод — только метод, и число аргументов
         * метода обязано покрывать требуемое.
         */
        private void checkRequirements(TraitValue trait, List<Field> allFields,
                                       Map<String, Entry> allMethods,
                                       Map<String, NativeProperty> allProperties) {
            for (String required : trait.requiredFields()) {
                if (allFields.stream().anyMatch(field -> field.name.equals(required))) {
                    continue;
                }
                // Полевое требование закрывает и свойство, умеющее читать и писать, —
                // тем же правилом, что у классов на wdl: трейт просил место, которое
                // читают и пишут, и получил именно его.
                NativeProperty property = allProperties.get(required);
                if (property != null && property.writable()) {
                    continue;
                }
                throw new IllegalStateException(unmet(trait) + "нет поля '" + required
                        + "'. Объявите его в заголовке класса или свойством с записью");
            }
            for (PropertyRequirement required : trait.requiredProperties()) {
                NativeProperty property = allProperties.get(required.name());
                boolean hasField = allFields.stream()
                        .anyMatch(field -> field.name.equals(required.name()));
                boolean canRead = property != null ? property.readable() : hasField;
                boolean canWrite = property != null ? property.writable() : hasField;
                if (!required.satisfiedBy(canRead, canWrite)) {
                    throw new IllegalStateException(unmet(trait) + "имя '" + required.name()
                            + "' не умеет " + required.missing(canRead, canWrite));
                }
            }
            for (Requirement required : trait.requiredMethods()) {
                Entry provided = allMethods.get(required.name());
                if (provided == null) {
                    throw new IllegalStateException(unmet(trait) + "нет метода '"
                            + required.name() + "'");
                }
                if (!required.satisfiedBy(provided.arity())) {
                    throw new IllegalStateException(unmet(trait) + "метод '" + required.name()
                            + "' должен принимать " + required.arity().describeArguments()
                            + ", а принимает " + provided.arity().describeArguments());
                }
            }
        }

        private String unmet(TraitValue trait) {
            return "класс '" + name + "' не выполняет требование трейта '" + trait.name() + "': ";
        }

        private void checkStaticFree(String memberName) {
            if (statics.containsKey(memberName) || factories.containsKey(memberName)) {
                throw new IllegalArgumentException("поле класса '" + memberName
                        + "' у '" + name + "' уже объявлено");
            }
        }

        /**
         * Подмешивает трейт: {@code is} начнёт отвечать ему {@code true}.
         * <p>
         * Трейт берётся значением, а не именем, и это важно: трейт прелюдии
         * ({@code Closeable}) принадлежит запуску, поэтому и класс, который его
         * обещает, собирается на запуск — в {@code installTo}, а не статическим полем.
         */
        public Builder with(TraitValue trait) {
            Objects.requireNonNull(trait, "trait");
            if (!traits.contains(trait)) {
                traits.add(trait);
            }
            return this;
        }

        /**
         * Собирает класс, проверив обещания трейтов.
         * <p>
         * Проверка здесь, а не при первом вызове из скрипта: класс собирают при старте
         * приложения, и «забыл close» должно падать там же, где написано {@code .with}.
         */
        public NativeClass build() {
            if (header != null && !fields.isEmpty()) {
                throw new IllegalStateException("класс '" + name + "': заголовок задан "
                        + "и через header(...), и полями — источник должен быть один");
            }
            List<Field> allFields = allFields();
            Map<String, Entry> allMethods = allMethods();
            Map<String, NativeProperty> allProperties = allProperties();
            checkHeader(allFields);
            // Требования проверяются по всему, что у класса есть, — вместе
            // с унаследованным: обещание трейта выполняет класс целиком.
            for (TraitValue trait : allTraits()) {
                checkRequirements(trait, allFields, allMethods, allProperties);
            }
            NativeClass built = new NativeClass(this, allFields, allMethods, allProperties);
            // Фабрики — последними: их телу нужен готовый класс, чтобы было чем
            // создавать экземпляр.
            factories.forEach((factoryName, factory) -> built.statics.put(factoryName,
                    BuiltinFunction.of(name + "." + factoryName, factory.signature(),
                            (context, arguments, span) ->
                                    factory.body().call(built, context, arguments, span))));
            return built;
        }

        private static String requireName(String value, String what) {
            Objects.requireNonNull(value, what);
            if (value.isBlank()) {
                throw new IllegalArgumentException(what + " не может быть пустым");
            }
            return value;
        }
    }

}
