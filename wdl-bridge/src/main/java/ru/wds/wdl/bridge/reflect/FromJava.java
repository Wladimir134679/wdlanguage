package ru.wds.wdl.bridge.reflect;

import ru.wds.wdl.bridge.MemberSource;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.NullValue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Что скрипт видит у Java-типа — и единственный способ это записать.
 * <p>
 * Работает в двух ролях, и обе описываются одними и теми же словами:
 * <ul>
 *   <li><b>источник членов</b> своему классу — {@code .members(FromJava.of(JButton.class)
 *       .bean("text"))}. Класс свой: свой конструктор, своё состояние, свой
 *       {@code onClick}, которого у {@code JButton} нет и быть не может, — но
 *       половина его членов уже написана в Swing, и переписывать её лямбдами
 *       значит делать чужую работу второй раз;</li>
 *   <li><b>описание класса целиком</b> для моста — {@link #asClass()}. Тогда своего
 *       у класса нет вовсе: и конструктор, и члены, и статика приходят от типа.</li>
 * </ul>
 * Раньше на эти две роли было два словаря: {@code JavaSchema} с
 * {@code method/methodAs/bean/beanAs/field/readOnly/factory} — для моста, и такой же
 * набор здесь — для источника членов. Одинаковые слова, одинаковые поля, дословно
 * повторённая проверка и дважды написанное сообщение об ошибке. Словарь один,
 * потому что вопрос один: <b>что видно у этого типа</b>. Кто соберёт класс — мост
 * или построитель — на ответ не влияет.
 *
 * <h2>Работает по состоянию экземпляра</h2>
 * Член зовёт Java-объект из {@link NativeInstance#state()} — тот самый, который
 * положил туда {@code init}. Если состояние это не сам объект, а обёртка над ним
 * (у {@code TextArea} в состоянии {@code JScrollPane}, а члены нужны у
 * {@code JTextArea}), объект достаёт {@link #via}.
 *
 * <h2>Проверяется при сборке класса</h2>
 * Нет метода, нет пары аксессоров, нет поля, состояние не того типа — исключение
 * при старте приложения, а не {@code null} у автора скрипта через месяц. Промах
 * по члену у объекта — не ошибка, а правило языка, поэтому опечатку обязан ловить
 * тот, кто описание писал.
 *
 * <h2>Свойство из аксессоров — только по просьбе</h2>
 * {@link #bean(String)} называют руками даже в режиме {@link #all()}. Автоматическое
 * «начинается на get — это свойство» превратило бы в свойство и
 * {@code getAndIncrement()}, а свойство языка обещает, что за именем ничего
 * не происходит ({@code docs/members.md}). Решать это должен человек.
 */
public final class FromJava implements MemberSource {

    private final Class<?> type;
    private final JavaShape shape;
    /** Имя в скрипте → имя метода Java. */
    private final Map<String, String> methods = new LinkedHashMap<>();
    /** Имя свойства в скрипте → имя пары аксессоров Java. */
    private final Map<String, String> beans = new LinkedHashMap<>();
    /** Имя свойства в скрипте → имя публичного поля Java. */
    private final Map<String, String> fields = new LinkedHashMap<>();
    /** Имя в скрипте → имя статического метода Java. */
    private final Map<String, String> factories = new LinkedHashMap<>();
    private final Set<String> readOnly = new LinkedHashSet<>();
    private Marshal marshal = Marshal.plain();
    /** Чем достать нужный объект из состояния экземпляра; {@code null} — само состояние. */
    private Function<Object, Object> extract;
    private String name;
    private boolean all;
    private boolean constructors = true;

    private FromJava(Class<?> type) {
        this.type = Objects.requireNonNull(type, "type");
        if (JavaPolicy.forbidden(type)) {
            throw new IllegalArgumentException("тип " + type.getName()
                    + " не открывается мостом ни при какой политике");
        }
        this.shape = JavaShape.of(type);
        this.name = type.getSimpleName();
    }

    /** Видно ровно то, что перечислено дальше. */
    public static FromJava of(Class<?> type) {
        return new FromJava(type);
    }

    /** Видно всё публичное — то же, что {@code of(type).all()}. */
    public static FromJava everythingOf(Class<?> type) {
        return new FromJava(type).all();
    }

    /**
     * Показать всё публичное: методы, поля, статику, константы.
     * <p>
     * Уместно у типа, который сам себе граница — {@code java.time} неизменяем
     * и ничего не умеет, кроме арифметики над датами. Перечислять сто его методов
     * поимённо значило бы переписать JDK ради нуля выигрыша.
     */
    public FromJava all() {
        this.all = true;
        return this;
    }

    /** Имя, под которым тип встанет в область видимости скрипта. */
    public FromJava as(String scriptName) {
        this.name = require(scriptName, "имя класса");
        return this;
    }

    /** Метод Java под тем же именем: {@code doClick()} → {@code b.doClick()}. */
    public FromJava method(String methodName) {
        return methodAs(methodName, methodName);
    }

    /**
     * Метод Java под другим именем в скрипте.
     * <p>
     * Нужно чаще, чем кажется: в Java метод не назовёшь {@code int}, а в скрипте
     * это лучшее имя.
     */
    public FromJava methodAs(String scriptName, String javaName) {
        methods.put(require(scriptName, "имя метода"), require(javaName, "имя метода Java"));
        return this;
    }

    /** Пара {@code getText()/setText(v)} как свойство {@code .text}. */
    public FromJava bean(String property) {
        return beanAs(property, property);
    }

    /** То же под другим именем в скрипте: в Swing «выбран» это {@code selected}. */
    public FromJava beanAs(String scriptName, String javaProperty) {
        beans.put(require(scriptName, "имя свойства"), require(javaProperty, "имя свойства Java"));
        return this;
    }

    /** Публичное поле Java как свойство: значение живёт в объекте и меняется за спиной. */
    public FromJava field(String fieldName) {
        return fieldAs(fieldName, fieldName);
    }

    /** То же под другим именем в скрипте. */
    public FromJava fieldAs(String scriptName, String javaField) {
        fields.put(require(scriptName, "имя свойства"), require(javaField, "имя поля Java"));
        return this;
    }

    /** Свойство только для чтения, даже если сеттер у типа есть. */
    public FromJava readOnly(String memberName) {
        readOnly.add(require(memberName, "имя свойства"));
        return this;
    }

    /** Статический метод как фабрика на классе: {@code Date.of(2026, 8, 29)}. */
    public FromJava factory(String factoryName) {
        return factoryAs(factoryName, factoryName);
    }

    /** То же под другим именем в скрипте. */
    public FromJava factoryAs(String scriptName, String javaName) {
        factories.put(require(scriptName, "имя фабрики"),
                require(javaName, "имя статического метода"));
        return this;
    }

    /**
     * Запретить {@code new}: объекты этого типа приходят только из фабрик
     * или от приложения.
     */
    public FromJava noConstructors() {
        this.constructors = false;
        return this;
    }

    /**
     * Где искать объект, если состояние экземпляра — не он сам.
     * <p>
     * {@code TextArea} держит в состоянии {@code JScrollPane}, а члены нужны
     * у {@code JTextArea} внутри него. Без этого слова такие классы писали половину
     * своих членов лямбдами-переходниками — ровно ту работу, ради устранения которой
     * источник членов и заведён.
     *
     * <pre>{@code
     * .backing(JScrollPane.class)
     * .members(FromJava.of(JTextArea.class)
     *         .via(state -> ((JScrollPane) state).getViewport().getView())
     *         .bean("text"))
     * }</pre>
     */
    public FromJava via(Function<Object, Object> fromState) {
        this.extract = Objects.requireNonNull(fromState, "fromState");
        return this;
    }

    /**
     * Чем переводить значения на границе.
     * <p>
     * По умолчанию — {@link Marshal#plain()}: числа, строки, списки и карты
     * переводятся, всё остальное отказ. Класс, чьи методы возвращают чужие объекты,
     * передаёт сюда переводчик моста — и тогда они оборачиваются по его политике.
     */
    public FromJava through(Marshal translator) {
        this.marshal = Objects.requireNonNull(translator, "translator");
        return this;
    }

    /** Тип, у которого берутся члены. */
    public Class<?> type() {
        return type;
    }

    /** Имя класса в скрипте. */
    public String name() {
        return name;
    }

    // --- роль первая: источник членов своему классу -------------------------

    @Override
    public void contributeTo(NativeClass.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        String owner = builder.name();
        checkBacking(builder);
        checkNames(owner);

        // Свойства раньше методов: ячейку имени они делят (см. NativeClass.Builder
        // .property), и при совпадении выигрывает свойство — за ним стоит значение,
        // а значение нужнее функции, которая его отдаёт.
        beans.forEach((scriptName, javaProperty) ->
                property(builder, owner, scriptName, JavaAccess.of(shape.bean(javaProperty))));
        fields.forEach((scriptName, javaField) ->
                property(builder, owner, scriptName, shape.field(javaField)));
        if (all) {
            for (String fieldName : shape.fieldNames()) {
                if (!fields.containsKey(fieldName) && !beans.containsKey(fieldName)) {
                    property(builder, owner, fieldName, shape.field(fieldName));
                }
            }
        }

        methods.forEach((scriptName, javaName) ->
                method(builder, owner, scriptName, shape.methods(javaName)));
        if (all) {
            for (String methodName : shape.methodNames()) {
                if (JavaShape.FORBIDDEN.contains(methodName) || methods.containsKey(methodName)
                        || beans.containsKey(methodName) || isProperty(methodName)) {
                    continue;
                }
                method(builder, owner, methodName, shape.methods(methodName));
            }
        }
    }

    /** Занято ли имя свойством: тогда метод под ним не ставится. */
    private boolean isProperty(String memberName) {
        return fields.containsKey(memberName) || shape.field(memberName) != null;
    }

    private void method(NativeClass.Builder builder, String owner, String scriptName,
                        List<JavaExecutable> candidates) {
        String subject = owner + "." + scriptName + "()";
        builder.method(scriptName, Overloads.arityOf(candidates),
                (self, context, args, span) -> {
                    Object target = target(self, owner, scriptName, span);
                    JavaExecutable chosen = Overloads.select(subject, candidates, args, marshal, span);
                    return marshal.toValue(
                            chosen.invoke(target, args, marshal, subject, context, span), span);
                });
    }

    /**
     * Свойством, а не полем, — по общему правилу границы: значение живёт в Java-объекте
     * и меняется за спиной скрипта, а поле, записанное однажды, к следующему обращению
     * устареет.
     */
    private void property(NativeClass.Builder builder, String owner, String scriptName,
                          JavaAccess access) {
        String subject = owner + "." + scriptName;
        if (access.writable() && !readOnly.contains(scriptName)) {
            builder.property(scriptName,
                    (self, context, span) -> read(self, access, owner, scriptName, subject, context, span),
                    (self, value, context, span) -> access.write(
                            target(self, owner, scriptName, span), value, marshal, subject, context, span));
        } else {
            builder.property(scriptName,
                    (self, context, span) -> read(self, access, owner, scriptName, subject, context, span));
        }
    }

    private Value read(NativeInstance self, JavaAccess access, String owner, String scriptName,
                       String subject, CallContext context, Span span) {
        Object target = target(self, owner, scriptName, span);
        return marshal.toValue(access.read(target, marshal, subject, context, span), span);
    }

    // --- роль вторая: класс целиком -----------------------------------------

    /**
     * Готовый класс-обёртка над этим типом: конструктор, члены и статика — всё оттуда.
     * <p>
     * Своего у такого класса нет ничего, поэтому он и {@linkplain
     * NativeClass.Builder#wrapper обёртка}: полей нет, печать — {@code toString()}
     * объекта, в поля класса писать нельзя.
     * <p>
     * Статика ставится <b>вторым шагом</b> ({@link #installStatics}), а не здесь:
     * константа бывает того же типа, что её класс ({@code LocalDate.MAX}), и чтобы
     * её показать, нужна обёртка — то есть этот самый класс, уже лежащий в таблице.
     */
    public NativeClass asClass() {
        List<JavaExecutable> chosen = constructors();
        return NativeClass.named(name)
                .wrapper(type)
                .header(headerOf(chosen))
                .init((self, context, args, span) -> {
                    if (chosen.isEmpty()) {
                        throw new WdlRuntimeError(ErrorKind.CALL, span, refuseCreation());
                    }
                    JavaExecutable made = Overloads.select("new " + name, chosen, args, marshal, span);
                    self.state(made.invoke(null, args, marshal, "new " + name, context, span));
                    return NullValue.NULL;
                })
                .members(this)
                .build();
    }

    /**
     * Ставит фабрики, статические методы и константы в поля собранного класса.
     * <p>
     * Отдельным шагом — см. {@link #asClass()}. Константа, которую показать нечем,
     * просто не показывается: константы открываются пачкой, заодно со всем публичным,
     * и одна непереводимая среди них — не ошибка приложения, а обычное дело.
     */
    public void installStatics(NativeClass built) {
        Objects.requireNonNull(built, "built");
        factories.forEach((scriptName, javaName) -> built.statics().put(scriptName,
                staticFunction(name + "." + scriptName, shape.staticMethods(javaName))));
        if (!all) {
            return;
        }
        for (String methodName : shape.staticMethodNames()) {
            if (!built.statics().has(methodName)) {
                built.statics().put(methodName, staticFunction(name + "." + methodName,
                        shape.staticMethods(methodName)));
            }
        }
        for (String fieldName : shape.staticFieldNames()) {
            JavaFieldAccess field = shape.staticField(fieldName);
            // Только static final: изменяемое статическое поле Java общее на процесс,
            // и снимок с него устарел бы к следующему обращению.
            if (field.writable() || built.statics().has(fieldName)) {
                continue;
            }
            Value shown = showable(field.get(null, name + "." + fieldName, Span.NONE));
            if (shown != null) {
                built.statics().put(fieldName, shown);
            }
        }
    }

    private Value showable(Object constant) {
        if (constant != null && JavaPolicy.forbidden(constant.getClass())) {
            return null;
        }
        try {
            return marshal.toValue(constant, Span.NONE);
        } catch (WdlRuntimeError refused) {
            return null;
        }
    }

    private BuiltinFunction staticFunction(String fullName, List<JavaExecutable> candidates) {
        return BuiltinFunction.of(fullName, Overloads.arityOf(candidates), (context, args, span) -> {
            JavaExecutable chosen = Overloads.select(fullName + "()", candidates, args, marshal, span);
            return marshal.toValue(
                    chosen.invoke(null, args, marshal, fullName + "()", context, span), span);
        });
    }

    private List<JavaExecutable> constructors() {
        return constructors ? shape.constructors() : List.of();
    }

    /**
     * Заголовок из конструкторов: имена параметров — только когда конструктор один
     * и класс собран с {@code -parameters}.
     * <p>
     * Когда конструкторов несколько, имена принадлежат разным подписям, и одна общая
     * подпись склеила бы их в неправду. А придумывать {@code arg0, arg1} нельзя тем
     * более: {@code new Date(arg0: 2026)} хуже, чем честное отсутствие именованных
     * аргументов.
     */
    private static Signature headerOf(List<JavaExecutable> constructors) {
        if (constructors.isEmpty()) {
            // Создать такой класс всё равно нельзя, и отказ выдаст init — с разбором
            // причины. Заголовок здесь только чтобы отказ дошёл до него, а не утонул
            // в «ожидалось 0 аргументов».
            return Signature.positional(Arity.any());
        }
        if (constructors.size() == 1 && !constructors.get(0).varargs()) {
            List<String> names = constructors.get(0).parameterNames();
            if (names != null) {
                List<Signature.Param> params = new ArrayList<>(names.size());
                names.forEach(parameter -> params.add(Signature.Param.required(parameter)));
                return Signature.of(params);
            }
        }
        return Signature.positional(Overloads.arityOf(constructors));
    }

    private String refuseCreation() {
        if (!constructors) {
            return "класс '" + name + "' нельзя создать: схема запрещает конструкторы"
                    + (factories.isEmpty() ? "" : ", есть фабрики — "
                    + String.join(", ", factories.keySet()));
        }
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return "класс '" + name + "' нельзя создать: в Java это "
                    + (type.isInterface() ? "интерфейс" : "абстрактный класс");
        }
        return "класс '" + name + "' нельзя создать: у типа " + type.getName()
                + " нет публичных конструкторов";
    }

    // --- проверки при сборке ------------------------------------------------

    /**
     * Сверяет объявленный тип состояния со своим.
     * <p>
     * Раньше сверки не было вовсе: несовпадение доживало до первого обращения
     * из скрипта и вылетало оттуда {@code IllegalStateException} из глубины
     * рефлексии. Класс, не объявивший {@link NativeClass.Builder#backing}, проверить
     * нечем — тогда остаётся сверка при обращении, как было.
     */
    private void checkBacking(NativeClass.Builder builder) {
        Class<?> declared = builder.declaredBacking();
        if (declared == null || extract != null || type.isAssignableFrom(declared)) {
            return;
        }
        throw new IllegalArgumentException("класс '" + builder.name() + "' держит в состоянии "
                + declared.getName() + ", а члены взяты у " + type.getName()
                + ": объявите backing(" + type.getSimpleName() + ".class) либо достаньте "
                + "нужный объект через via(...)");
    }

    /** Всё обещанное должно существовать у типа — иначе отказ здесь, а не в скрипте. */
    private void checkNames(String owner) {
        methods.forEach((scriptName, javaName) -> {
            if (shape.methods(javaName).isEmpty()) {
                throw refuse(owner, "метода '" + javaName + "'"
                        + (scriptName.equals(javaName) ? "" : " (открыт как '" + scriptName + "')"));
            }
        });
        beans.forEach((scriptName, javaProperty) -> {
            JavaShape.Bean pair = shape.bean(javaProperty);
            if (pair == null || pair.getter() == null) {
                throw refuse(owner, "пары аксессоров для свойства '" + javaProperty
                        + "': нужен get" + capitalize(javaProperty) + "() или is"
                        + capitalize(javaProperty) + "()");
            }
        });
        fields.forEach((scriptName, javaField) -> {
            if (shape.field(javaField) == null) {
                throw refuse(owner, "публичного поля '" + javaField + "'");
            }
        });
        factories.forEach((scriptName, javaName) -> {
            if (shape.staticMethods(javaName).isEmpty()) {
                throw refuse(owner, "статического метода '" + javaName + "' для фабрики '"
                        + scriptName + "'");
            }
        });
        readOnly.forEach(memberName -> {
            if (!beans.containsKey(memberName) && !fields.containsKey(memberName)
                    && !(all && (shape.bean(memberName) != null || shape.field(memberName) != null))) {
                throw refuse(owner, "поля или свойства '" + memberName
                        + "', объявленного только для чтения");
            }
        });
    }

    /**
     * Java-объект этого экземпляра.
     * <p>
     * Пустым он бывает ровно в двух случаях: класс не завёл состояние в {@code init}
     * или уже отдал его в {@code close()}. Второе — обычная жизнь скрипта, поэтому
     * это ошибка выполнения с внятным текстом, а не {@code NullPointerException}
     * из глубины рефлексии.
     */
    private Object target(NativeInstance self, String owner, String member, Span span) {
        Object state = self.state();
        if (state == null) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, owner + "." + member
                    + ": объект уже закрыт или ещё не создан");
        }
        Object object = extract == null ? state : extract.apply(state);
        if (!type.isInstance(object)) {
            throw new IllegalStateException("класс '" + owner + "' держит в состоянии "
                    + state.getClass().getName() + ", а члены взяты у " + type.getName());
        }
        return object;
    }

    private IllegalArgumentException refuse(String owner, String missing) {
        return new IllegalArgumentException("класс '" + owner + "': у типа "
                + type.getName() + " нет " + missing);
    }

    private static String require(String value, String role) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(role + " не может быть пустым");
        }
        return value;
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
