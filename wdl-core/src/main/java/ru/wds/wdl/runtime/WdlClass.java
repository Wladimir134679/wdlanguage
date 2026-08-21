package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.embed.NativeTrait;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.resolve.ClassShape;
import ru.wds.wdl.resolve.FieldSlot;
import ru.wds.wdl.resolve.MethodSlot;
import ru.wds.wdl.resolve.PropertySlot;
import ru.wds.wdl.resolve.NativeTraitShape;
import ru.wds.wdl.resolve.ScriptTraitShape;
import ru.wds.wdl.resolve.Shape;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arguments;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.MapValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Класс во время выполнения: форма плюс область, где класс объявлен.
 * <p>
 * Форма ({@link ClassShape}) знает имена и порядок и общая для всех запусков;
 * значение знает замыкание и потому у каждого запуска своё. Отсюда же и то, что
 * класс, объявленный внутри функции, при каждом вызове даёт новое значение
 * с новым замыканием, но остаётся тем же классом для {@code is} — форма-то одна.
 * <p>
 * Таблица методов здесь <b>плоская</b>: собрана один раз из родителя, трейтов
 * и собственных методов по правилу «побеждает последний». Поиска по цепочке
 * во время выполнения нет, поэтому обращение к методу стоит столько же, сколько
 * обращение к полю, и не зависит от глубины наследования.
 */
final class WdlClass implements ClassValue {

    private final ClassShape shape;
    private final Environment closure;
    /** Файл, где класс объявлен: в нём выполняются его конструктор, методы и фабрики. */
    private final Unit unit;
    private final WdlClass parent;
    private final List<WdlTrait> traits;
    private final Map<String, Method> methods;
    /**
     * Плоская таблица свойств — собрана тем же правилом и в том же порядке, что
     * таблица методов: родитель, трейты слева направо, свои. Поиска по цепочке
     * во время выполнения нет и здесь.
     */
    private final Map<String, WdlProperty> properties;
    /**
     * «Статические поля» и фабрики: {@code Point.zero}, {@code User.of}.
     * <p>
     * Отдельного слова {@code static} нет и не нужно — класс это значение, значение
     * это контейнер, запись по ключу уже работает. Здесь же лежат фабрики, объявленные
     * формой {@code def User.of(...)}: это место записи, а не особый вид члена.
     */
    private final MapValue statics = new MapValue();
    /**
     * Запуск, которому класс принадлежит. Нужен затем же, зачем функции: связанный
     * метод — это {@link UserFunction}, и она обязана унести сеанс с собой, чтобы
     * приложение могло позвать её из своего потока. См. {@link Run}.
     */
    private final Run run;
    /**
     * Интерпретатор нужен, чтобы выполнить тело метода и значения по умолчанию.
     * Он безсостоятельный, поэтому держать на него ссылку безопасно, а альтернатива —
     * протаскивать его аргументом через {@link ClassValue}, у второй реализации
     * которого ({@code embed.NativeClass}) никакого интерпретатора нет и не будет.
     */
    private final Interpreter interpreter;

    WdlClass(ClassShape shape, Environment closure, Unit unit, WdlClass parent,
             List<WdlTrait> traits, Run run, Interpreter interpreter) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.closure = Objects.requireNonNull(closure, "closure");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.parent = parent;
        this.traits = List.copyOf(traits);
        this.run = Objects.requireNonNull(run, "run");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");

        Map<String, Method> table = new LinkedHashMap<>();
        // Методы родителя и трейтов приходят вместе со своими юнитами: унаследованный
        // метод остаётся кодом того файла, где он написан.
        if (parent != null) {
            table.putAll(parent.methods);
        }
        for (WdlTrait trait : traits) {
            table.putAll(trait.methods());
        }
        for (MethodSlot slot : shape.methods().values()) {
            if (slot.declaredIn() == shape) {
                table.put(slot.name(), new Method(slot.declaration(), closure, unit, this));
            }
        }
        this.methods = Collections.unmodifiableMap(table);

