package ru.wds.wdl.embed;

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

    private NativeClass(Builder builder, List<Field> allFields, Map<String, Entry> allMethods,
                        Map<String, NativeProperty> allProperties) {
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(allProperties));
        this.name = builder.name;
        this.parent = builder.parent;
        this.fields = List.copyOf(allFields);
        this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(allMethods));
        this.traits = List.copyOf(builder.allTraits());
        this.arity = arityOf(fields);
        this.signature = signatureOf(fields);
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
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            Value given = i < arguments.size() ? arguments.get(i) : field.defaultValue;
            instance.put(field.name, given == null ? NullValue.NULL : given);
        }
        if (!inits.isEmpty()) {
            Args args = Args.of("new " + name, arguments, context, span);
            for (NativeMethod body : inits) {
                body.call(instance, context, args, span);
            }
        }
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

    private record Field(String name, Value defaultValue) {
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
            fields.add(new Field(fieldName, defaultValue == null ? null : defaultValue));
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

        /** Свойство с чтением и записью: {@code window.title = "..."}. */
        public Builder property(String propertyName, NativeGetter getter, NativeSetter setter) {
            requireName(propertyName, "имя свойства");
            Objects.requireNonNull(getter, "getter");
            // Ячейку имени свойство делит с полем и с методом. У класса на wdl первое
            // разрешает плоская таблица, второе ловит Linker; здесь оба случая видно
            // сразу — заголовок и методы объявлены тем же построителем.
            if (fields.stream().anyMatch(field -> field.name.equals(propertyName))) {
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
