package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arguments;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Функция, написанная на wdl: тело-дерево плюс область видимости, в которой её объявили.
 * <p>
 * Для языка она ничем не отличается от встроенной {@link BuiltinFunction} — такое же
 * значение в такой же переменной. Поэтому её можно передать аргументом, вернуть
 * из другой функции, положить в массив и в поле объекта, а {@code visitCall} не знает
 * и не хочет знать, что внутри: Java-лямбда или дерево.
 * <p>
 * <b>Замыкание — это ссылка на область объявления, и больше ничего.</b> Отсюда само
 * собой получается всё, чего от функций ждут:
 * <ul>
 *   <li>рекурсия — объявление заводит имя в той же области, которая стала замыканием,
 *       поэтому функция находит себя обычным поиском имени;</li>
 *   <li>вложенная функция видит переменные вызова, внутри которого её объявили,
 *       и живёт дольше него;</li>
 *   <li>значения переменных не копируются: замыкание захватывает область, а не снимок,
 *       так что два замыкания над одной переменной видят одно и то же.</li>
 * </ul>
 * Тело функции выполняется в <b>вложенной</b> области: имя, впервые присвоенное внутри,
 * наружу не попадает, а присваивание уже известному имени уходит туда, где оно заведено.
 * Правило то же, что у блока, — функция не заводит для него исключений.
 * <p>
 * Вместе с замыканием функция запоминает и {@link Unit юнит} — файл, где она написана.
 * Замыкание отвечает на вопрос «какие имена видно», юнит — «в каком файле мы находимся»:
 * функция из модуля, вызванная из главного скрипта, объявляет классы по формам своего
 * файла и сообщает об ошибках его строками.
 * <p>
 * И третье, что она несёт с собой, — {@link Run сеанс}: модули, формы классов, классы
 * ошибок своего запуска. <b>Этих трёх ссылок хватает, чтобы выполнить функцию откуда
 * угодно.</b> Приложение вправе достать значение-функцию из области видимости, отдать
 * его в свой обработчик, в очередь, в другой поток — и позвать; она выполнится там же,
 * где была объявлена, увидит те же переменные и те же импорты, а её изменения увидят
 * все, кто смотрит на ту же область. От вызывающего ей нужен только вывод.
 */
public final class UserFunction implements FunctionValue {

    private final FunctionExpr declaration;
    private final Environment closure;
    private final Unit unit;
    /** Запуск, которому функция принадлежит: см. javadoc класса и {@link Run}. */
    private final Run run;
    /** Интерпретатор безсостоятельный, поэтому делить один экземпляр безопасно. */
    private final Interpreter interpreter;
    private final Arity arity;
    /** Контракт вызова: имена параметров нужны, чтобы принимать именованные аргументы. */
    private final Signature signature;
    /**
     * Замок {@code synchronized}-функции или {@code null}, если модификатора нет.
     * <p>
     * Кто его создаёт — и есть ответ на вопрос «чей это замок». У обычной функции его
     * заводит {@code Interpreter.visitFunction}, то есть <b>вычисление литерала</b>:
     * замыкание, созданное дважды, даёт два значения и два замка, и это верно —
     * у них разное захваченное состояние. У метода класса замок приходит готовым
     * от {@code WdlClass.bind} — замок <b>экземпляра</b>, общий для всех его методов.
     */
    private final ReentrantLock guard;

