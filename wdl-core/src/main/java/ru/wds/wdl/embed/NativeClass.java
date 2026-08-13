package ru.wds.wdl.embed;

import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.FunctionValue;
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
 *         .factory("temp", Arity.exactly(0), (ctx, args, span) -> ...)
 *         .build();
 *
 * scope.define(file.name(), file);
 * }</pre>
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
 * то есть при старте приложения: забыли метод — исключение из построителя, а не
 * непонятная ошибка у автора скрипта через месяц. Это то же обещание и в тот же
 * момент, что у класса на wdl, где требования проверяет {@code Linker} на строке
 * {@code class}.
 * <p>
 * Методов по умолчанию трейт нативному классу не приносит: метод в плоской таблице
 * держит кусок дерева, а у Java-метода дерева нет. Трейт здесь — контракт, и только.
 *
 * <h2>Чего у нативного класса пока нет</h2>
 * Наследования: {@code is} отвечает {@code true} самому классу и подмешанным трейтам,
 * но не предку. Когда понадобится, сюда добавится ссылка на родителя — таблица методов
 * здесь и так плоская, как у классов языка.
 */
public final class NativeClass implements ClassValue {

    private final String name;
    private final List<Field> fields;
    private final NativeMethod init;
    private final Map<String, Entry> methods;
    private final List<TraitValue> traits;
    private final MapValue statics = new MapValue();
    private final Arity arity;

    private NativeClass(Builder builder) {
        this.name = builder.name;
        this.fields = List.copyOf(builder.fields);
        this.init = builder.init;
        this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(builder.methods));
        this.traits = List.copyOf(builder.traits);
        this.arity = arityOf(fields);
        builder.statics.forEach(statics::put);
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

    @Override
    public String name() {
        return name;
    }

    @Override
    public Arity arity() {
        return arity;
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
     * посчитать производное и завести Java-состояние.
     */
    @Override
    public Value instantiate(List<Value> arguments, CallContext context, Span span) {
        NativeInstance instance = new NativeInstance(this);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            Value given = i < arguments.size() ? arguments.get(i) : field.defaultValue;
            instance.put(field.name, given == null ? NullValue.NULL : given);
        }
        if (init != null) {
            init.call(instance, context, arguments, span);
        }
        return instance;
    }

    @Override
    public FunctionValue method(InstanceObjectValue instance, String name) {
        Entry entry = methods.get(name);
        if (entry == null) {
            return null;
        }
        if (!(instance.identity() instanceof NativeInstance self)) {
            // Попасть сюда можно только собрав экземпляр в обход instantiate:
            // метод ищется в классе объекта, а этот класс создаёт только NativeInstance.
            throw new IllegalStateException("экземпляр класса '" + this.name
                    + "' создан в обход instantiate");
        }
        return new Bound(entry, self, this.name);
    }

    /**
     * Сам класс и подмешанные трейты. Наследования у нативных классов пока нет,
     * поэтому цепочки предков в ответе тоже нет.
     * <p>
     * Сравнение по ссылке, а не по имени: трейт прелюдии принадлежит запуску,
     * и класс, собранный для одного запуска, честно не совпадает с трейтом другого.
     */
    @Override
    public boolean conformsTo(Value classOrTrait) {
        return classOrTrait == this || traits.contains(classOrTrait);
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

    private record Entry(String name, Arity arity, NativeMethod body) {
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
        public Value call(CallContext context, List<Value> arguments, Span span) {
            return entry.body().call(self, context, arguments, span);
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
        private final Map<String, Value> statics = new LinkedHashMap<>();
        private final List<TraitValue> traits = new ArrayList<>();
        private NativeMethod init;

        private Builder(String name) {
            this.name = requireName(name, "имя класса");
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
                // То же правило, что в языке: пропуск в середине списка нечем записать,
                // пока нет именованных аргументов.
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

        /** Метод экземпляра. */
        public Builder method(String methodName, Arity methodArity, NativeMethod body) {
            requireName(methodName, "имя метода");
            Objects.requireNonNull(methodArity, "arity");
            Objects.requireNonNull(body, "body");
            if (methods.put(methodName, new Entry(methodName, methodArity, body)) != null) {
                throw new IllegalArgumentException("метод '" + methodName + "' класса '"
                        + name + "' уже объявлен");
            }
            return this;
        }

        /**
         * Фабрика — функция на самом классе: {@code File.temp()}.
         * <p>
         * Не отдельный вид члена, а место записи: она ложится в {@link #statics()}
         * обычным значением, и снаружи то же самое сделало бы присваивание.
         */
        public Builder factory(String factoryName, Arity factoryArity, BuiltinFunction.Body body) {
            requireName(factoryName, "имя фабрики");
            return constant(factoryName,
                    BuiltinFunction.of(name + "." + factoryName, factoryArity, body));
        }

        /** Поле самого класса: {@code File.SEPARATOR}. */
        public Builder constant(String constantName, Value value) {
            requireName(constantName, "имя поля класса");
            Objects.requireNonNull(value, "value");
            if (statics.put(constantName, value) != null) {
                throw new IllegalArgumentException("поле класса '" + constantName
                        + "' у '" + name + "' уже объявлено");
            }
            return this;
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
            for (TraitValue trait : traits) {
                for (String required : trait.requiredMethods()) {
                    if (!methods.containsKey(required)
                            && fields.stream().noneMatch(field -> field.name.equals(required))) {
                        throw new IllegalStateException("класс '" + name
                                + "' не выполняет требование трейта '" + trait.name()
                                + "': нет метода '" + required + "'");
                    }
                }
            }
            return new NativeClass(this);
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