        Map<String, WdlProperty> declared = new LinkedHashMap<>();
        if (parent != null) {
            declared.putAll(parent.properties);
        }
        for (WdlTrait trait : traits) {
            for (PropertySlot slot : trait.properties().values()) {
                // super внутри аксессора трейта запрещён разбором — класса здесь нет,
                // ровно как у метода трейта.
                declared.put(slot.name(), new WdlProperty(slot.declaration(), trait.closure(),
                        trait.unit(), null, run, interpreter));
            }
        }
        for (PropertySlot slot : shape.properties().values()) {
            if (slot.owner() == shape) {
                declared.put(slot.name(), new WdlProperty(slot.declaration(), closure, unit,
                        this, run, interpreter));
            }
        }
        this.properties = Collections.unmodifiableMap(declared);
    }

    @Override
    public Property property(String name) {
        return properties.get(name);
    }

    ClassShape shape() {
        return shape;
    }

    Environment closure() {
        return closure;
    }

    Unit unit() {
        return unit;
    }

    WdlClass parent() {
        return parent;
    }

    @Override
    public MapValue statics() {
        return statics;
    }

    /**
     * Метод, связанный с экземпляром, или {@code null}, если такого метода нет.
     * <p>
     * Связанный метод — это обычная {@link UserFunction}, замыканием которой служит
     * {@link InstanceScope}. Отдельного вида значения не нужно: объект помнится
     * замыканием, а арность, значения по умолчанию, {@code return} и ограничение
     * глубины достаются готовыми.
     * <p>
     * Значение создаётся на каждом чтении, поэтому {@code p.text} и {@code p.text} —
     * два разных значения. Для языка это ничего не меняет: функции и без того
     * сравниваются по ссылке.
     */
    @Override
    public FunctionValue method(InstanceObjectValue instance, String name) {
        Method method = methods.get(name);
        return method == null ? null : bind(instance, method);
    }

    /**
     * Связывает метод с экземпляром.
     * <p>
     * Приёмником становится сам объект, а не вид {@code super}, через который метод
     * могли прочитать: вид — только точка отсчёта для поиска, а {@code this} внутри
     * метода обязан быть настоящим объектом. Класс для виртуального поиска голых имён
     * тоже берётся у объекта, а не у того класса, в чьей таблице метод нашёлся.
     */
    private FunctionValue bind(InstanceObjectValue container, Method method) {
        InstanceObjectValue self = container.identity();
        return new UserFunction(method.declaration(),
                new InstanceScope(self, (WdlClass) self.owner(), method), method.unit(),
                run, interpreter,
                // Замок synchronized-метода — замок экземпляра, а не метода: метод
                // защищает поля своего объекта, поэтому два таких метода одного объекта
                // взаимно исключаются, а два разных объекта друг другу не мешают.
                // Связанное значение создаётся на каждом чтении 'p.push', поэтому
                // собственный замок здесь был бы новым при каждом обращении и не защищал
                // бы ничего.
                method.declaration().isSynchronized() ? self.guard() : null);
    }

    /**
     * Создаёт экземпляр.
     * <p>
     * Порядок ровно такой, как обещан языком:
     * <ol>
     *   <li>число аргументов проверено по заголовку — до входа сюда;</li>
     *   <li>связываются параметры: свои, затем родительские (их аргументы видят
     *       параметры потомка), затем значения по умолчанию трейтов;</li>
     *   <li>заводится экземпляр, и поля пишутся <b>по одному разу</b> в порядке
     *       плоской таблицы — родительские, из трейтов, свои;</li>
     *   <li>выполняются конструкторы от самого дальнего предка к этому классу.</li>
     * </ol>
     * К телу конструктора в {@code this} уже всё: поля родителя, поля трейтов и свои.
     * Поэтому в конструкторе можно посчитать производное поле, проверить инвариант
     * целиком и отдать объект наружу.
     */
    @Override
    public Value instantiate(List<Value> arguments, CallContext caller, Span span) {
        // У класса с остатком плотный список сам собой не разложится: заголовок идёт
        // по своим позициям, и хвост, которому места не хватило, потерялся бы молча.
        // То же правило и по той же причине, что в UserFunction.call.
        Signature signature = signature();
        Arguments prepared = signature.hasRest() || signature.hasNamedRest()
                ? Binder.bindPositional(signature, arguments, Binder.Callee.klass(name()), span)
                : Arguments.positional(arguments);
        return instantiate(prepared, caller, span);
    }

    /**
     * То же создание по разложенному набору: у класса на wdl значения по умолчанию
     * отложенные, поэтому пропуск в середине — {@code new Point(y: 3)} — доживает сюда
     * и заполняется там же, где связываются параметры.
     */
    @Override
    public Value instantiate(Arguments arguments, CallContext caller, Span span) {
        // Создание — такой же вход в скрипт, как вызов: приложение вправе позвать
        // 'new' само, и конструктор — обычный код на wdl. Правило то же, что
        // у UserFunction.call: изнутри запуска мы уже внутри, снаружи надо войти.
        if (run.insideCurrentThread()) {
            return create(arguments, caller, span);
        }
        run.enter(span);
        try {
            return create(arguments, caller, span);
        } finally {
            run.leave();
        }
    }

    private Value create(Arguments arguments, CallContext caller, Span span) {
        if (caller.callDepth() >= ExecutionContext.MAX_CALL_DEPTH) {
            // Создание считается вызовом: 'class Node(next = new Node())' обязано
            // остановить выполнение, а не свалить чужое приложение StackOverflowError.
            throw FatalError.tooDeep(span, "Проверьте создание '" + name() + "'");
        }

        Map<Shape, Header> bound = bindLineage(arguments, caller, span);
        InstanceObjectValue instance = InstanceObjectValue.of(this);
        for (FieldSlot slot : shape.fields().values()) {
            instance.put(slot.name(), fieldValue(slot, bound, caller, span));
        }
        initHidden(instance, bound, caller, span);
        construct(instance, bound, caller, span);
        return instance;
    }

    /**
     * Записывает начальные значения скрытых полей: {@code property x = 0}.
     * <p>
     * После обычных полей и <b>до</b> конструктора, потому что скрытое поле — такое же
     * поле: к телу конструктора объект обязан быть собран целиком, и {@code this.x = 5}
     * в конструкторе должно попасть в уже готовое свойство, а не опередить его
     * начальное значение.
     * <p>
     * Область вычисления — та же, что у обычного поля из того же заголовка: у свойства
     * класса это область его заголовка, поэтому {@code property x = w * 2} видит поле
     * {@code w}; у свойства трейта — область объявления трейта, полей класса оно
     * не видит и видеть не может.
     */
    private void initHidden(InstanceObjectValue instance, Map<Shape, Header> bound,
                            CallContext caller, Span span) {
        for (PropertySlot slot : shape.properties().values()) {
            if (!slot.hasBackingField()) {
                continue;
            }
            Environment scope;
            Unit where;
            if (slot.owner() instanceof ClassShape owner) {
                scope = Scope.under(bound.get(owner).scope());
                where = classOf(owner).unit;
            } else {
                WdlTrait trait = traitOf((ScriptTraitShape) slot.owner());
                scope = Scope.under(trait.closure());
                where = trait.unit();
            }
            ExecutionContext inner = ExecutionContext.call(scope, caller, run, where,
                    "new " + name(), span);
            instance.hidden(slot.name(), interpreter.visit(slot.declaration().initial(), inner));
        }
    }

    /** Класс цепочки по его форме: у скрытого поля свой файл, как и у метода. */
    private WdlClass classOf(ClassShape owner) {
        for (WdlClass klass = this; klass != null; klass = klass.parent) {
            if (klass.shape == owner) {
                return klass;
            }
        }
        throw new IllegalStateException("класс " + owner.name() + " не в цепочке " + name());
    }

    /**
     * Связанный заголовок одного класса цепочки.
     * <p>
     * Значения полей лежат по позициям — по ним идёт запись в экземпляр. Область
     * нужна отдельно из-за остатка: {@code *args} и {@code **named} полями не стали,
     * а видны быть должны — в аргументах родителю и в конструкторе своего класса.
     * Держать их больше негде: в экземпляре их нет по построению.
     *
     * @param values значения позиционных параметров заголовка
     * @param scope  область, в которой заголовок выполнялся: параметры и остатки
     */
    private record Header(Value[] values, Environment scope) {
    }

    /**
     * Выполняет конструкторы — от самого дальнего предка к этому классу, каждый по разу.
     * <p>
     * Отдельного «до записи» в языке нет. Оно понадобилось бы, будь поля неизменяемыми,
     * как у Java-рекорда; здесь поля обычные, и правка поля после записи делает ровно
     * то же самое, зато {@code this} не бывает наполовину собранным.
     * <p>
     * Конструктор — обычный метод без параметров, поэтому и выполняется он как метод:
     * та же область поверх экземпляра, тот же {@code return}, та же защита от рекурсии.
     * В таблицу методов он при этом не попадает — {@code p.Point()} вызвать нельзя.
     */
    private void construct(InstanceObjectValue instance, Map<Shape, Header> bound,
                           CallContext caller, Span span) {
        List<WdlClass> lineage = new ArrayList<>();
        for (WdlClass klass = this; klass != null; klass = klass.parent) {
            lineage.add(klass);
        }
        Collections.reverse(lineage);

        for (WdlClass klass : lineage) {
            FunctionExpr constructor = klass.shape.constructor();
            if (constructor != null) {
                // Замыканием служит не область объявления класса, а область его
                // заголовка — тот же слой плюс остаток. Позиционные параметры в ней
                // тоже лежат, но их всегда перекрывают одноимённые поля экземпляра,
                // поэтому единственное, что этот слой добавляет, — '*args' и '**named'.
                bind(instance, new Method(constructor, bound.get(klass.shape).scope(),
                        klass.unit, klass)).call(caller, List.of(), span);
            }
        }
    }

    /**
     * Связывает параметры всей цепочки классов.
     * <p>
     * Аргументы родителя вычисляются в области, где связаны параметры потомка, —
     * поэтому {@code class Square(side) : Shape("сторона " + side)} работает.
     * И вычисляются они <b>всегда</b>, даже если одноимённое поле перекрыто своим
     * параметром: это список аргументов, а не источник поля, и побочный эффект
     * в нём не должен зависеть от того, какие имена завёл потомок.
     */
    private Map<Shape, Header> bindLineage(Arguments arguments, CallContext caller, Span span) {
        Map<Shape, Header> bound = new IdentityHashMap<>();
        Arguments level = arguments;
        for (WdlClass klass = this; klass != null; klass = klass.parent) {
            Environment local = Scope.under(klass.closure);
            // Кадр называется 'new Имя': в трассировке ошибка из значения по умолчанию
            // или из аргумента родителю должна показывать создание, а не пустоту.
            ExecutionContext inner = ExecutionContext.call(local, caller, run, klass.unit,
                    "new " + klass.name(), span);
            Value[] values = bindParams(klass.shape.params(), level, local, inner);
            bindRest(klass.shape, level, local);
            bound.put(klass.shape, new Header(values, local));

            ClassDeclStmt.Superclass reference = klass.shape.declaration().parent();
            level = reference == null ? Arguments.none() : parentArguments(klass, reference, inner, span);
        }
        return bound;
    }

    /**
     * Заводит остатки заголовка в области связывания: {@code *args} и {@code **named}.
     * <p>
     * После позиционных параметров, а не до: остаток — это то, чего им не хватило,
     * и посчитать его можно только когда позиции разобраны. Правее остатка параметров
     * не бывает — он заканчивает заголовок, — поэтому значений по умолчанию, которым
     * он мог бы понадобиться, здесь нет; нужен он аргументам родителю и конструктору.
     * В экземпляр остатки не пишутся — почему, разобрано в {@code ast.stmt.ClassDeclStmt}.
     */
    private static void bindRest(ClassShape shape, Arguments arguments, Environment local) {
        if (shape.restName() != null) {
            local.define(shape.restName(), ArrayValue.of(arguments.rest()));
        }
        if (shape.namedRestName() != null) {
            MapValue named = new MapValue();
            arguments.namedRest().forEach(named::put);
            local.define(shape.namedRestName(), named);
        }
    }

    /**
     * Аргументы родителю — с именами, если они там написаны: {@code : Shape(radius: r)}.
     * <p>
     * Раскладка тем же связывателем, что у обычного вызова, но ошибок здесь ждать почти
     * не приходится: имена в заголовке родителя проверил {@code Linker} ещё при связывании
     * класса, когда форма родителя стала известна. Это тот редкий случай, когда язык
     * успевает сказать об опечатке до первого {@code new}.
     */
    private Arguments parentArguments(WdlClass klass, ClassDeclStmt.Superclass reference,
                                      ExecutionContext inner, Span span) {
        List<Value> values = evaluate(reference.arguments(), inner);
        if (!Binder.needed(klass.parent.signature(), reference.arguments())) {
            return Arguments.positional(values);
        }
        return Binder.bind(klass.parent.signature(), reference.arguments(), values,
                Binder.Callee.klass(klass.parent.name()), span);
    }

    /**
     * Значения параметров по порядку: переданный аргумент или значение по умолчанию.
     * <p>
     * Имена связываются по мере вычисления, поэтому значение по умолчанию видит
     * параметры левее себя — то же правило, что у функции, и тот же список параметров.
     */
    private Value[] bindParams(List<FunctionExpr.Param> params, Arguments arguments,
                               Environment local, ExecutionContext inner) {
        Value[] values = new Value[params.size()];
        for (int i = 0; i < params.size(); i++) {
            FunctionExpr.Param param = params.get(i);
            // Как и у функции: спрашивается «заполнена ли позиция», а не длина списка, —
            // именованное создание вправе задать поле, пропустив предыдущее.
            values[i] = arguments.has(i)
                    ? arguments.get(i)
                    : interpreter.visit(param.defaultValue(), inner);
            local.define(param.name(), values[i]);
        }
        return values;
    }

    /** Значения аргументов заголовка — в порядке записи, как и у обычного вызова. */
    private List<Value> evaluate(List<Argument> arguments, ExecutionContext context) {
        List<Value> values = new ArrayList<>(arguments.size());
        arguments.forEach(argument -> values.add(interpreter.visit(argument.value(), context)));
        return values;
    }

    /**
     * Значение поля: у класса оно уже связано, у трейта вычисляется здесь.
     * <p>
     * Значение по умолчанию трейта считается только для <b>победившего</b> слота:
     * оно «подставляется скрыто», и наблюдаемым — через побочный эффект — быть
     * не должно. Область вычисления при этом всегда область объявления трейта:
     * полей класса такое значение не видит и видеть не может.
     */
    private Value fieldValue(FieldSlot slot, Map<Shape, Header> bound, CallContext caller, Span span) {
        if (slot.owner() instanceof ClassShape owner) {
            return bound.get(owner).values()[slot.paramIndex()];
        }
        if (slot.owner() instanceof NativeTraitShape declared) {
            // У трейта от приложения значение поля готово: вычислять нечего,
            // а значит и области, в которой вычислять, не нужно.
            return declared.defaultOf(slot.name());
        }
        WdlTrait trait = traitOf((ScriptTraitShape) slot.owner());
        ExecutionContext inner = ExecutionContext.call(Scope.under(trait.closure()), caller,
                run, trait.unit(), "new " + name(), span);
        return interpreter.visit(slot.param().defaultValue(), inner);
    }

    private WdlTrait traitOf(ScriptTraitShape shape) {
        for (WdlClass klass = this; klass != null; klass = klass.parent) {
            for (WdlTrait trait : klass.traits) {
                if (trait.shape() == shape) {
                    return trait;
                }
            }
        }
        throw new IllegalStateException("трейт " + shape.name() + " не подмешан в " + name());
    }

    @Override
    public String name() {
        return shape.name();
    }

    @Override
    public Arity arity() {
        return shape.arity();
    }

    /**
     * Контракт создания: имена полей заголовка и остатки. Значения по умолчанию
     * отложенные — по той же причине, что у функции: там стоит выражение,
     * а не готовое значение.
     */
    @Override
    public Signature signature() {
        List<Signature.Param> params = new ArrayList<>(shape.params().size());
        for (FunctionExpr.Param param : shape.params()) {
            params.add(param.hasDefault()
                    ? Signature.Param.lazy(param.name())
                    : Signature.Param.required(param.name()));
        }
        return Signature.of(params, shape.restName(), shape.namedRestName());
    }

    /**
     * Сравнение идёт по формам, а не по значениям: класс, объявленный в теле функции,
     * при каждом вызове даёт новое значение с новым замыканием, но остаётся тем же
     * классом — он объявлен в одном и том же месте текста.
     */
    @Override
    public boolean conformsTo(Value classOrTrait) {
        return switch (classOrTrait) {
            case WdlClass other -> shape.conformsTo(other.shape);
            case WdlTrait other -> shape.conformsTo(other.shape());
            // Трейт от приложения отвечает так же: сравниваются формы, а форма
            // у него одна на всю его жизнь — как и у трейта, объявленного скриптом.
            case NativeTrait other -> shape.conformsTo(other.shape());
            default -> false;
        };
    }

    /** {@code class Point(x, y)} — заголовок без значений по умолчанию: важен состав полей. */
    @Override
    public String display() {
        return "class " + name() + shape.params().stream()
                .map(FunctionExpr.Param::name)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    @Override
    public String toString() {
        return display();
    }
}