    UserFunction(FunctionExpr declaration, Environment closure, Unit unit, Run run,
                 Interpreter interpreter, ReentrantLock guard) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.closure = Objects.requireNonNull(closure, "closure");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.run = Objects.requireNonNull(run, "run");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.arity = arityOf(declaration);
        this.signature = signatureOf(declaration);
        this.guard = guard;
    }

    /**
     * Обязательных параметров столько, сколько их до первого со значением по умолчанию:
     * парсер не пропускает обязательный после необязательного, поэтому дальше идут
     * только необязательные и арность остаётся отрезком. Остаток {@code *args} верхнюю
     * границу снимает совсем — принять функция готова сколько угодно.
     */
    private static Arity arityOf(FunctionExpr declaration) {
        List<FunctionExpr.Param> params = declaration.params();
        int required = 0;
        while (required < params.size() && !params.get(required).hasDefault()) {
            required++;
        }
        return declaration.isVariadic()
                ? Arity.atLeast(required)
                : Arity.between(required, params.size());
    }

    /**
     * Контракт вызова: имена из заголовка функции.
     * <p>
     * Значения по умолчанию объявлены {@linkplain Signature.Param#lazy(String)
     * отложенными} — и это не оговорка, а суть: по умолчанию здесь стоит <b>выражение</b>,
     * которое считается в области вызова и видит параметры левее себя. Подставить его
     * снаружи, до входа в функцию, значило бы посчитать его не там и не тогда.
     */
    private static Signature signatureOf(FunctionExpr declaration) {
        List<Signature.Param> params = new ArrayList<>(declaration.params().size());
        for (FunctionExpr.Param param : declaration.params()) {
            params.add(param.hasDefault()
                    ? Signature.Param.lazy(param.name())
                    : Signature.Param.required(param.name()));
        }
        return Signature.of(params,
                declaration.rest() == null ? null : declaration.rest().name(),
                declaration.namedRest() == null ? null : declaration.namedRest().name());
    }

    @Override
    public String name() {
        return declaration.title();
    }

    @Override
    public String knownName() {
        // Именно name(), а не title(): title подставляет 'def' там, где имени нет,
        // и эта подстановка предназначена сообщениям, а не данным.
        return declaration.name();
    }

    @Override
    public boolean anonymous() {
        return declaration.anonymous();
    }

    @Override
    public Arity arity() {
        return arity;
    }

    @Override
    public Signature signature() {
        return signature;
    }

    /**
     * Вызывает функцию — изнутри скрипта или снаружи, из приложения.
     * <p>
     * Разница между этими двумя случаями ровно одна и вся здесь: вызов изнутри уже
     * внутри запуска, а вызов снаружи в него входит — и этот вход считается
     * ({@link Run#MAX_ENTRIES}) и проверяется на закрытие. Дальше кода два раза
     * не написано — тело одно, и оно не знает, кто его начал.
     */
    @Override
    public Value call(CallContext context, List<Value> arguments, Span span) {
        // У функции с остатком плотный список сам собой не разложится: тело идёт
        // по своим параметрам, и хвост, которому места не хватило, потерялся бы молча.
        // Поэтому вызов снаружи проходит ту же раскладку, что и вызов из скрипта.
        Arguments prepared = signature.hasRest() || signature.hasNamedRest()
                ? Binder.bindPositional(signature, arguments, Binder.Callee.function(name()), span)
                : Arguments.positional(arguments);
        return call(context, prepared, span);
    }

    /**
     * Тот же вызов, но по разложенному набору: у функции на wdl значение по умолчанию —
     * выражение, поэтому пропуск в середине доживает именно сюда и заполняется в теле.
     */
    @Override
    public Value call(CallContext context, Arguments arguments, Span span) {
        if (run.insideCurrentThread()) {
            return guarded(context, arguments, span);
        }
        // Внешний вход: чужой поток, другой запуск или приложение вовсе без запуска.
        run.enter(span);
        try {
            return guarded(context, arguments, span);
        } finally {
            run.leave();
        }
    }

    /**
     * Берёт замок {@code synchronized}-функции, если он у неё есть.
     * <p>
     * <b>Внутри входа в запуск, а не снаружи.</b> Сейчас вход ничего не блокирует,
     * поэтому порядок ни на что не влияет; он выбран на случай, когда вход снова
     * начнёт ждать (лимиты выполнения, пауза запуска). Замок, взятый раньше входа,
     * дал бы классическую взаимную блокировку: один поток держит функцию и ждёт вход,
     * другой держит вход и ждёт функцию. Взятые в одном порядке, они не встретятся.
     * <p>
     * {@code lockInterruptibly}, а не {@code lock}: ожидание входа обязано сниматься
     * прерыванием — иначе {@code t.interrupt()} не достал бы поток, застрявший здесь,
     * и остановка зациклившегося скрипта перестала бы работать.
     */
    private Value guarded(CallContext context, Arguments arguments, Span span) {
        if (guard == null) {
            return body(context, arguments, span);
        }
        try {
            guard.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw FatalError.interrupted(span);
        }
        try {
            return body(context, arguments, span);
        } finally {
            guard.unlock();
        }
    }

    private Value body(CallContext context, Arguments arguments, Span span) {
        // Прерывание проверяется и здесь, а не только в циклах: рекурсия без цикла —
        // такое же зацикливание, и остановить её снаружи надо той же кнопкой.
        if (Thread.currentThread().isInterrupted()) {
            throw FatalError.interrupted(span);
        }
        if (context.callDepth() >= ExecutionContext.MAX_CALL_DEPTH) {
            // Рекурсия без выхода — не «эта операция не удалась», а «выполнение дальше
            // не идёт»: поймать такое обработчиком нельзя, иначе цикл с try съел бы
            // собственную защиту от зацикливания.
            throw FatalError.tooDeep(span, "Проверьте условие выхода из '" + name() + "'");
        }

        Environment local = closure.child();
        // Контекст создаётся до связывания: в нём же вычисляются значения по умолчанию,
        // и оттого они видят параметры, связанные левее, — область у них одна и та же.
        ExecutionContext inner = ExecutionContext.call(local, context, run, unit, name(), span);
        List<FunctionExpr.Param> params = declaration.params();
        for (int i = 0; i < params.size(); i++) {
            // Параметры — всегда локальные: одноимённая внешняя переменная остаётся
            // нетронутой, даже если функция параметру что-то присвоит.
            // Пропуск бывает и в середине — именованный вызов может задать третий
            // параметр, не задав второго, — поэтому спрашивается «заполнена ли позиция»,
            // а не «хватило ли длины списка».
            local.define(params.get(i).name(), arguments.has(i)
                    ? arguments.get(i)
                    // Значение по умолчанию считается заново на каждом вызове: снимок,
                    // сделанный при объявлении, сделал бы один массив или объект общим
                    // для всех вызовов — известная ловушка Python.
                    : interpreter.visit(params.get(i).defaultValue(), inner));
        }
        // Остатки — после параметров, и это не мелочь: значение по умолчанию видит
        // только то, что связано левее, а остаток левее не бывает. Контейнеры свежие
        // на каждом вызове — по той же причине, по которой дефолт считается заново.
        if (declaration.rest() != null) {
            local.define(declaration.rest().name(), ArrayValue.of(arguments.rest()));
        }
        if (declaration.namedRest() != null) {
            MapValue named = new MapValue();
            arguments.namedRest().forEach(named::put);
            local.define(declaration.namedRest().name(), named);
        }

        try {
            interpreter.visit(declaration.body(), inner);
        } catch (ControlSignal.Return signal) {
            return signal.value();
        } catch (WdlError error) {
            // Место в исходнике у ошибки уже есть, а вот какому файлу оно принадлежит,
            // знает только тело функции — здесь и последняя возможность это сказать.
            if (error instanceof WdlRuntimeError runtime) {
                // И здесь же — путь по скрипту. Спросят об этом все границы вызова
                // на пути наружу, но полная цепочка кадров только у самой внутренней:
                // у внешних от неё остался бы хвост.
                runtime.rememberTrace(Frame.trace(inner.frame()));
            }
            throw error.inSource(unit.source());
        } catch (ControlSignal.Break | ControlSignal.Continue signal) {
            // Парсер обнуляет счётчик циклов на границе функции, поэтому сюда можно
            // попасть только с деревом, собранным в обход разбора.
            throw new IllegalStateException(
                    "выход из цикла через границу функции: дерево собрано неверно", signal);
        }
        // Тело закончилось без return: функция ничего не вернула, и это null, а не «ничего».
        return NullValue.NULL;
    }

    @Override
    public String display() {
        return declaration.name() != null ? "def " + declaration.name() : "def";
    }

    @Override
    public String toString() {
        return display();
    }
}
