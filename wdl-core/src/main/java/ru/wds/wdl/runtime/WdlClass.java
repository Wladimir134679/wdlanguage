package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.resolve.ClassShape;
import ru.wds.wdl.resolve.FieldSlot;
import ru.wds.wdl.resolve.MethodSlot;
import ru.wds.wdl.resolve.Shape;
import ru.wds.wdl.resolve.TraitShape;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Value;
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
     * «Статические поля» и фабрики: {@code Point.zero}, {@code User.of}.
     * <p>
     * Отдельного слова {@code static} нет и не нужно — класс это значение, значение
     * это контейнер, запись по ключу уже работает. Здесь же лежат фабрики, объявленные
     * формой {@code fun User.of(...)}: это место записи, а не особый вид члена.
     */
    private final MapValue statics = new MapValue();
    /**
     * Интерпретатор нужен, чтобы выполнить тело метода и значения по умолчанию.
     * Он безсостоятельный, поэтому держать на него ссылку безопасно, а альтернатива —
     * протаскивать его аргументом через {@link ClassValue}, у второй реализации
     * которого ({@code embed.NativeClass}) никакого интерпретатора нет и не будет.
     */
    private final Interpreter interpreter;

    WdlClass(ClassShape shape, Environment closure, Unit unit, WdlClass parent,
             List<WdlTrait> traits, Interpreter interpreter) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.closure = Objects.requireNonNull(closure, "closure");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.parent = parent;
        this.traits = List.copyOf(traits);
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
                new InstanceScope(self, (WdlClass) self.owner(), method), method.unit(), interpreter);
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
        if (caller.callDepth() >= ExecutionContext.MAX_CALL_DEPTH) {
            // Создание считается вызовом: 'class Node(next = new Node())' обязано
            // давать ошибку скрипта, а не StackOverflowError в чужом приложении.
            throw new WdlRuntimeError(span, "слишком глубокая рекурсия: вложенных вызовов больше "
                    + ExecutionContext.MAX_CALL_DEPTH + ". Проверьте создание '" + name() + "'");
        }

        Map<Shape, Value[]> bound = bindLineage(arguments, caller);
        InstanceObjectValue instance = InstanceObjectValue.of(this);
        for (FieldSlot slot : shape.fields().values()) {
            instance.put(slot.name(), fieldValue(slot, bound, caller));
        }
        construct(instance, caller, span);
        return instance;
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
    private void construct(InstanceObjectValue instance, CallContext caller, Span span) {
        List<WdlClass> lineage = new ArrayList<>();
        for (WdlClass klass = this; klass != null; klass = klass.parent) {
            lineage.add(klass);
        }
        Collections.reverse(lineage);

        for (WdlClass klass : lineage) {
            FunctionExpr constructor = klass.shape.constructor();
            if (constructor != null) {
                bind(instance, new Method(constructor, klass.closure, klass.unit, klass))
                        .call(caller, List.of(), span);
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
    private Map<Shape, Value[]> bindLineage(List<Value> arguments, CallContext caller) {
        Map<Shape, Value[]> bound = new IdentityHashMap<>();
        List<Value> level = arguments;
        for (WdlClass klass = this; klass != null; klass = klass.parent) {
            Environment local = Scope.under(klass.closure);
            ExecutionContext inner = ExecutionContext.call(local, caller, klass.unit);
            bound.put(klass.shape, bindParams(klass.shape.params(), level, local, inner));

            ClassDeclStmt.Superclass reference = klass.shape.declaration().parent();
            level = reference == null ? List.of() : evaluate(reference.arguments(), inner);
        }
        return bound;
    }

    /**
     * Значения параметров по порядку: переданный аргумент или значение по умолчанию.
     * <p>
     * Имена связываются по мере вычисления, поэтому значение по умолчанию видит
     * параметры левее себя — то же правило, что у функции, и тот же список параметров.
     */
    private Value[] bindParams(List<FunctionExpr.Param> params, List<Value> arguments,
                               Environment local, ExecutionContext inner) {
        Value[] values = new Value[params.size()];
        for (int i = 0; i < params.size(); i++) {
            FunctionExpr.Param param = params.get(i);
            values[i] = i < arguments.size()
                    ? arguments.get(i)
                    : interpreter.visit(param.defaultValue(), inner);
            local.define(param.name(), values[i]);
        }
        return values;
    }

    private List<Value> evaluate(List<ru.wds.wdl.ast.expr.Expr> expressions, ExecutionContext context) {
        List<Value> values = new ArrayList<>(expressions.size());
        expressions.forEach(expression -> values.add(interpreter.visit(expression, context)));
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
    private Value fieldValue(FieldSlot slot, Map<Shape, Value[]> bound, CallContext caller) {
        if (slot.owner() instanceof ClassShape owner) {
            return bound.get(owner)[slot.paramIndex()];
        }
        WdlTrait trait = traitOf((TraitShape) slot.owner());
        ExecutionContext inner = ExecutionContext.call(Scope.under(trait.closure()), caller, trait.unit());
        return interpreter.visit(slot.param().defaultValue(), inner);
    }

    private WdlTrait traitOf(TraitShape shape) {
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
     * Сравнение идёт по формам, а не по значениям: класс, объявленный в теле функции,
     * при каждом вызове даёт новое значение с новым замыканием, но остаётся тем же
     * классом — он объявлен в одном и том же месте текста.
     */
    @Override
    public boolean conformsTo(Value classOrTrait) {
        return switch (classOrTrait) {
            case WdlClass other -> shape.conformsTo(other.shape);
            case WdlTrait other -> shape.conformsTo(other.shape());
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
