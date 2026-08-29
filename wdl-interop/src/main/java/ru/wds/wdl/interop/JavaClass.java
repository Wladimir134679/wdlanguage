package ru.wds.wdl.interop;

import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.MapValue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Java-тип как класс языка.
 * <p>
 * Четвёртая реализация {@link ClassValue} — рядом с классом на wdl, классом
 * от приложения ({@code embed.NativeClass}) и дескриптором типа. Интерфейс
 * {@code non-sealed} именно для этого, и цена такого решения — ноль изменений
 * в ядре: интерпретатор видит класс, и ему всё равно, откуда у класса методы.
 *
 * <h2>Почему не {@code NativeClass}</h2>
 * Тот не подходит трижды, и каждый раз по делу: у него заголовок — это список
 * полей (конструктор Java так не выразить, а их бывает несколько), поле пишется
 * в экземпляр (Java-поле меняется за спиной скрипта), и метод у него один на имя
 * (у Java под одним именем живёт набор перегрузок).
 *
 * <h2>Собирается на запуск</h2>
 * В отличие от {@link JavaShape}, который кэшируется на процесс. Причина та же,
 * по которой {@code NativeClass} строят из {@code installTo}: поля самого класса
 * ({@link #statics()}) скрипт вправе менять, и общий на процесс класс переносил бы
 * {@code Date.mark = 1} из одного запуска в следующий.
 *
 * <h2>Иерархии он не строит</h2>
 * {@link #parentClass()} всегда {@code null}, и это не упрощение с потерей:
 * методы предков у Java и так плоские ({@code getMethods} отдаёт их все), а
 * {@code is} отвечает по живой иерархии через {@code isAssignableFrom} — то есть
 * верно и для тех предков, которых мосту никто не открывал. Собирать цепочку
 * {@code JavaClass} значило бы повторять в мосте то, что Java уже знает про себя.
 */
public final class JavaClass implements ClassValue {

    private final String name;
    private final JavaShape shape;
    private final JavaSchema schema;
    private final JavaBridge bridge;
    private final MapValue statics = new MapValue();
    private final Arity arity;
    private final Signature signature;
    private boolean staticsReady;

    JavaClass(JavaSchema schema, JavaBridge bridge) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.name = schema.name();
        this.shape = JavaShape.of(schema.type());
        List<JavaExecutable> constructors = constructors();
        this.arity = arityOf(constructors);
        this.signature = signatureOf(constructors, arity);
        validate();
    }

    /**
     * Проверяет схему по живому типу — при сборке моста, то есть при старте приложения.
     * <p>
     * Иначе опечатка в схеме доживает до автора скрипта и выглядит там пропажей:
     * {@code .method("getVlaue")} не открывает ничего, а обращение молча даёт
     * {@code null}, потому что промах по члену у объекта — не ошибка, а правило языка.
     * Ловить это должен тот, кто схему писал. Момент тот же, что у требований трейта
     * в {@code embed.NativeClass}, и по той же причине.
     */
    private void validate() {
        schema.declaredMethods().forEach(scriptName -> {
            if (shape.methods(schema.methodOf(scriptName)).isEmpty()) {
                throw refuse("метода '" + schema.methodOf(scriptName) + "'"
                        + (scriptName.equals(schema.methodOf(scriptName)) ? ""
                        : " (открыт как '" + scriptName + "')"));
            }
        });
        schema.declaredFields().forEach(fieldName -> {
            if (shape.field(fieldName) == null) {
                throw refuse("публичного поля '" + fieldName + "'");
            }
        });
        schema.declaredBeans().forEach((scriptName, javaProperty) -> {
            JavaShape.Bean bean = shape.bean(javaProperty);
            if (bean == null || bean.getter() == null) {
                throw refuse("пары аксессоров для свойства '" + javaProperty
                        + "': нужен get" + capitalize(javaProperty) + "() или is"
                        + capitalize(javaProperty) + "()");
            }
        });
        schema.factories().forEach((scriptName, javaName) -> {
            if (shape.staticMethods(javaName).isEmpty()) {
                throw refuse("статического метода '" + javaName + "' для фабрики '"
                        + scriptName + "'");
            }
        });
        schema.readOnlyNames().forEach(memberName -> {
            if (access(memberName) == null) {
                throw refuse("поля или свойства '" + memberName
                        + "', объявленного только для чтения");
            }
        });
    }

    private IllegalArgumentException refuse(String missing) {
        return new IllegalArgumentException("схема класса '" + name + "': у типа "
                + shape.type().getName() + " нет " + missing);
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private List<JavaExecutable> constructors() {
        return schema.constructorsAllowed() ? shape.constructors() : List.of();
    }

    private static Arity arityOf(List<JavaExecutable> constructors) {
        if (constructors.isEmpty()) {
            // Создать нельзя вовсе — и сказать об этом должен сам класс, а не проверка
            // числа аргументов перед ним: «принимает ровно 0, а передано 1» на месте
            // «конструкторы запрещены схемой» уводит совсем не туда.
            return Arity.any();
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (JavaExecutable constructor : constructors) {
            min = Math.min(min, constructor.minimum());
            max = Math.max(max, constructor.varargs() ? Integer.MAX_VALUE : constructor.count());
        }
        return max == Integer.MAX_VALUE ? Arity.atLeast(min) : Arity.between(min, max);
    }

    /**
     * Имена параметров — только когда конструктор один и класс собран
     * с {@code -parameters}.
     * <p>
     * Когда конструкторов несколько, имена принадлежат разным подписям, и одна
     * общая {@code Signature} склеила бы их в неправду. А придумывать
     * {@code arg0, arg1} нельзя тем более: {@code new Date(arg0: 2026)} хуже,
     * чем честное отсутствие именованных аргументов.
     */
    private static Signature signatureOf(List<JavaExecutable> constructors, Arity arity) {
        if (constructors.size() == 1) {
            List<String> names = constructors.get(0).parameterNames();
            if (names != null && !constructors.get(0).varargs()) {
                List<Signature.Param> params = new ArrayList<>(names.size());
                for (String parameter : names) {
                    params.add(Signature.Param.required(parameter));
                }
                return Signature.of(params);
            }
        }
        return Signature.positional(arity);
    }

    /**
     * Статические члены: фабрики схемы, статические методы и константы.
     * <p>
     * <b>Только {@code static final} поля.</b> Изменяемое статическое поле Java —
     * общее на процесс, и снимок с него устарел бы к следующему обращению; молча
     * показывать устаревшее хуже, чем не показывать вовсе.
     * <p>
     * <b>Вторым шагом, а не из конструктора.</b> Константа бывает того же типа,
     * что и класс ({@code LocalDate.MAX}), — чтобы её показать, нужна обёртка,
     * а обёртке нужен этот самый класс. Собирая статику из конструктора, мост
     * уходил бы в бесконечную рекурсию на первом же таком классе; собирая после
     * того, как класс уже лежит в таблице, — находит его там.
     */
    synchronized void initStatics() {
        if (staticsReady) {
            return;
        }
        staticsReady = true;
        installStatics();
    }

    private void installStatics() {
        // Проверено при сборке класса: сюда доходят только существующие методы.
        schema.factories().forEach((scriptName, javaName) -> statics.put(scriptName,
                function(name + "." + scriptName, shape.staticMethods(javaName), null)));
        if (!schema.showsEverything()) {
            return;
        }
        for (String methodName : shape.staticMethodNames()) {
            if (!statics.has(methodName)) {
                statics.put(methodName, function(name + "." + methodName,
                        shape.staticMethods(methodName), null));
            }
        }
        for (String fieldName : shape.staticFieldNames()) {
            JavaFieldAccess field = shape.staticField(fieldName);
            if (field.writable() || statics.has(fieldName)) {
                continue;
            }
            Object constant = field.get(null, name + "." + fieldName, Span.NONE);
            if (showable(constant)) {
                statics.put(fieldName, bridge.marshal().toValue(constant, Span.NONE));
            }
        }
    }

    /**
     * Константа, которую показать нечем, просто не показывается.
     * <p>
     * Отказ при сборке был бы здесь неуместен: константы открываются пачкой,
     * заодно со всем публичным, и одна непереводимая среди них — не ошибка
     * приложения, а обычное дело.
     */
    private boolean showable(Object constant) {
        return constant == null
                || !JavaPolicy.forbidden(constant.getClass()) && bridge.canRepresent(constant);
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Java-тип, стоящий за этим именем.
     * <p>
     * Не {@code type()}: этим именем у {@code Value} зовётся тип значения языка,
     * и у класса он всегда {@code class}.
     */
    public Class<?> javaType() {
        return shape.type();
    }

    JavaShape shape() {
        return shape;
    }

    JavaBridge bridge() {
        return bridge;
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
     * Писать в статику Java-класса нельзя.
     * <p>
     * Класс собирается на запуск, а статическое поле Java общее на процесс: запись
     * из скрипта меняла бы состояние всего приложения — и делала бы это молча,
     * под видом обычного присваивания.
     */
    @Override
    public boolean staticsWritable() {
        return false;
    }

    @Override
    public Value instantiate(List<Value> arguments, CallContext context, Span span) {
        List<JavaExecutable> constructors = constructors();
        if (constructors.isEmpty()) {
            throw new WdlRuntimeError(ErrorKind.CALL, span, refuseCreation());
        }
        JavaExecutable chosen = Overloads.select("new " + name, constructors,
                arguments, bridge.marshal(), span);
        Object created = chosen.invoke(null, arguments, bridge.marshal(),
                "new " + name, context, span);
        return new JavaInstance(this, created);
    }

    private String refuseCreation() {
        if (!schema.constructorsAllowed()) {
            return "класс '" + name + "' нельзя создать: схема запрещает конструкторы"
                    + (schema.factories().isEmpty() ? ""
                    : ", есть фабрики — " + String.join(", ", schema.factories().keySet()));
        }
        Class<?> type = shape.type();
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return "класс '" + name + "' нельзя создать: в Java это "
                    + (type.isInterface() ? "интерфейс" : "абстрактный класс");
        }
        return "класс '" + name + "' нельзя создать: у типа "
                + type.getName() + " нет публичных конструкторов";
    }

    @Override
    public FunctionValue method(InstanceObjectValue instance, String memberName) {
        String javaName = schema.methodOf(memberName);
        if (javaName == null) {
            return null;
        }
        List<JavaExecutable> candidates = shape.methods(javaName);
        if (candidates.isEmpty()) {
            return null;
        }
        return function(name + "." + memberName, candidates,
                NativeInstance.receiverOf(instance, name).state());
    }

    /**
     * Метод как значение-функция.
     * <p>
     * {@code Arity} у неё — общая по всем перегрузкам, и выбор идёт уже внутри:
     * иначе движку пришлось бы знать про перегрузки, которых в языке нет.
     */
    private FunctionValue function(String fullName, List<JavaExecutable> candidates, Object self) {
        return BuiltinFunction.of(fullName, arityOf(candidates), (context, args, span) -> {
            JavaExecutable chosen = Overloads.select(fullName + "()", candidates,
                    args, bridge.marshal(), span);
            Object result = chosen.invoke(self, args, bridge.marshal(),
                    fullName + "()", context, span);
            return bridge.marshal().toValue(result, span);
        });
    }

    @Override
    public Property property(String memberName) {
        JavaAccess access = access(memberName);
        return access == null ? null
                : new JavaProperty(memberName, access, this, schema.readOnly(memberName));
    }

    /** Что стоит за этим именем: пара аксессоров, публичное поле или ничего. */
    private JavaAccess access(String memberName) {
        String beanName = schema.beanOf(memberName);
        if (beanName != null) {
            JavaShape.Bean bean = shape.bean(beanName);
            return bean == null || bean.getter() == null ? null : JavaAccess.of(bean);
        }
        return schema.fieldAllowed(memberName) ? shape.field(memberName) : null;
    }

    @Override
    public ClassValue parentClass() {
        return null;
    }

    @Override
    public List<TraitValue> traits() {
        return List.of();
    }

    @Override
    public List<String> methodNames() {
        if (schema.showsEverything()) {
            return List.copyOf(shape.methodNames());
        }
        List<String> names = new ArrayList<>(schema.declaredMethods());
        names.removeIf(declared -> shape.methods(schema.methodOf(declared)).isEmpty());
        return List.copyOf(names);
    }

    @Override
    public List<String> propertyNames() {
        Set<String> names = new LinkedHashSet<>(schema.declaredBeans().keySet());
        if (schema.showsEverything()) {
            names.addAll(shape.fieldNames());
        } else {
            names.addAll(schema.declaredFields());
        }
        return List.copyOf(names);
    }

    /**
     * {@code is} отвечает по живой иерархии Java.
     * <p>
     * Не перебором того, что собрал мост: {@code isAssignableFrom} знает про предков
     * и интерфейсы всё, а мост — только про то, что ему показали. Поэтому
     * {@code conn is Closeable} верно и тогда, когда {@code Connection} никто
     * не открывал.
     */
    @Override
    public boolean conformsTo(Value classOrTrait) {
        if (classOrTrait == this) {
            return true;
        }
        if (classOrTrait instanceof JavaClass other) {
            return other.shape.type().isAssignableFrom(shape.type());
        }
        return classOrTrait instanceof JavaTrait trait
                && trait.javaType().isAssignableFrom(shape.type());
    }

    @Override
    public String display() {
        return "class " + name;
    }

    @Override
    public String toString() {
        return display();
    }

    /**
     * Свойство обёртки: имя, за которым стоит поле Java или пара аксессоров.
     * <p>
     * Один класс на оба случая — разницу держит {@link JavaAccess}. Пока классов
     * было два, в них дважды повторялись проверка получателя, перевод результата
     * и текст ошибки.
     */
    private record JavaProperty(String name, JavaAccess access, JavaClass owner,
                                boolean frozen) implements Property {

        @Override
        public boolean readable() {
            return true;
        }

        @Override
        public boolean writable() {
            return access.writable() && !frozen;
        }

        @Override
        public Value read(Value receiver, CallContext context, Span span) {
            Marshal marshal = owner.bridge.marshal();
            Object result = access.read(self(receiver), marshal, subject(), context, span);
            return marshal.toValue(result, span);
        }

        @Override
        public void write(Value receiver, Value value, CallContext context, Span span) {
            access.write(self(receiver), value, owner.bridge.marshal(), subject(), context, span);
        }

        private String subject() {
            return owner.name + "." + name;
        }

        private Object self(Value receiver) {
            return NativeInstance.receiverOf(receiver, owner.name).state();
        }
    }
}
