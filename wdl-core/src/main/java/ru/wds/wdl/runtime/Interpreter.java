package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.op.*;
import ru.wds.wdl.ast.stmt.*;
import ru.wds.wdl.ast.visitor.*;
import ru.wds.wdl.metrics.Measure;
import ru.wds.wdl.metrics.Stage;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.resolve.ClassShape;
import ru.wds.wdl.resolve.DeclaredTrait;
import ru.wds.wdl.resolve.LinkError;
import ru.wds.wdl.resolve.Linker;
import ru.wds.wdl.resolve.ScriptTraitShape;
import ru.wds.wdl.resolve.TraitShape;
import ru.wds.wdl.runtime.members.BuiltinMembers;
import ru.wds.wdl.runtime.members.MemberTable;
import ru.wds.wdl.runtime.members.ScriptMember;
import ru.wds.wdl.runtime.members.TypeMemberFunction;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arguments;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Member;
import ru.wds.wdl.value.DecoratorMeta;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Property;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.ModuleValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.RangeValue;
import ru.wds.wdl.value.types.StringValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Интерпретатор: выполняет дерево, полученное от парсера.
 * <p>
 * Это посетитель — выражений ({@link ExprVisitor}) и инструкций ({@link StmtVisitor}).
 * Выполнение живёт здесь, а не в узлах дерева, и разница не косметическая: у узлов нет
 * ни метода {@code eval}, ни ссылки на окружение, поэтому одно и то же дерево можно
 * запускать одновременно в нескольких потоках с разными переменными, кэшировать между
 * запусками и разбирать инструментами, которым до выполнения нет дела. В прошлой
 * реализации {@code eval()} был прямо в узле и тянул за собой контекст программы —
 * дерево оказывалось намертво привязано к одному запуску.
 * <p>
 * Сам интерпретатор состояния не имеет: всё, что нужно вычислению, приходит
 * в {@link ExecutionContext}. Один экземпляр спокойно используется повторно
 * и из разных потоков.
 */
public final class Interpreter
        implements ExprVisitor<Value, ExecutionContext>, StmtVisitor<Void, ExecutionContext> {

    /**
     * Выполняет скрипт целиком.
     * <p>
     * Никакой стадии между разбором и выполнением нет: инструкции выполняются подряд,
     * сверху вниз, и объявление начинает существовать тогда, когда до него дошло
     * выполнение. См. {@link #execute}.
     * <p>
     * Дерево при этом попадает в контекст юнитом — без исходника, потому что его тут
     * и не дали. Нужно оно ровно на пути ошибки: по нему {@link Declarations} отвечает,
     * что имя в файле есть, просто объявлено ниже. Вариант с исходником —
     * {@link #run(Unit, ExecutionContext)}.
     * <p>
     * Готовый юнит при этом не подменяется: {@code Modules} выполняет файл модуля
     * этим же методом, уже поставив его юнит в контекст, — и модуль обязан остаться
     * при своём исходнике и своём ключе.
     *
     * @return значение последней инструкции-выражения файла
     */
    public Value run(Program program, ExecutionContext context) {
        ExecutionContext known = context.unit().program() == null
                ? context.withUnit(Unit.anonymous(program))
                : context;
        return entering(context, () -> execute(program, known));
    }

    /**
     * Открывает выполнение: засчитывает внешний вход в запуск.
     * <p>
     * Это граница движка — сюда управление приходит от приложения. Замка здесь больше
     * нет: внутренности запуска конкурентны, и потоков внутри может быть сколько угодно.
     * Осталось то, ради чего вход вообще считают, — предел на длину цепочки
     * «скрипт → приложение → скрипт» и проверка, что запуск ещё не закрыт. См. {@link Run}.
     */
    private Value entering(ExecutionContext context, Supplier<Value> body) {
        Run run = context.run();
        // Место для сообщения о переполнении — начало файла: до первой инструкции
        // ничего точнее ещё не случилось.
        run.enter(Span.point(0));
        try {
            return body.get();
        } finally {
            run.leave();
        }
    }

    /**
     * Выполняет файл: дерево, формы его классов и его исходник приходят одним
     * {@link Unit юнитом}.
     * <p>
     * Исходник нужен затем, чтобы ошибка выполнения знала, какому файлу принадлежит
     * её место: с импортом файлов много, а смещение в каждом из них указывает на своё.
     * <p>
     * <b>Файл выполняется в своей области</b> поверх переданной, и главный скрипт тут
     * ничем не отличается от модуля. Иначе его переменные оказались бы в той же области,
     * что служит модулям корнем, и модуль видел бы имена того, кто его импортирует, —
     * а значит, работал бы по-разному в зависимости от места импорта.
     * <p>
     * Поэтому наружу отдаётся и сама эта область: имена, объявленные файлом, живут
     * в ней, и без неё приложение не достало бы из скрипта ни функции-обработчика,
     * ни класса. См. {@link Execution}.
     */
    public Execution run(Unit unit, ExecutionContext context) {
        ExecutionContext file = context.nested().withUnit(unit);
        Value result = entering(context, () -> {
            try {
                return execute(unit.program(), file);
            } catch (WdlError error) {
                throw error.inSource(unit.source());
            }
        });
        // Область файла отдаётся наружу вместе с результатом: без неё приложение
        // не добралось бы до того, что скрипт объявил, — см. Execution.
        return new Execution(result, file);
    }

    /**
     * Выполняет инструкции файла и отвечает его результатом.
     * <p>
     * <b>Результат файла — значение его последней инструкции-выражения.</b> Правило
     * то же, что у тела функции из одного выражения, и выбрано оно по той же причине,
     * по которой {@code return} на верхнем уровне запрещён разбором: файл — это ещё
     * и модуль, а у модуля значение уже есть ({@code ModuleValue}), и второй смысл
     * у того же {@code return} завёл бы два ответа на один вопрос.
     * <pre>{@code
     * def total(price, count) => price * count
     * total(120, 3)      // ← результат файла: 360
     * }</pre>
     * Ни одной инструкции-выражения в файле нет — результат {@code null}, как у функции,
     * дошедшей до конца тела без {@code return}. Значение считает только тот, кто
     * запустил файл: {@code Modules} его игнорирует, ему нужен модуль, а не число.
     */
    private Value execute(Program program, ExecutionContext running) {
        // Здесь и меряется стадия EXECUTE — на верхнем уровне файла, а не на каждом
        // входе в скрипт: вызов функции скрипта из приложения — тоже вход, и считать
        // его стадией конвейера значило бы складывать несравнимое. Место одно на всех:
        // и главный скрипт, и модуль, и строка REPL приходят сюда.
        Unit unit = running.unit();
        Measure measure = running.metrics().begin(Stage.EXECUTE, subjectOf(unit),
                unit.key() != null);
        try {
            return statements(program, running);
        } finally {
            // Из finally: время скрипта, упавшего на середине, — тоже ответ.
            measure.close();
        }
    }

    /** Над чем работала стадия: ключ модуля, имя файла или «безымянно» у REPL. */
    private static String subjectOf(Unit unit) {
        if (unit.key() != null) {
            return unit.key();
        }
        return unit.source() != null ? unit.source().name() : "<script>";
    }

    private Value statements(Program program, ExecutionContext running) {
        // Верхний уровень файла — тоже область: 'defer' на нём выполняется, когда файл
        // дочитан, чем бы он ни кончился.
        Deferred pending = new Deferred();
        ExecutionContext scoped = running.withDeferred(pending);
        RuntimeException flying = null;
        Value result = NullValue.NULL;
        try {
            for (Stmt statement : program.statements()) {
                // Инструкция-выражение вычисляется здесь, а не через visitExprStmt,
                // ровно затем, чтобы её значение не потерялось: посетитель инструкций
                // возвращает Void, и менять это ради одного случая — платить правкой
                // всех реализаций за то, что нужно только на верхнем уровне файла.
                if (statement instanceof ExprStmt expression) {
                    result = valueOf(expression.expr(), scoped);
                } else {
                    visit(statement, scoped);
                }
            }
        } catch (ControlSignal signal) {
            // break, continue или return вне своей конструкции. Парсер такое не пропускает,
            // поэтому сюда можно попасть только с деревом, собранным в обход разбора, —
            // то есть из-за ошибки в движке, а не в скрипте.
            throw new IllegalStateException(
                    "сигнал управления вне цикла или функции: дерево собрано неверно", signal);
        } catch (StackOverflowError e) {
            // Вторая линия защиты от рекурсии, и ловится она только на границе выполнения,
            // где стек уже раскручен: собирать сообщение в тот момент, когда стека нет, —
            // верный способ получить второе переполнение вместо диагностики. Отложенное
            // при этом не выполняется: стека на него всё равно нет.
            throw FatalError.stackExhausted();
        } catch (RuntimeException error) {
            flying = error;
        }
        flying = runDeferred(pending, flying, scoped);
        if (flying != null) {
            throw flying;
        }
        return result;
    }

    /**
     * Вычисляет выражение. Ошибки скрипта прилетают как {@link WdlRuntimeError}
     * с местом в исходнике.
     * <p>
     * Это внешняя точка входа — сюда приходят REPL и {@code engine.eval("a + b")}.
     * Внутри дерева интерпретатор пользуется {@link #valueOf}: страховка от исчерпания
     * стека имеет смысл только на границе, где стек уже раскручен.
     */
    public Value eval(Expr expr, ExecutionContext context) {
        return entering(context, () -> evaluate(expr, context));
    }

    private Value evaluate(Expr expr, ExecutionContext context) {
        try {
            return visit(expr, context);
        } catch (StackOverflowError e) {
            // Вторая линия защиты от рекурсии, и ловится она только на границе выполнения,
            // где стек уже раскручен: собирать сообщение в тот момент, когда стека нет, —
            // верный способ получить второе переполнение вместо диагностики.
            throw FatalError.stackExhausted();
        }
    }

    /** Вычисление выражения внутри дерева — без страховок, их место на границе. */
    private Value valueOf(Expr expr, ExecutionContext context) {
        return visit(expr, context);
    }

    // --- инструкции ----------------------------------------------------------

    @Override
    public Void visitExprStmt(ExprStmt stmt, ExecutionContext context) {
        valueOf(stmt.expr(), context);
        return null;
    }

    /**
     * Присваивание.
     * <p>
     * Сначала вычисляется <b>место</b> записи, и только один раз: в
     * {@code таблица[ключ()] += 1} функция {@code ключ()} обязана вызваться однократно.
     * Поэтому составное присваивание и не разворачивается в {@code a = a + b} на этапе
     * разбора — операция берётся из {@link AssignStmt#op()} уже здесь.
     * <p>
     * Простое присваивание в имя, которого ещё нет, заводит переменную в текущей
     * области видимости: объявлять её незачем. Существующее имя присваивается там,
     * где оно объявлено, — вложенная область не создаёт себе копию.
     * <p>
     * Единственное имя, которому присваивание не проходит, — объявленное через
     * {@code const} (см. {@link #visitConstDecl} и {@link VariablePlace#write}).
     */
    @Override
    public Void visitAssign(AssignStmt stmt, ExecutionContext context) {
        Place place = resolvePlace(stmt.target(), context);
        Value value = valueOf(stmt.value(), context);
        if (stmt.op().isCompound()) {
            BinaryOp operation = stmt.op().base();
            value = binary(operation, place.read(), value, stmt.span(), context);
        }
        place.write(value);
        return null;
    }

    /**
     * Распаковка: {@code x, y = *point}, {@code host, port, **rest = **config},
     * {@code a, b = b, a}.
     * <p>
     * <b>Порядок здесь и есть смысл конструкции.</b> Сначала вычисляется правая часть,
     * потом значения раскладываются по целям — и только после этого начинается запись.
     * Отсюда само собой получается и обмен {@code a, b = b, a}, и то, что ошибка
     * на середине не оставляет половину имён перезаписанными: писать нечего, пока
     * не готово всё.
     * <p>
     * Источник вычисляется <b>ровно один раз</b>: маркер относится ко всему выражению,
     * а не к первому его звену, поэтому {@code x, y = *reacts[i].center()} — это один
     * вызов, а не по вызову на имя.
     * <p>
     * Что значат {@code *} и {@code **}, знает {@link Unpack} и только он. Куда писать
     * — {@link #resolvePlace}, тот же самый, что у обычного присваивания: своего
     * понятия цели у распаковки нет.
     */
    @Override
    public Void visitUnpack(UnpackStmt stmt, ExecutionContext context) {
        List<Value> sources = new ArrayList<>(stmt.sources().size());
        for (Argument source : stmt.sources()) {
            sources.add(valueOf(source.value(), context));
        }
        List<Value> values = switch (stmt.style()) {
            case POSITIONAL -> Unpack.positional(stmt, sources.get(0), stmt.source().span());
            case NAMED -> Unpack.named(stmt, sources.get(0), stmt.source().span());
            case PAIRWISE -> Unpack.pairwise(stmt, sources, stmt.span());
        };

        // Места считаются отдельным проходом и тоже до записи: 'grid[next()], x = *pair'
        // обязано звать next() один раз и до того, как что-нибудь изменится.
        List<Place> places = new ArrayList<>(stmt.targets().size());
        for (UnpackTarget target : stmt.targets()) {
            places.add(target.writes() ? resolvePlace(target.target(), context) : null);
        }
        for (int i = 0; i < places.size(); i++) {
            if (places.get(i) != null) {
                places.get(i).write(values.get(i));
            }
        }
        return null;
    }

    /**
     * Блок создаёт область видимости — как и вызов функции ({@link UserFunction}),
     * и по тому же самому правилу.
     * <p>
     * Правило стоит держать в голове, читая скрипт: имя, впервые присвоенное внутри,
     * снаружи не существует, а присваивание уже известному имени уходит туда, где оно
     * заведено (см. {@link VariablePlace#write}). Тело из одной инструкции без скобок
     * своей области не заводит — там просто нет блока.
     */
    @Override
    public Void visitBlock(BlockStmt stmt, ExecutionContext context) {
        ExecutionContext inner = context.nested();
        if (!stmt.hasDefer()) {
            for (Stmt statement : stmt.statements()) {
                visit(statement, inner);
            }
            return null;
        }

        // Блок с отложенным: список заводится только здесь, потому что блоков в скрипте
        // тысячи, а блоков с 'defer' — единицы. Знает об этом разбор, а не выполнение.
        Deferred pending = new Deferred();
        ExecutionContext scoped = inner.withDeferred(pending);
        RuntimeException flying = null;
        try {
            for (Stmt statement : stmt.statements()) {
                visit(statement, scoped);
            }
        } catch (RuntimeException exit) {
            // Любой выход — нормальный, через return, break, continue, ошибку
            // или остановку выполнения: отложенное выполняется при всех.
            flying = exit;
        }
        flying = runDeferred(pending, flying, scoped);
        if (flying != null) {
            throw flying;
        }
        return null;
    }

    @Override
    public Void visitIf(IfStmt stmt, ExecutionContext context) {
        if (valueOf(stmt.condition(), context).isTruthy()) {
            visit(stmt.thenBranch(), context);
        } else if (stmt.hasElse()) {
            visit(stmt.elseBranch(), context);
        }
        return null;
    }

    @Override
    public Void visitWhile(WhileStmt stmt, ExecutionContext context) {
        while (valueOf(stmt.condition(), context).isTruthy()) {
            checkInterrupted(stmt.span());
            if (runLoopBody(stmt.body(), context)) {
                break;
            }
        }
        return null;
    }

    /**
     * Цикл со счётчиком.
     * <p>
     * Инициализатор, условие и шаг живут в собственной области видимости цикла:
     * {@code i} из {@code for (i = 0; ...)} снаружи не виден и не мешает следующему
     * циклу с таким же именем. Отсутствующее условие — это {@code null}, и читается
     * оно как «повторять всегда»: {@code for (;;)}.
     */
    @Override
    public Void visitFor(ForStmt stmt, ExecutionContext context) {
        ExecutionContext loop = context.nested();
        if (stmt.init() != null) {
            visit(stmt.init(), loop);
        }
        while (stmt.condition() == null || valueOf(stmt.condition(), loop).isTruthy()) {
            checkInterrupted(stmt.span());
            if (runLoopBody(stmt.body(), loop)) {
                break;
            }
            // Шаг выполняется и после continue. Пропускать его — самый простой способ
            // превратить обычный цикл в вечный, и язык так делать не станет.
            if (stmt.step() != null) {
                visit(stmt.step(), loop);
            }
        }
        return null;
    }

    /**
     * Перебор массива, строки или объекта.
     * <p>
     * По массиву идём по индексу с заранее снятой длиной, по объекту — по снимку ключей.
     * Причина одна: тело цикла имеет полное право менять то, что перебирают, и получить
     * за это {@code ConcurrentModificationException} из внутренностей Java автор скрипта
     * не должен. Добавленное во время перебора в этот проход не попадёт, удалённое —
     * не сломает.
     * <p>
     * У объекта перебираются <b>ключи</b>: значение по ключу всегда рядом ({@code o[к]}),
     * а обратной операции не существует.
     */
    @Override
    public Void visitForEach(ForEachStmt stmt, ExecutionContext context) {
        Value iterable = valueOf(stmt.iterable(), context);
        switch (iterable) {
            case ArrayValue array -> {
                int size = array.size();
                for (int i = 0; i < size && i < array.size(); i++) {
                    if (iteration(stmt, context, IntValue.of(i), array.get(i))) {
                        return null;
                    }
                }
            }
            case StringValue string -> {
                String text = string.value();
                for (int i = 0; i < text.length(); i++) {
                    if (iteration(stmt, context, IntValue.of(i),
                            StringValue.of(String.valueOf(text.charAt(i))))) {
                        return null;
                    }
                }
            }
            // У объекта с одним именем перебираются ключи, а не значения, — так было
            // и так остаётся: значение по ключу всегда рядом (o[k]), а обратной
            // операции не существует. Второе имя ничего в этом не меняет, оно лишь
            // избавляет от второй строки: 'for (k, v in o)' — это тот же ключ плюс o[k].
            case MapValue object -> {
                for (Value key : List.copyOf(object.entries().keySet())) {
                    if (iteration(stmt, context, key,
                            stmt.withKey() ? object.get(key) : key)) {
                        return null;
                    }
                }
            }
            // Диапазон перебирается шагом в единицу, поэтому границы обязаны быть
            // целыми: у '0.5..2.5' нет ответа на вопрос, какие числа он содержит
            // «по одному», а выдумывать его за автора незачем.
            case RangeValue range -> {
                if (stmt.withKey()) {
                    // Первое имя — то, чем обращаются, а диапазон по ключу не читается
                    // вовсе: у него только члены. Номер прохода здесь и есть значение.
                    throw new WdlRuntimeError(ErrorKind.TYPE, stmt.key().span(),
                            "у диапазона нет ключа: номер прохода здесь и есть значение —"
                                    + " оставьте одно имя, '" + stmt.value() + "'");
                }
                if (!range.from().isInteger() || !range.to().isInteger()) {
                    throw new WdlRuntimeError(ErrorKind.TYPE, stmt.iterable().span(),
                            "перебрать можно диапазон с целыми границами, а здесь " + range
                                    + ": шаг перебора равен единице. Проверить принадлежность"
                                    + " такому диапазону можно и так: 'x in " + range + "'");
                }
                long to = range.to().asLong();
                // Пустой диапазон (5..1) даёт ноль проходов — это и есть ответ
                // для 'for (i in 0..n - 1)' при n == 0.
                for (long i = range.from().asLong(); i <= to; i++) {
                    if (iteration(stmt, context, null, IntValue.of(i))) {
                        return null;
                    }
                    if (i == Long.MAX_VALUE) {
                        // Инкремент завернул бы счётчик и сделал цикл вечным.
                        break;
                    }
                }
            }
            default -> throw new WdlRuntimeError(ErrorKind.TYPE, stmt.iterable().span(),
                    "перебрать можно массив, строку, объект или диапазон, а здесь "
                            + iterable.type().title() + " (" + iterable + ")");
        }
        return null;
    }

    @Override
    public Void visitBreak(BreakStmt stmt, ExecutionContext context) {
        throw ControlSignal.Break.INSTANCE;
    }

    @Override
    public Void visitContinue(ContinueStmt stmt, ExecutionContext context) {
        throw ControlSignal.Continue.INSTANCE;
    }

    /**
     * Объявление функции.
     * <p>
     * Имя <b>заводится</b> в текущей области, а не присваивается по цепочке наружу:
     * объявление на то и объявление. Разница видна там, где одноимённая переменная
     * есть снаружи, — {@code def} внутри функции или блока не портит внешнее имя,
     * в отличие от присваивания {@code имя = def(...)}.
     */
    @Override
    public Void visitDefDecl(DefDeclStmt stmt, ExecutionContext context) {
        checkNotConstant(stmt.name(), stmt.span(), context);
        context.scope().define(stmt.name(), valueOf(stmt.function(), context));
        return null;
    }

    /**
     * Объявление под декораторами: {@code @[timer]("ms") def command() { ... }}.
     * <p>
     * Порядок здесь весь смысл конструкции. Сначала строится значение — то же самое,
     * какое построило бы объявление само по себе, — но <b>имя не заводится</b>.
     * Потом значение проходит через декораторы снизу вверх: ближайший к {@code def}
     * получает саму цель, следующий — то, что вернул предыдущий. И только результат
     * последнего попадает в область видимости.
     * <p>
     * Отсюда правило записи, которое стоит знать заранее: не оборачивающие декораторы
     * ставятся ближе к {@code def}, чем оборачивающие. Наблюдатель наверху стека увидит
     * не цель, а анонимную обёртку — и будет прав, под именем в тот момент лежит она.
     * <p>
     * <b>Декоратор выполняется каждый раз, когда исполнение проходит через объявление.</b>
     * Стадии между разбором и выполнением в языке нет, поэтому {@code def} внутри
     * функции декорируется на каждом её вызове. Это не оговорка, а то же правило
     * «имя существует с той строки, где его завели», просто применённое к декоратору.
     * <p>
     * <b>Декорированный класс не переиспользуется.</b> {@link #classOf} возвращает
     * прежнее значение, если под именем уже лежит класс той же формы; под декорированным
     * именем лежит результат декоратора, и совпасть ему не с чем. То есть повторное
     * прохождение объявления даёт новый класс, а объект из прошлого прохода перестаёт
     * быть {@code is} этому классу. Чинить нечего: декоратор волен вернуть что угодно
     * и выполняется заново — переиспользовать здесь просто нечего.
     */
    @Override
    public Void visitDecorated(DecoratedStmt stmt, ExecutionContext context) {
        String name = stmt.name();
        checkNotConstant(name, stmt.declaration().span(), context);

        Value value = undecorated(stmt.declaration(), context);
        List<Decorator> decorators = stmt.decorators();
        for (int i = decorators.size() - 1; i >= 0; i--) {
            value = apply(decorators.get(i), value, context);
        }
        context.scope().define(name, value);
        return null;
    }

    /**
     * Значение, которое объявление построило бы само по себе, — но без записи имени.
     * <p>
     * Ради этого и разведены «построить» и «завести»: у декорированного объявления
     * имя заводится один раз и уже обвешанным значением. Промежуточной записи быть
     * не должно — иначе между ней и итоговой существовало бы окно, в котором под
     * именем лежит цель, а не то, что просил автор.
     */
    private Value undecorated(Stmt declaration, ExecutionContext context) {
        return switch (declaration) {
            case DefDeclStmt declared -> valueOf(declared.function(), context);
            case ClassDeclStmt declared -> classOf(declared, context);
            case TraitDeclStmt declared -> traitOf(declared, context);
            // Разбор сюда ничего другого не пропускает: парсер принимает после '@[...]'
            // только объявление, а неразобранное объявление до выполнения не доживает.
            default -> throw new IllegalStateException("не объявление под декоратором: " + declaration);
        };
    }

    /**
     * Один шаг: зовёт декоратор, подставив метаданные нулевым аргументом.
     * <p>
     * <b>Цель приходит позицией, а не особым каналом.</b> Поэтому попытка задать её
     * своим именем — {@code @[deco](meta: 5)} при {@code def deco(meta, **opts)} —
     * даёт обычную ошибку «параметр передан дважды»: связыватель видит позицию,
     * занятую дважды, и писать для этого случая отдельное сообщение не нужно.
     * <p>
     * {@code null} в ответе оставляет цель прежней. Значит, декоратору не обязательно
     * заканчиваться возвратом — регистрирующему возвращать нечего, — и заменить цель
     * на {@code null} нельзя. Второе не потеря: имя со значением {@code null}
     * ни вызвать, ни создать.
     */
    private Value apply(Decorator decorator, Value target, ExecutionContext context) {
        Value callee = valueOf(decorator.callee(), context);
        if (!(callee instanceof FunctionValue function)) {
            throw new WdlRuntimeError(ErrorKind.CALL, decorator.callee().span(),
                    "декоратором может быть только функция, а здесь "
                            + callee.type().title() + " (" + callee + ")");
        }

        List<Argument> written = decorator.arguments();
        List<Argument> all = new ArrayList<>(written.size() + 1);
        all.add(Argument.positional(new LiteralExpr(DecoratorMeta.of(target), decorator.span())));
        all.addAll(written);

        List<Value> values = evaluate(all, context);
        Arguments arguments;
        if (Binder.needed(function.signature(), all)) {
            arguments = Binder.bind(function.signature(), all, values,
                    Binder.Callee.function(function.name()), decorator.span());
        } else {
            if (!function.arity().accepts(values.size())) {
                throw new WdlRuntimeError(ErrorKind.CALL, decorator.span(), "декоратор '"
                        + function.name() + "' принимает " + function.arity().describeArguments()
                        + " вместе с метаданными, а передано " + values.size());
            }
            arguments = Arguments.positional(values);
        }

        Value replacement = function.call(context, arguments, decorator.span());
        return replacement == null || replacement.type() == ValueType.NULL ? target : replacement;
    }

    /**
     * Объявление константы: {@code const LIMIT = 10}.
     * <p>
     * Имя заводится в текущей области — как у {@code def} и {@code class}, — но
     * дополнительно замораживается: присваивание ему не пройдёт ни отсюда, ни из
     * вложенной области, ни из замыкания. Заморожено при этом имя, а не значение:
     * {@code const items = [1, 2]} запрещает {@code items = [3]}, но не {@code items[0] = 5}.
     * <p>
     * До выполнения константа не помечается, в отличие от функции: у неё есть
     * выражение-инициализатор, и вычислять его до первой инструкции скрипта значило бы
     * завести вторую, невидимую фазу выполнения — в неопределённом порядке относительно
     * остальных констант. Поэтому функция, объявленная выше, константу увидит, но только
     * если вызвана после её объявления.
     */
    @Override
    public Void visitConstDecl(ConstDeclStmt stmt, ExecutionContext context) {
        // Проверка до вычисления: выполнять инициализатор заведомо неверного объявления незачем.
        checkNotConstant(stmt.name(), stmt.nameSpan(), context);
        context.scope().defineConstant(stmt.name(), valueOf(stmt.value(), context));
        return null;
    }

    /**
     * Объявление не перекрывает константу: имя, замороженное в <b>этой</b> области,
     * занято окончательно.
     * <p>
     * Иначе {@code const A = 1} и {@code def A() {}} ниже разошлись бы молча, и что
     * означает {@code A}, зависело бы от строки. Смотреть наружу нельзя: вложенная область
     * вправе объявить своё имя, и это затенение, а не переопределение.
     * <p>
     * Обратный порядок ошибки не даёт — {@code const A = 1} после {@code def A() {}}
     * перекрывает функцию, ровно как это делает обычное {@code A = 1}. Асимметрия
     * сознательная: объявление заводит имя поверх прежнего, а константа запрещает
     * переопределение только после себя, — читатель скрипта видит то же, что и движок,
     * сверху вниз.
     */
    private static void checkNotConstant(String name, Span span, ExecutionContext context) {
        if (context.scope().isConstantHere(name)) {
            throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "'" + name + "' нельзя объявить: в этой области уже есть"
                    + " константа с таким именем, а её значение задаётся один раз."
                    + " Перекрыть константу можно только во вложенной области");
        }
    }

    /**
     * Объявление класса: имя заводится в текущей области, как и у функции.
     * <p>
     * Здесь же класс и <b>связывается</b>: родитель с трейтами ищутся среди значений,
     * видимых в этой точке, и {@link Linker} собирает по ним форму — плоские таблицы
     * плюс проверки требований трейтов и числа аргументов родителю. Всё, что для этого
     * нужно, уже стоит в области видимости — поэтому родитель может прийти из модуля,
     * которого секунду назад не было на диске, и отдельная стадия до запуска не нужна.
     * <p>
     * Значение собирается из формы и текущей области — она станет замыканием методов.
     * Поэтому класс, объявленный внутри функции, на каждом вызове даёт новое значение,
     * но для {@code is} остаётся тем же классом: форма-то одна, её держит кэш линкера.
     */
    @Override
    public Void visitClassDecl(ClassDeclStmt stmt, ExecutionContext context) {
        classOf(stmt, context);
        return null;
    }

    /**
     * Расширение типа или класса: {@code extend Array { ... }}.
     * <p>
     * <b>Член появляется тогда, когда выполнился {@code extend}</b>, — то же правило,
     * что у всякого имени в языке. «Верхний уровень файла», который требует разбор,
     * ограничивает место в тексте, но не момент во времени: {@code import} законен
     * в теле функции и в потоке, а выполнение модуля — это выполнение его верхнего
     * уровня. Регистрировать расширения заранее значило бы вернуть стадию подготовки,
     * убранную ради ленивой загрузки модулей; поэтому таблица конкурентна, а правило
     * остаётся одно на весь язык.
     * <p>
     * <b>Расширение принадлежит запуску, а не файлу.</b> Объявили в модуле — работает
     * везде в этом запуске, но не в соседнем интерпретаторе: таблица живёт в
     * {@link Run}, статики у неё нет.
     */
    @Override
    public Void visitExtend(ExtendStmt stmt, ExecutionContext context) {
        Value target = valueOf(stmt.target(), context);
        if (!(target instanceof ClassValue declared)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, stmt.target().span(), "расширять можно тип или класс, а '"
                    + stmt.label() + "' — это " + target.type().title() + " (" + target + ")");
        }
        Object key = declared.memberKey();
        List<String> reserved = reserved(declared);
        MemberTable members = context.run().members();
        for (FunctionExpr method : stmt.methods()) {
            members.declare(key, declared.name(),
                    ScriptMember.method(method, context.scope(), context.unit(), context.run(), this),
                    reserved, method.span());
        }
        for (PropertyDecl property : stmt.properties()) {
            if (property.getter() == null || property.getter().function() == null) {
                throw new WdlRuntimeError(ErrorKind.DECLARATION, property.span(), "свойство '"
                        + property.name() + "' объявлено без чтения: требований в расширении не бывает, "
                        + "их проверять некому");
            }
            if (property.hasSetter()) {
                throw new WdlRuntimeError(ErrorKind.DECLARATION, property.span(), "у свойства '"
                        + property.name() + "' не бывает 'def set(value)': запись в член типа — "
                        + "действие, замаскированное под имя, а девать её вдобавок некуда");
            }
            members.declare(key, declared.name(),
                    ScriptMember.property(property, context.scope(), context.unit(), context.run(), this),
                    reserved, property.span());
        }
        return null;
    }

    /**
     * Имена, которые у цели уже заняты помимо таблицы расширений.
     * <p>
     * У типа это его набор ядра, у класса — вдобавок его собственные методы и свойства
     * и члены объекта: экземпляр это {@code object}, и {@code extend Point} с именем
     * {@code keys} перекрыл бы член, которым пользуется чужой код.
     */
    private static List<String> reserved(ClassValue declared) {
        List<String> reserved = new ArrayList<>();
        if (declared instanceof TypeValue descriptor) {
            reserved.addAll(BuiltinMembers.of(descriptor.valueType()).names());
            return reserved;
        }
        reserved.addAll(declared.methodNames());
        reserved.addAll(declared.propertyNames());
        reserved.addAll(BuiltinMembers.of(ValueType.OBJECT).names());
        return reserved;
    }

    @Override
    public Void visitTraitDecl(TraitDeclStmt stmt, ExecutionContext context) {
        traitOf(stmt, context);
        return null;
    }

    /**
     * Значение класса — то, что уже лежит под этим именем, или новое.
     * <p>
     * Повторное объявление не создаёт второе значение не из экономии: помеченный
     * до выполнения класс и класс, созданный заново на своей же инструкции, были бы
     * двумя разными значениями, и потомок держал бы ссылку на первое, а имя указывало
     * бы на второе. Тогда {@code c is Shape} давало бы ложь при совершенно правильном
     * скрипте. Сравнение идёт по форме: она одна ровно тогда, когда класс связан
     * тем же родителем и теми же трейтами.
     */
    private WdlClass classOf(ClassDeclStmt stmt, ExecutionContext context) {
        WdlClass parent = parentOf(stmt, context);
        List<TraitValue> traits = mixinsOf(stmt, context);
        ClassShape shape = shapeOf(stmt, parent, traits, context);

        if (context.scope().lookup(stmt.name()) instanceof WdlClass existing
                && existing.shape() == shape) {
            return existing;
        }
        // Значению класса нужны только трейты на wdl: от них достаются методы
        // и значения полей, которые ещё предстоит вычислить. Нативные трейты
        // целиком описаны формой, и в значении им делать нечего.
        List<WdlTrait> scripted = new ArrayList<>(traits.size());
        for (TraitValue trait : traits) {
            if (trait instanceof WdlTrait declaredTrait) {
                scripted.add(declaredTrait);
            }
        }
        // Аннотации считаются здесь, а не раньше: выше стоит проверка на повторное
        // использование значения, и у прежнего класса объект уже есть — считать
        // нечего и не для чего.
        TypeAnnotations annotations = typeAnnotations(stmt.annotations(), stmt.params(),
                stmt.methods(), stmt.properties(), stmt.factories(), context);
        WdlClass declared = new WdlClass(shape, context.scope(), context.unit(), parent, scripted,
                context.run(), this, annotations);
        installFactories(declared, annotations, context);
        checkNotConstant(stmt.name(), stmt.nameSpan(), context);
        context.scope().define(stmt.name(), declared);
        return declared;
    }

    private WdlTrait traitOf(TraitDeclStmt stmt, ExecutionContext context) {
        ScriptTraitShape shape = context.linker().traitShape(stmt);
        if (context.scope().lookup(stmt.name()) instanceof WdlTrait existing
                && existing.shape() == shape) {
            return existing;
        }
        WdlTrait declared = new WdlTrait(shape, context.scope(), context.unit(),
                typeAnnotations(stmt.annotations(), stmt.params(), stmt.methods(),
                        stmt.properties(), List.of(), context));
        checkNotConstant(stmt.name(), stmt.nameSpan(), context);
        context.scope().define(stmt.name(), declared);
        return declared;
    }

    /**
     * Собирает форму класса. Ошибка связывания — обычная ошибка скрипта: место у неё
     * есть, а стадия человека не интересует.
     */
    private ClassShape shapeOf(ClassDeclStmt stmt, WdlClass parent, List<TraitValue> traits,
                               ExecutionContext context) {
        List<TraitShape> mixins = new ArrayList<>(traits.size());
        for (TraitValue trait : traits) {
            // Приведение безопасно: в список попадают только трейты с формой —
            // это проверил mixinsOf, и других сюда не доходит.
            mixins.add(((DeclaredTrait) trait).shape());
        }
        try {
            return context.linker().classShape(stmt, parent == null ? null : parent.shape(), mixins);
        } catch (LinkError error) {
            throw new WdlRuntimeError(ErrorKind.DECLARATION, error.span(), error.getMessage());
        }
    }

    /**
     * Родитель: значение, видимое в этой точке под своим именем.
     * <p>
     * Именно значение, а не форма из таблицы разбора, — на этом и держится
     * наследование через файлы. {@code Shape} после {@code import lib.shapes} — то же
     * имя в той же области, что и любое другое, поэтому никакого особого случая для
     * модулей здесь нет: работает и развёрнутый импорт, и {@code m.Shape}, и класс,
     * объявленный рядом.
     */
    private WdlClass parentOf(ClassDeclStmt stmt, ExecutionContext context) {
        ClassDeclStmt.Superclass parent = stmt.parent();
        if (parent == null) {
            return null;
        }
        Value value = typeValue(parent.type(), parent.title(), "класс", parent.span(), context);
        if (value instanceof WdlClass klass) {
            return klass;
        }
        if (value instanceof TraitValue) {
            throw new WdlRuntimeError(ErrorKind.DECLARATION, parent.span(), "'" + parent.title() + "' — трейт, а не класс: "
                    + "трейт подмешивается через 'with', наследуются от класса");
        }
        if (value instanceof ClassValue) {
            // Класс от приложения (bridge.NativeClass): его поля и методы живут в Java,
            // и плоскую таблицу по ним не собрать.
            throw new WdlRuntimeError(ErrorKind.DECLARATION, parent.span(), "'" + parent.title() + "' — встроенный класс: "
                    + "наследоваться можно только от класса, объявленного на wdl");
        }
        throw new WdlRuntimeError(ErrorKind.DECLARATION, parent.span(), "наследоваться можно только от класса, а '"
                + parent.title() + "' — это " + value.type().title() + " (" + value + ")");
    }

    /**
     * Подмешанные трейты — тем же правилом, что и родитель.
     * <p>
     * Трейт языка и трейт от приложения равноправны: у обоих есть форма
     * ({@link DeclaredTrait}), а требования обоих проверяет один и тот же
     * {@code Linker}. Разница появится дальше, при сборке значения класса,
     * и автору скрипта не видна.
     */
    private List<TraitValue> mixinsOf(ClassDeclStmt stmt, ExecutionContext context) {
        List<TraitValue> traits = new ArrayList<>(stmt.traits().size());
        for (ClassDeclStmt.TraitRef reference : stmt.traits()) {
            Value value = typeValue(reference.type(), reference.title(), "трейт",
                    reference.span(), context);
            if (value instanceof DeclaredTrait) {
                traits.add((TraitValue) value);
                continue;
            }
            if (value instanceof ClassValue) {
                throw new WdlRuntimeError(ErrorKind.DECLARATION, reference.span(), "'" + reference.title()
                        + "' — класс, а не трейт: подмешать можно только трейт,"
                        + " у класса есть конструктор");
            }
            if (value instanceof TraitValue) {
                // Чужая реализация TraitValue: формы у неё нет, а значит и связывать
                // класс нечем. Трейт от приложения объявляется построителем NativeTrait.
                throw new WdlRuntimeError(ErrorKind.DECLARATION, reference.span(), "трейт '"
                        + reference.title() + "' объявлен не построителем NativeTrait:"
                        + " подмешать можно трейт языка или трейт, собранный им");
            }
            throw new WdlRuntimeError(ErrorKind.DECLARATION, reference.span(), "подмешать можно только трейт, а '"
                    + reference.title() + "' — это " + value.type().title() + " (" + value + ")");
        }
        return traits;
    }

    /**
     * Значение ссылки на тип в заголовке класса.
     * <p>
     * Ссылка — обычное выражение, и вычисляется она обычным образом: {@code m.Shape}
     * и {@code registry.classes["Shape"]} проходят тем же кодом, что и любое
     * обращение по ключу. Особого синтаксиса для модулей не понадобилось и здесь.
     * <p>
     * Собственных сообщений при этом два, и оба про <b>ненайденное имя</b> —
     * то есть про случай, когда вычислять уже нечего. Общее «переменная не определена»
     * здесь хуже своего: человек написал заголовок класса, и подсказка нужна про
     * заголовок класса — что связывать можно объявленное выше или импортированное.
     * Точное место объявления и круг в наследовании добавляет {@link Declarations}.
     * Дальше по цепочке подсказывать уже нечего, и работает обычная диагностика
     * обращения: «у модуля нет имени X» точнее всего, что можно придумать отсюда.
     */
    private Value typeValue(Expr type, String title, String what, Span span,
                            ExecutionContext context) {
        if (type instanceof VariableExpr variable) {
            Value value = context.scope().lookup(variable.name());
            if (value == null) {
                String hint = Declarations.typeHint(variable.name(), context);
                throw new WdlRuntimeError(ErrorKind.NAME, span, "неизвестный " + what + " '" + title
                        + "'" + (hint.isEmpty()
                        ? ": наследоваться и подмешивать можно то, что объявлено в этом же"
                        + " файле или импортировано выше по тексту"
                        : hint));
            }
            return value;
        }
        // Не найдено самое левое имя цепочки — почти всегда это забытый или неверно
        // названный импорт, и сказать про него надо раньше, чем про обращение к null.
        if (root(type) instanceof VariableExpr base && context.scope().lookup(base.name()) == null) {
            throw new WdlRuntimeError(ErrorKind.NAME, span, "неизвестный " + what + " '" + title
                    + "': проверьте, что выше есть 'import ... as " + base.name()
                    + "' и что в том модуле объявлен этот тип");
        }
        return valueOf(type, context);
    }

    /** Самое левое звено цепочки обращений и вызовов: {@code a} у {@code a.b()[0]}. */
    private static Expr root(Expr type) {
        Expr current = type;
        while (true) {
            switch (current) {
                case AccessExpr access -> current = access.target();
                case CallExpr call -> current = call.callee();
                default -> {
                    return current;
                }
            }
        }
    }

    /**
     * Значение имени типа в обработчике {@code catch}: простого или квалифицированного.
     * <p>
     * Отдельно от заголовка класса: там ссылка — выражение, а здесь имя, и вычислять
     * цепочку с вызовами на пути обработки уже случившейся ошибки язык не станет.
     */
    private Value typeValue(String alias, String name, String title, String what,
                            Span span, ExecutionContext context) {
        if (alias == null) {
            Value value = context.scope().lookup(name);
            if (value == null) {
                throw new WdlRuntimeError(ErrorKind.NAME, span, "неизвестный " + what + " '" + title
                        + "': наследоваться и подмешивать можно то, что объявлено в этом же"
                        + " файле или импортировано выше по тексту");
            }
            return value;
        }
        Value module = context.scope().lookup(alias);
        if (module == null) {
            throw new WdlRuntimeError(ErrorKind.NAME, span, "неизвестный " + what + " '" + title
                    + "': проверьте, что выше есть 'import ... as " + alias
                    + "' и что в том модуле объявлен этот тип");
        }
        return read(module, StringValue.of(name), AccessStyle.DOT, span, context);
    }

    /**
     * Фабрики кладутся в сам класс: {@code def User.of(...)} — это место записи,
     * а не особый вид члена, и снаружи ровно то же самое делает присваивание
     * {@code User.of = def(...)}.
     */
    private void installFactories(WdlClass declared, TypeAnnotations annotations,
                                  ExecutionContext context) {
        for (ClassDeclStmt.Factory factory : declared.shape().factories()) {
            // Фабрика — функция на классе, а не метод: экземпляра ещё нет, значит
            // и замка экземпляра быть не может. Замок у неё свой, как у обычной функции.
            declared.statics().put(factory.name(), new UserFunction(factory.function(),
                    declared.closure(), declared.unit(), context.run(), this,
                    factory.function().isSynchronized() ? new ReentrantLock() : null,
                    annotations.method(factory.function().memberName())));
        }
    }

    /**
     * Импорт модуля.
     * <p>
     * Модуль выполняется (или достаётся из реестра, если уже выполнялся), а дальше
     * всё решает одно слово {@code as}: с ним в текущей области заводится одно имя —
     * значение-модуль, без него в неё заводятся все имена модуля, как будто их
     * объявили здесь.
     * <p>
     * Заводятся <b>связками</b>, а не копиями: {@code a = 10} после развёрнутого импорта
     * меняет переменную модуля, и это видят и его функции, и все, кто импортировал его
     * же. Обе формы поэтому равносильны — {@code m.a} и {@code a} после двух импортов
     * одного файла означают одну ячейку.
     * <p>
     * Область — <b>текущая</b>, и никакой особый случай для этого не понадобился:
     * {@code import} внутри функции заводит имена в теле функции и исчезает вместе
     * с ней, ровно как {@code def} или {@code const} на том же месте.
     * <p>
     * Откуда взялся модуль — из файла или из библиотеки на Java, — здесь не видно
     * и видно быть не должно: значение у обоих одно, {@code ModuleValue}.
     */
    @Override
    public Void visitImport(ImportStmt stmt, ExecutionContext context) {
        // Разрешением пути занимается реестр: видов модулей два — файл и встроенный, —
        // и различаются они именно тем, как из записи получается ключ.
        ModuleValue module = context.modules()
                .load(stmt.path(), context.unit().home(), stmt.pathSpan(), context, this);
        if (stmt.hasAlias()) {
            checkNotConstant(stmt.alias(), stmt.aliasSpan(), context);
            context.scope().define(stmt.alias(), module);
            return null;
        }
        // Имена не копируются, а связываются с ячейками модуля: развёрнутый импорт
        // обещает, что они ведут себя так же, как если бы их объявили в этом файле, —
        // а модуль выполняется один раз, и значение у всех импортёров общее. Копия
        // сдержала бы только первое обещание: 'PI = 4' поменял бы её, функции модуля
        // считали бы по-старому, а второй импортёр не увидел бы ничего. Отсюда же
        // и константа: она остаётся константой, потому что спрашивают о ней модуль.
        for (String name : module.names()) {
            checkNotConstant(name, stmt.pathSpan(), context);
            checkNotShadowingType(name, module.get(name), stmt.pathSpan(), context);
            context.scope().defineAlias(name, new ModuleBinding(module, name));
        }
        return null;
    }

    /**
     * Развёрнутый импорт не перекрывает тип, объявленный в этой же области, молча.
     * <p>
     * Два разных класса под одним именем — выше и ниже одной строки — худшее, что можно
     * предложить читателю скрипта: по имени уже не понять, чей экземпляр создаётся
     * и что ответит {@code is}. Заменить обычное имя импорт вправе, как и любое
     * объявление, а два <b>типа</b> с одним именем разводятся именованной формой.
     * <p>
     * Смотрим только свою область: затенить класс, объявленный снаружи, импорт внутри
     * функции может — это то же затенение, что у {@code def} или {@code const} на том
     * же месте.
     */
    private static void checkNotShadowingType(String name, Value incoming, Span span,
                                              ExecutionContext context) {
        Value existing = context.scope().lookupHere(name);
        if (existing == null || existing == incoming || !isType(existing) || !isType(incoming)) {
            return;
        }
        throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "модуль приносит тип '" + name
                + "', а такое имя в этой области уже есть. Импортируйте модуль"
                + " с именем — 'import ... as m' — и обращайтесь через него");
    }

    private static boolean isType(Value value) {
        return value instanceof ClassValue || value instanceof TraitValue;
    }

    /**
     * Возврат из функции. Значение считается здесь, а до вызова его доносит сигнал —
     * сквозь любую вложенность блоков и циклов, не требуя от них ни строчки кода.
     */
    @Override
    public Void visitReturn(ReturnStmt stmt, ExecutionContext context) {
        throw new ControlSignal.Return(
                stmt.hasValue() ? valueOf(stmt.value(), context) : NullValue.NULL);
    }

    /**
     * Значение ветки {@code case}.
     * <p>
     * Сигналом, как и {@code return}, и по той же причине: {@code yield} бывает
     * из глубины ветки — из {@code if}, из цикла, — и передавать его наверх кодом
     * возврата значило бы проверять его в каждом узле. Ловит сигнал ближайший
     * {@link #visitMatch}, поэтому вложенный {@code match} забирает свой {@code yield}
     * первым. Отложенное по пути наружу выполняется само: {@link #visitBlock} ловит
     * любой {@link RuntimeException}, доигрывает {@code defer} и бросает дальше.
     */
    @Override
    public Void visitYield(YieldStmt stmt, ExecutionContext context) {
        throw new ControlSignal.Yield(valueOf(stmt.value(), context));
    }

    @Override
    public Void visitErrorStmt(ErrorStmt stmt, ExecutionContext context) {
        throw brokenTree(stmt.span());
    }

    // --- ошибки --------------------------------------------------------------

    /** Поле экземпляра ошибки: сообщение для человека. */
    private static final String MESSAGE = "message";
    /** Поле экземпляра ошибки: имя её класса. Проставляется в момент броска. */
    private static final String KIND = "kind";
    /** Поле экземпляра ошибки: место броска — «файл:строка:столбец». */
    private static final String AT = "at";
    /** Поле экземпляра ошибки: путь по скрипту, массив строк. */
    private static final String TRACE = "trace";
    /** Поле экземпляра ошибки: то, что случилось на пути наружу и не должно затирать причину. */
    private static final String SUPPRESSED = "suppressed";

    /**
     * Бросок из скрипта.
     * <p>
     * Значение обязано быть экземпляром {@code Exception} или его наследника. Проверка
     * стоит одной строки и убирает из каждого обработчика вопрос «а это вообще объект?»,
     * которым расплачиваются языки, разрешающие {@code throw 5}.
     */
    @Override
    public Void visitThrow(ThrowStmt stmt, ExecutionContext context) {
        Value value = valueOf(stmt.error(), context);
        if (!(value instanceof InstanceObjectValue error) || !isException(error, context)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, stmt.error().span(),
                    "бросить можно только экземпляр Exception, а здесь "
                            + value.type().title() + " (" + value.display() + ")");
        }
        throw raise(error, stmt.span(), context);
    }

    /**
     * {@code try} с обработчиками и {@code finally}.
     * <p>
     * Порядок здесь и есть обещание языка, поэтому конструкция расписана явно, а не
     * отдана {@code try/finally} самой Java:
     * <ol>
     *   <li>сигналы ({@code return}, {@code break}, {@code continue}) и {@link FatalError}
     *       обработчикам не достаются, но {@code finally} при них выполняется;</li>
     *   <li>ошибка, брошенная из обработчика, летит наружу — своим же {@code catch}
     *       она не ловится;</li>
     *   <li>ошибка из {@code finally} не затирает ту, ради которой мы шли наружу:
     *       первая летит дальше, вторая ложится ей в {@code suppressed}.</li>
     * </ol>
     */
    @Override
    public Void visitTry(TryStmt stmt, ExecutionContext context) {
        RuntimeException pending = null;
        try {
            visitBlock(stmt.body(), context);
        } catch (WdlRuntimeError error) {
            // Путь по скрипту записывается здесь же: ошибка, брошенная и пойманная
            // внутри одной функции, границы вызова не пересекает вовсе — а кадры
            // вокруг неё есть, и обработчик вправе их увидеть.
            error.rememberTrace(Frame.trace(context.frame()));
            TryStmt.Catch handler = handlerFor(stmt, error, context);
            if (handler == null) {
                pending = error;
            } else {
                try {
                    handle(handler, error, context);
                } catch (RuntimeException failed) {
                    pending = failed;
                }
            }
        } catch (RuntimeException uncatchable) {
            // Сигналы управления и FatalError: обработчик их не видит, а finally обязан
            // выполниться — закрыть начатое на пути наружу можно и нужно.
            pending = uncatchable;
        }

        if (stmt.hasFinally()) {
            try {
                visitBlock(stmt.finallyBlock(), context);
            } catch (RuntimeException second) {
                pending = onTheWayOut(pending, second, context);
            }
        }
        if (pending != null) {
            throw pending;
        }
        return null;
    }

    /**
     * Записывает отложенное действие. Именно записывает: до выхода из области
     * оно не выполняется, а если выполнение до этой строки не дошло — не выполнится вовсе.
     * <p>
     * В этом и разница с {@code finally}, из-за которой нужны оба: блок {@code finally}
     * существует независимо от того, дошло ли дело до захвата ресурса, и потому обрастает
     * проверками на {@code null}; отложенного действия просто нет, пока {@code open}
     * не вернулся.
     */
    @Override
    public Void visitDefer(DeferStmt stmt, ExecutionContext context) {
        Deferred pending = context.deferred();
        if (pending == null) {
            // Парсер ставит 'defer' только внутри блока или на верхнем уровне файла,
            // а у обоих список есть. Сюда можно попасть только с деревом, собранным
            // в обход разбора.
            throw new IllegalStateException("'defer' вне области видимости: дерево собрано неверно");
        }
        pending.add(stmt.body(), context);
        return null;
    }

    /**
     * Работа с ресурсом.
     * <p>
     * Захват идёт слева направо, закрытие — справа налево: правый ресурс мог быть взят
     * из левого. Если бросил сам захват, закрывается только то, что уже захвачено,
     * а тело не выполняется вовсе — то же правило «отложено только после успеха»,
     * что у {@code defer}, просто записанное конструкцией.
     */
    @Override
    public Void visitUse(UseStmt stmt, ExecutionContext context) {
        ExecutionContext inner = context.nested();
        List<Value> held = new ArrayList<>(stmt.resources().size());
        RuntimeException flying = null;
        try {
            for (UseStmt.Binding resource : stmt.resources()) {
                Value value = valueOf(resource.value(), inner);
                checkCloseable(value, resource, inner);
                held.add(value);
                inner.scope().define(resource.name(), value);
            }
            visit(stmt.body(), inner);
        } catch (RuntimeException exit) {
            flying = exit;
        }

        for (int i = held.size() - 1; i >= 0; i--) {
            try {
                close(held.get(i), stmt.resources().get(i).span(), inner);
            } catch (RuntimeException failed) {
                flying = onTheWayOut(flying, failed, inner);
            }
        }
        if (flying != null) {
            throw flying;
        }
        return null;
    }

    /**
     * Проверка стоит здесь, а не в момент закрытия: узнать «это не ресурс» надо
     * до того, как тело отработало, — иначе ошибка прилетит в самом конце и объяснит
     * не то.
     */
    private static void checkCloseable(Value value, UseStmt.Binding resource,
                                       ExecutionContext context) {
        TraitValue closeable = context.exceptions().closeable();
        if (closeable == null) {
            // Прелюдии не было — проверять не с чем; закрытие само скажет, если close нет.
            return;
        }
        if (value instanceof InstanceObjectValue instance && instance.owner().conformsTo(closeable)) {
            return;
        }
        String what = value instanceof InstanceObjectValue instance
                ? "класс '" + instance.owner().name() + "' не подмешивает трейт 'Closeable'"
                : "здесь " + value.type().title() + " (" + value.display() + ")";
        throw new WdlRuntimeError(ErrorKind.TYPE, resource.value().span(),
                "'use' работает со значением, которое умеет закрываться, а " + what);
    }

    /** Зовёт {@code close()} — обычным чтением метода и обычным вызовом. */
    private void close(Value resource, Span span, ExecutionContext context) {
        Value method = read(resource, StringValue.of("close"), AccessStyle.DOT, span, context);
        if (!(method instanceof FunctionValue function)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    "у ресурса нет метода 'close', закрывать его нечем");
        }
        function.call(context, List.of(), span);
    }

    /**
     * Выполняет отложенное — в обратном порядке и <b>всё</b>, даже если по дороге
     * что-то из него упало.
     *
     * @param flying то, с чем мы уходим из области: ошибка, сигнал или {@code null}
     * @return то, что полетит наружу после уборки
     */
    private RuntimeException runDeferred(Deferred pending, RuntimeException flying,
                                         ExecutionContext context) {
        if (pending.isEmpty()) {
            return flying;
        }
        for (Deferred.Action action : pending.inRunOrder()) {
            try {
                visit(action.body(), action.context());
            } catch (RuntimeException failed) {
                flying = onTheWayOut(flying, failed, context);
            }
        }
        return flying;
    }

    /**
     * Что победит: то, ради чего мы идём наружу, или то, что случилось по дороге.
     * <p>
     * Правило одно на {@code finally}, {@code defer} и закрытие ресурсов:
     * <b>подавляется то, что случилось на пути наружу, а не то, ради чего мы шли</b>.
     * Первая ошибка объясняет, что пошло не так, вторая — лишь следствие, и терять
     * первую нельзя.
     * <p>
     * Исключение одно: {@code return}, {@code break} и {@code continue} — не причина,
     * а намерение, и ошибка их отменяет. Иначе {@code return} молча возвращал бы
     * значение из области, уборка которой не удалась.
     */
    private RuntimeException onTheWayOut(RuntimeException flying, RuntimeException second,
                                         ExecutionContext context) {
        if (flying == null || flying instanceof ControlSignal) {
            return second;
        }
        if (flying instanceof WdlRuntimeError first && second instanceof WdlRuntimeError extra) {
            suppress(first, extra, context);
        }
        return flying;
    }

    /** Первый подходящий обработчик, сверху вниз, или {@code null}. */
    private TryStmt.Catch handlerFor(TryStmt stmt, WdlRuntimeError error, ExecutionContext context) {
        for (TryStmt.Catch handler : stmt.handlers()) {
            if (handler.catchesEverything()) {
                return handler;
            }
            for (TryStmt.TypeRef reference : handler.types()) {
                if (catches(error, typeOf(reference, context), context)) {
                    return handler;
                }
            }
        }
        return null;
    }

    /**
     * Значение из {@code catch (e is ...)}: класс, трейт или дескриптор типа.
     * <p>
     * Дескриптор ({@code TypeValue}) пропускается тем же путём, что класс и трейт:
     * он реализует {@link ClassValue}, а значит уже проходит проверку {@code instanceof}
     * ниже, и отдельной ветки для него не нужно. Поэтому {@code catch (e is Object)}
     * законен — как и {@code catch (e is Exception)}.
     */
    private Value typeOf(TryStmt.TypeRef reference, ExecutionContext context) {
        Value value = typeValue(reference.alias(), reference.name(), reference.title(),
                "класс, трейт или тип", reference.span(), context);
        if (value instanceof ClassValue || value instanceof TraitValue) {
            return value;
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, reference.span(),
                "после 'is' в обработчике должен стоять класс, трейт или тип, а '" + reference.title()
                        + "' — это " + value.type().title() + " (" + value.display() + ")");
    }

    /**
     * Подходит ли ошибка обработчику.
     * <p>
     * У ошибки, брошенной скриптом, значение уже есть, и вопрос решает {@code matches}
     * цели — тот же метод, которым отвечает обычный {@code is} (см.
     * {@link ClassValue#matches}). Раньше здесь стоял {@code conformsTo} напрямую;
     * теперь путь ровно тот же, что у оператора, — дорог осталось две, а не три:
     * значение ошибки есть — спрашиваем цель, значения нет — спрашиваем реестр запуска,
     * который сравнивает формы классов, ничего не материализуя.
     */
    private static boolean catches(WdlRuntimeError error, Value target, ExecutionContext context) {
        if (error.payload() instanceof InstanceObjectValue instance) {
            return switch (target) {
                case ClassValue declared -> declared.matches(instance);
                case TraitValue declared -> declared.matches(instance);
                default -> false;
            };
        }
        return error.kind() != null && context.exceptions().matches(error.kind(), target);
    }

    /** Выполняет обработчик: имя пойманной ошибки живёт только в его области. */
    private void handle(TryStmt.Catch handler, WdlRuntimeError error, ExecutionContext context) {
        ExecutionContext inner = context.nested();
        inner.scope().define(handler.name(), materialize(error, context));
        visit(handler.body(), inner);
    }

    /**
     * Значение ошибки — готовое или созданное сейчас.
     * <p>
     * Ошибка движка становится объектом ровно в этот момент: непойманная не становится
     * им никогда, и на пути в хост от неё нужны только текст и место.
     */
    private Value materialize(WdlRuntimeError error, ExecutionContext context) {
        if (error.payload() != null) {
            return error.payload();
        }
        ClassValue declared = context.exceptions().classOf(error.kind());
        MapValue value;
        if (declared != null
                && declared.instantiate(List.of(StringValue.of(error.getMessage())), context,
                        error.span()) instanceof MapValue created) {
            value = created;
        } else {
            // Прелюдии в этом запуске не было — такое бывает, когда функцию wdl зовёт
            // приложение через свой CallContext. Объект всё равно нужен: обработчик
            // получит те же поля, только без класса.
            value = new MapValue();
            value.put(MESSAGE, StringValue.of(error.getMessage()));
            value.put(SUPPRESSED, new ArrayValue());
        }
        value.put(KIND, StringValue.of(error.kindName()));
        value.put(AT, StringValue.of(placeOf(error.span(), error.source(), context)));
        value.put(TRACE, traceValue(error.trace()));
        if (error.javaCause() != null) {
            describeJava(value, error);
        }
        error.materialized(value);
        return value;
    }

    /** Поля {@code JavaException}: откуда прилетело и чем это было. */
    private static void describeJava(MapValue value, WdlRuntimeError error) {
        Throwable cause = error.javaCause();
        value.put("javaClass", StringValue.of(cause.getClass().getName()));
        value.put("module", error.module() == null
                ? NullValue.NULL
                : StringValue.of(error.module()));
        // Функцией, а не массивом: стек из сорока строк не должен попадать ни в println,
        // ни в перебор полей, а собирать его незачем, пока не спросили.
        value.put("javaTrace", BuiltinFunction.of("javaTrace", Arity.exactly(0),
                (ignoredContext, ignoredArguments, ignoredSpan) -> {
                    ArrayValue lines = new ArrayValue();
                    for (StackTraceElement element : cause.getStackTrace()) {
                        lines.add(StringValue.of(element.toString()));
                    }
                    return lines;
                }));
    }

    /** Ошибка при выходе не затирает ту, ради которой мы выходим, — она ложится к ней. */
    private void suppress(WdlRuntimeError flying, WdlRuntimeError extra, ExecutionContext context) {
        if (materialize(flying, context) instanceof MapValue carrier
                && carrier.get(SUPPRESSED) instanceof ArrayValue list) {
            list.add(materialize(extra, context));
        }
    }

    /**
     * Готовит экземпляр к полёту: имя класса, место и путь по скрипту.
     * <p>
     * Повторный бросок ({@code throw e} внутри обработчика) место и трейс не затирает:
     * иначе перезаворачивание стирало бы ровно ту информацию, ради которой заворачивают.
     */
    private static WdlRuntimeError raise(InstanceObjectValue error, Span span,
                                         ExecutionContext context) {
        List<String> trace = Frame.trace(context.frame());
        if (!alreadyThrown(error)) {
            error.put(KIND, StringValue.of(error.owner().name()));
            error.put(AT, StringValue.of(placeOf(span, null, context)));
            error.put(TRACE, traceValue(trace));
        }
        WdlRuntimeError thrown = WdlRuntimeError.thrown(span, error, messageOf(error));
        thrown.rememberTrace(trace);
        return thrown;
    }

    private static boolean alreadyThrown(InstanceObjectValue error) {
        return error.get(AT) instanceof StringValue at && !at.value().isEmpty();
    }

    /** Наследник ли это {@code Exception} — по классу из реестра запуска, а не по имени. */
    private static boolean isException(InstanceObjectValue error, ExecutionContext context) {
        ClassValue root = context.exceptions().classOf(ErrorKind.EXCEPTION);
        // Без прелюдии сравнивать не с чем: разрешаем любой экземпляр, иначе бросок
        // вообще перестал бы работать там, где корневой класс не объявлен.
        return root == null || error.owner().conformsTo(root);
    }

    private static String messageOf(InstanceObjectValue error) {
        return error.get(MESSAGE) instanceof StringValue message
                ? message.value()
                : error.owner().name();
    }

    /** «файл:строка:столбец» или пустая строка, если исходника нет (REPL, eval). */
    private static String placeOf(Span span, Source known, ExecutionContext context) {
        Source source = known != null ? known : context.unit().source();
        if (source == null || span.isNone() || span.start() > source.length()) {
            return "";
        }
        return source.name() + ":" + source.positionOf(span.start());
    }

    private static ArrayValue traceValue(List<String> frames) {
        ArrayValue lines = new ArrayValue();
        frames.forEach(frame -> lines.add(StringValue.of(frame)));
        return lines;
    }

    // --- механика циклов -----------------------------------------------------

    /**
     * Один проход тела цикла.
     *
     * @return {@code true}, если цикл надо прервать
     */
    private boolean runLoopBody(Stmt body, ExecutionContext context) {
        try {
            visit(body, context);
        } catch (ControlSignal.Break ignored) {
            return true;
        } catch (ControlSignal.Continue ignored) {
            // Проход закончен досрочно; остальное решает сам цикл.
        }
        return false;
    }

    /**
     * Один проход перебора: переменная цикла заводится в собственной области прохода,
     * а не переиспользуется между итерациями. Сейчас разницы не видно, но когда появятся
     * функции, замыкание захватит значение своего прохода — те самые грабли, на которые
     * JavaScript наступал до {@code let}.
     *
     * @return {@code true}, если цикл прерван
     */
    /**
     * Один проход перебора.
     * <p>
     * Область — своя на каждый проход, и это не мелочь: замыкание, созданное в теле,
     * захватывает значение <b>своего</b> прохода, а не последнее. Пропуск {@code _}
     * имени не заводит вовсе — ни ключ, ни значение.
     *
     * @param key     чем обращаются к источнику: номер, ключ объекта; у диапазона
     *                ключа нет, и сюда приходит {@code null} — но и второго имени
     *                там не бывает, это проверено выше
     * @param element что лежит по этому ключу
     */
    private boolean iteration(ForEachStmt stmt, ExecutionContext context,
                              Value key, Value element) {
        checkInterrupted(stmt.span());
        ExecutionContext step = context.nested();
        if (stmt.withKey()) {
            define(step, stmt.key(), key);
        }
        define(step, stmt.value(), element);
        return runLoopBody(stmt.body(), step);
    }

    /** Заводит переменную прохода; пропуск не заводит ничего. */
    private static void define(ExecutionContext step, UnpackTarget name, Value value) {
        if (name.writes()) {
            step.scope().define(((VariableExpr) name.target()).name(), value);
        }
    }

    /**
     * Даёт остановить зациклившийся скрипт снаружи — обычным
     * {@link Thread#interrupt()}. Три строки на цикл против «приложение висит,
     * и сделать с этим нечего».
     * <p>
     * Это {@link FatalError}, а не ошибка скрипта, и разница здесь принципиальная:
     * {@code while (true) { try { ... } catch (e) {} }} поймал бы прерывание и продолжил
     * работу — ровно то, ради чего приложение и звало {@code interrupt()}.
     */
    private static void checkInterrupted(Span span) {
        if (Thread.currentThread().isInterrupted()) {
            throw FatalError.interrupted(span);
        }
    }

    // --- выражения -----------------------------------------------------------

    @Override
    public Value visitLiteral(LiteralExpr expr, ExecutionContext context) {
        return expr.value();
    }

    @Override
    public Value visitVariable(VariableExpr expr, ExecutionContext context) {
        Value value = context.scope().lookup(expr.name(), context, expr.span());
        if (value == null) {
            // Подсказка ищется по дереву файла и только здесь, на пути ошибки: чаще
            // всего имя в файле есть, просто объявлено ниже — см. Declarations.
            throw new WdlRuntimeError(ErrorKind.NAME, expr.span(), "переменная '" + expr.name()
                    + "' не определена" + Declarations.hint(expr.name(), context));
        }
        return value;
    }

    /**
     * Унарная операция: ядро, потом член без параметров у самого значения.
     * <p>
     * Унарный отличается от бинарного арностью, а не именем, — {@code def `-`()}
     * рядом с {@code def `-`(right)} законны и не мешают друг другу.
     */
    @Override
    public Value visitUnary(UnaryExpr expr, ExecutionContext context) {
        return Overloading.unary(expr.op(), valueOf(expr.operand(), context), expr.span(), context);
    }

    /**
     * Ленивые {@code &&} и {@code ||} обрабатываются здесь, а не в {@link Operations}:
     * решение «вычислять ли правую часть» принимается до того, как правое значение
     * появится, — а операциям значения передаются уже готовыми.
     * <p>
     * Результатом становится сам операнд, а не приведённое к логическому значение:
     * {@code имя || "без имени"} даёт подставленное значение по умолчанию, а
     * {@code настройки && настройки.цвет} — безопасное чтение. Для условий разницы нет,
     * они смотрят на истинность.
     */
    @Override
    public Value visitBinary(BinaryExpr expr, ExecutionContext context) {
        Value left = valueOf(expr.left(), context);
        if (expr.op() == BinaryOp.AND) {
            return left.isTruthy() ? valueOf(expr.right(), context) : left;
        }
        if (expr.op() == BinaryOp.OR) {
            return left.isTruthy() ? left : valueOf(expr.right(), context);
        }
        Value right = valueOf(expr.right(), context);
        return binary(expr.op(), left, right, expr.span(), context);
    }

    /**
     * Бинарная операция: сперва ядро, и только если оно не умеет — оператор у значений.
     * <p>
     * Само правило и все его исключения живут в {@link Overloading}, а не здесь:
     * оператор спрашивает не только вычисление выражения, но и сортировка массива,
     * и члены {@code contains} с {@code has}. Разойтись им нельзя, значит и место
     * у правила одно.
     */
    private Value binary(BinaryOp op, Value left, Value right, Span span, ExecutionContext context) {
        return Overloading.binary(op, left, right, span, context);
    }

    @Override
    public Value visitTernary(TernaryExpr expr, ExecutionContext context) {
        return valueOf(expr.condition(), context).isTruthy()
                ? valueOf(expr.ifTrue(), context)
                : valueOf(expr.ifFalse(), context);
    }

    /**
     * Ветвление по одному предмету.
     * <p>
     * <b>Предмет вычисляется ровно один раз</b> — это и есть главное отличие от цепочки
     * {@code if}: {@code match (order.total())} не зовёт {@code total()} на каждую ветку.
     * Образцы, наоборот, вычисляются лениво, сверху вниз, до первого совпадения:
     * побочный эффект в образце случится, только если до этой ветки дошла очередь.
     * <p>
     * <b>Ни одной новой семантики здесь нет.</b> Проверка образца — это буквально
     * {@link Operations#binary}, та же, что стоит за оператором в {@code if}: сравнения,
     * {@code is}, {@code in}, {@code has}, диапазон. Ради этого {@code match} и делался
     * последним.
     * <p>
     * <b>{@code break} и {@code continue} из ветки проходят наружу сами собой</b> —
     * это {@link ControlSignal}, и здесь его никто не ловит. Провала между ветками нет,
     * поэтому {@code break} в теле ветки относится к объемлющему циклу, а не к
     * {@code match}: в Си иначе, и молчать об этом нельзя.
     * <p>
     * Ни одна ветка не подошла и {@code else} не написан — значение {@code null}.
     * В позиции выражения такого не бывает: {@code else} там обязателен, и требует его
     * парсер. Правило одно и стоит в одном месте.
     */
    @Override
    public Value visitMatch(MatchExpr expr, ExecutionContext context) {
        Value subject = valueOf(expr.subject(), context);
        for (MatchCase branch : expr.cases()) {
            if (fits(branch, subject, context)) {
                return caseResult(expr, branch, context);
            }
        }
        return expr.hasOtherwise() ? caseResult(expr, expr.otherwise(), context) : NullValue.NULL;
    }

    /**
     * Подходит ли ветка: хоть один образец истинен и условие, если оно есть, тоже.
     * <p>
     * Ветка без образцов ({@code case if throttled =>}) подходит по одному условию —
     * образцы перебирать нечего, и «ни один не совпал» здесь означало бы «никогда».
     */
    private boolean fits(MatchCase branch, Value subject, ExecutionContext context) {
        boolean matched = branch.tails().isEmpty();
        for (CaseTail tail : branch.tails()) {
            Value right = valueOf(tail.right(), context);
            if (binary(tail.op(), subject, right, tail.span(), context).isTruthy()) {
                matched = true;
                break;
            }
        }
        return matched && (!branch.hasGuard() || valueOf(branch.guard(), context).isTruthy());
    }

    /**
     * Тело ветки: стрелка даёт значение сразу, блок — словом {@code yield}.
     * <p>
     * Ветка-блок в позиции выражения, дошедшая до конца без {@code yield}, — ошибка,
     * а не тихий {@code null}. Парсер такую ветку ловит, только когда {@code yield}
     * не написан вовсе; когда он написан под условием, которое не выполнилось,
     * сказать об этом может лишь выполнение — и говорит, вместо того чтобы подсунуть
     * дальше пустое значение.
     */
    private Value caseResult(MatchExpr expr, MatchCase branch, ExecutionContext context) {
        if (branch.isValue()) {
            return valueOf(branch.value(), context);
        }
        try {
            // Свою область блок заводит сам — см. visitBlock.
            visit(branch.body(), context);
        } catch (ControlSignal.Yield yielded) {
            return yielded.value();
        }
        if (expr.asValue()) {
            throw new WdlRuntimeError(ErrorKind.VALUE, branch.span(),
                    "ветка 'case' закончилась, не отдав значение: 'match' стоит в позиции"
                            + " выражения, и значение обязано быть у любого предмета."
                            + " Проверьте, что 'yield' выполняется на всех путях ветки");
        }
        return NullValue.NULL;
    }

    /**
     * Обращение к содержимому значения.
     * <p>
     * Здесь видно, ради чего {@code a.x} и {@code a["x"]} — один узел: правило чтения
     * пишется один раз на тип контейнера, а не по разу на каждую форму записи.
     * Тем же кодом пользуется присваивание — см. {@link #resolvePlace}.
     */
    @Override
    public Value visitAccess(AccessExpr expr, ExecutionContext context) {
        Value target = valueOf(expr.target(), context);
        Value key = valueOf(expr.key(), context);
        return read(target, key, expr.style(), expr.span(), context);
    }

    /**
     * Вызов.
     * <p>
     * Вызывается значение, а не имя: слева от скобок может стоять что угодно, что даёт
     * функцию, — переменная, поле объекта, элемент массива, результат другого вызова.
     * Число аргументов проверяется здесь, до входа в функцию, поэтому сообщение
     * одинаково для всех функций, а сама функция начинается с дела, а не с проверок.
     * <p>
     * <b>Аргументы вычисляются в порядке записи</b>, а не в порядке параметров:
     * побочные эффекты идут так, как читается строка, даже если имена переставлены.
     * Раскладка по позициям — после, и только если имена вообще есть: вызов без имён
     * идёт прежним путём, тем же списком и с тем же сообщением об ошибке.
     */
    @Override
    public Value visitCall(CallExpr expr, ExecutionContext context) {
        // Обращение слева вычисляется здесь, а не общим valueOf, ради одного:
        // получателя надо сохранить. Без него сообщение об ошибке вызова говорит
        // про следствие («здесь число»), а не про причину («это свойство»).
        Value callee;
        Value receiver = null;
        Value memberKey = null;
        if (expr.callee() instanceof AccessExpr access) {
            receiver = valueOf(access.target(), context);
            memberKey = valueOf(access.key(), context);
            callee = read(receiver, memberKey, access.style(), access.span(), context);
        } else {
            callee = valueOf(expr.callee(), context);
        }
        if (!(callee instanceof FunctionValue function)) {
            throw notCallable(expr, callee, receiver, memberKey, context);
        }

        List<Value> values = evaluate(expr.arguments(), context);
        Arguments arguments;
        if (Binder.needed(function.signature(), expr.arguments())) {
            arguments = Binder.bind(function.signature(), expr.arguments(), values,
                    Binder.Callee.function(function.name()), expr.span());
        } else {
            if (!function.arity().accepts(values.size())) {
                throw new WdlRuntimeError(ErrorKind.CALL, expr.span(), "функция '" + function.name()
                        + "' принимает " + function.arity().describeArguments()
                        + ", а передано " + values.size());
            }
            arguments = Arguments.positional(values);
        }

        if (function instanceof UserFunction) {
            return function.call(context, arguments, expr.span());
        }
        // Тело написано на Java — значит, оттуда может прилететь что угодно.
        // Правило «что своё, что чужое» одно на весь движок и живёт в Foreign.
        return Foreign.call(expr.span(), null, moduleOf(expr.callee(), context),
                () -> function.call(context, arguments, expr.span()));
    }

    /**
     * Почему это нельзя позвать.
     * <p>
     * <b>Скобки — самая частая ошибка при наборе членов</b>, и общая ветка про них
     * молчит: {@code a.size()} у члена-свойства сказало бы «вызвать можно только
     * функцию, а здесь число (3)» — правда, из которой ничего не следует. Поэтому,
     * когда слева стояло обращение, здесь спрашивается таблица членов: если за именем
     * стоит свойство, сообщение называет именно это.
     * <p>
     * Подсказка не даётся, когда имя перекрыто собственными данными: тогда за скобками
     * стоял ключ, а не член, и совет «скобки лишние» увёл бы не туда.
     */
    private WdlRuntimeError notCallable(CallExpr expr, Value callee, Value receiver, Value key,
                                        ExecutionContext context) {
        if (receiver != null && key instanceof StringValue name && !shadowed(receiver, key)) {
            Member member = memberOf(receiver, name.value(), context);
            if (member != null && member.isProperty()) {
                return new WdlRuntimeError(ErrorKind.CALL, expr.callee().span(), "'" + name.value()
                        + "' у значения типа " + receiver.type().title()
                        + " — свойство, а не метод: скобки лишние");
            }
        }
        return new WdlRuntimeError(ErrorKind.CALL, expr.callee().span(),
                "вызвать можно только функцию, а здесь " + callee.type().title() + " (" + callee + ")");
    }

    /** Перекрыто ли имя собственными данными получателя. */
    private static boolean shadowed(Value receiver, Value key) {
        if (receiver instanceof MapValue object) {
            return object.has(key);
        }
        if (receiver instanceof ClassValue declared) {
            return declared.statics().has(key);
        }
        return receiver instanceof ModuleValue module
                && key instanceof StringValue name
                && module.has(name.value());
    }

    /** Значения аргументов — строго в порядке записи в исходнике. */
    private List<Value> evaluate(List<Argument> arguments, ExecutionContext context) {
        List<Value> values = new ArrayList<>(arguments.size());
        for (Argument argument : arguments) {
            values.add(valueOf(argument.value(), context));
        }
        return values;
    }

    /**
     * Из какого модуля прилетело чужое исключение.
     * <p>
     * Спрашивается только тогда, когда оно уже прилетело, — на удачном пути этого кода
     * нет вовсе. Отвечает по форме записи вызова: {@code io.read(path)} называет модуль,
     * {@code read(path)} после развёрнутого импорта — уже нет, и это честный {@code null},
     * а не догадка.
     */
    private static String moduleOf(Expr callee, ExecutionContext context) {
        if (callee instanceof AccessExpr access
                && access.target() instanceof VariableExpr holder
                && context.scope().lookup(holder.name()) instanceof ModuleValue module) {
            return module.name();
        }
        return null;
    }

    /**
     * Создание экземпляра.
     * <p>
     * Создаёт значение, а не имя: слева от скобок может стоять что угодно, что даёт
     * класс. Число аргументов проверяется здесь, по заголовку и до входа в конструктор,
     * — тем же правилом и тем же сообщением, что у функции.
     */
    @Override
    public Value visitNew(NewExpr expr, ExecutionContext context) {
        Value target = valueOf(expr.callee(), context);
        if (target instanceof TraitValue trait) {
            throw new WdlRuntimeError(ErrorKind.DECLARATION, expr.callee().span(),
                    "'" + trait.name() + "' — трейт, экземпляр создаёт класс");
        }
        if (!(target instanceof ClassValue declared)) {
            throw new WdlRuntimeError(ErrorKind.CALL, expr.callee().span(), "создать экземпляр можно только классом, "
                    + "а здесь " + target.type().title() + " (" + target + ")");
        }

        List<Value> values = evaluate(expr.arguments(), context);
        Arguments arguments;
        if (Binder.needed(declared.signature(), expr.arguments())) {
            arguments = Binder.bind(declared.signature(), expr.arguments(), values,
                    Binder.Callee.klass(declared.name()), expr.span());
        } else {
            if (!declared.arity().accepts(values.size())) {
                throw new WdlRuntimeError(ErrorKind.CALL, expr.span(), "класс '" + declared.name()
                        + "' принимает " + declared.arity().describeArguments()
                        + ", а передано " + values.size());
            }
            arguments = Arguments.positional(values);
        }
        // Класс, написанный на wdl, и класс, встроенный приложением, здесь неразличимы —
        // кроме одного: у второго конструктор написан на Java, и оттуда может прилететь
        // что угодно. Это единственное место, где Java-код зовётся при создании.
        if (declared instanceof WdlClass) {
            return declared.instantiate(arguments, context, expr.span());
        }
        return Foreign.call(expr.span(), null, moduleOf(expr.callee(), context),
                () -> declared.instantiate(arguments, context, expr.span()));
    }

    /**
     * Литерал массива вместе с раскрытием: {@code [*head, 3, *1..2]}.
     * <p>
     * Раскрывается массив и диапазон — ровно то же, что раскрывает {@code f(*values)}:
     * одна пара символов, одно значение. Диапазон обязан быть с целыми границами
     * по той же причине, что и в {@code for}: шаг перебора равен единице, а какие
     * числа лежат в {@code 0.5..2.5} «по одному», не знает никто.
     */
    @Override
    public Value visitArray(ArrayExpr expr, ExecutionContext context) {
        List<Value> items = new ArrayList<>(expr.elements().size());
        for (ArrayExpr.Element element : expr.elements()) {
            Value value = valueOf(element.value(), context);
            if (!element.isSpread()) {
                items.add(value);
                continue;
            }
            spreadInto(items, value, element.span());
        }
        return ArrayValue.of(items);
    }

    /** Раскрытие одного контейнера в элементы массива. */
    private static void spreadInto(List<Value> items, Value value, Span span) {
        switch (value) {
            case ArrayValue array -> items.addAll(array.items());
            case RangeValue range -> {
                if (!range.from().isInteger() || !range.to().isInteger()) {
                    throw new WdlRuntimeError(ErrorKind.TYPE, span,
                            "раскрыть можно диапазон с целыми границами, а здесь " + range
                                    + ": шаг раскрытия равен единице");
                }
                long to = range.to().asLong();
                for (long i = range.from().asLong(); i <= to; i++) {
                    items.add(IntValue.of(i));
                    if (i == Long.MAX_VALUE) {
                        break;
                    }
                }
            }
            default -> throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    "раскрыть в элементы можно массив или диапазон, а здесь "
                            + value.type().title() + " (" + value.display() + ")");
        }
    }

    /**
     * Литерал объекта вместе с раскрытием: {@code {**defaults, timeout: 60}}.
     * <p>
     * <b>Последний победил</b>, хотя в вызове та же коллизия — ошибка. Разойтись
     * здесь можно потому, что причина запрета в вызове тут не действует: там
     * {@code f(*arr, **map)} собирает два независимых источника, и порядок их
     * объединения читателю не виден, а в литерале он написан автором слева направо
     * и переопределение — ровно то, зачем сливают.
     * <p>
     * Экземпляр класса раскрывается, в отличие от {@code f(**instance)}: в литерале
     * имён параметров нет вовсе, и {@code {**user}} — это «поля объектом», обычная
     * операция. По той же причине ключ здесь любой: совпадать ему не с чем.
     */
    @Override
    public Value visitObject(ObjectExpr expr, ExecutionContext context) {
        MapValue object = new MapValue();
        for (ObjectExpr.Entry entry : expr.entries()) {
            if (!entry.isSpread()) {
                object.put(valueOf(entry.key(), context), valueOf(entry.value(), context));
                continue;
            }
            Value value = valueOf(entry.value(), context);
            if (!(value instanceof MapValue source)) {
                throw new WdlRuntimeError(ErrorKind.TYPE, entry.span(),
                        "раскрыть в пары можно только объект, а здесь "
                                + value.type().title() + " (" + value.display() + ")");
            }
            source.entries().forEach(object::put);
        }
        return object;
    }

    /**
     * Литерал функции: дерево тела плюс текущая область видимости как замыкание.
     * <p>
     * Замыкается именно область, а не снимок её значений: две функции, объявленные
     * рядом, видят одну и ту же переменную, и изменение из одной видно другой. Это
     * и позволяет написать счётчик или накопитель поверх замыкания.
     */
    @Override
    public Value visitFunction(FunctionExpr expr, ExecutionContext context) {
        // Замок заводится здесь, при вычислении литерала, — и потому принадлежит
        // значению-функции, а не имени. Два вычисления одного и того же 'def' дают
        // два замыкания и два замка: у них разное захваченное состояние, и защищать
        // их одним замком было бы неправдой.
        return new UserFunction(expr, context.scope(), context.unit(), context.run(), this,
                expr.isSynchronized() ? new ReentrantLock() : null,
                // Аннотации считаются здесь же и один раз — до того, как отработает
                // первый декоратор. Иначе '@[deco] @{a: 1}' и '@{a: 1} @[deco]'
                // значили бы разное, а разницы в записи читатель не заметит.
                declaredAnnotations(expr, context));
    }

    // --- аннотации -----------------------------------------------------------

    /**
     * Аннотации объявления функции и её параметров — уже значениями.
     * <p>
     * Ничего не написано — общая пустая карта: литерал функции вычисляется на каждом
     * вызове объемлющей, и лишней аллокации на этом пути быть не должно.
     */
    private DeclaredAnnotations declaredAnnotations(FunctionExpr expr, ExecutionContext context) {
        Map<Value, Value> own = annotationValues(expr.annotations(), context);
        List<Map<Value, Value>> params = paramAnnotations(expr.params(), context);
        return own.isEmpty() && params.isEmpty()
                ? DeclaredAnnotations.NONE
                : new DeclaredAnnotations(own, params);
    }

    /**
     * Аннотации параметров по позициям заголовка.
     * <p>
     * Ни одной не написано — пустой список, а не столько же пустых карт, сколько
     * параметров: спрашивают о них по номеру, и {@link DeclaredAnnotations#param(int)}
     * на коротком списке отвечает то же самое.
     */
    private List<Map<Value, Value>> paramAnnotations(List<FunctionExpr.Param> params,
                                                     ExecutionContext context) {
        boolean any = false;
        for (FunctionExpr.Param param : params) {
            any |= param.annotations().written();
        }
        if (!any) {
            return List.of();
        }
        List<Map<Value, Value>> collected = new ArrayList<>(params.size());
        for (FunctionExpr.Param param : params) {
            collected.add(annotationValues(param.annotations(), context));
        }
        return List.copyOf(collected);
    }

    /**
     * Считает один набор блоков {@code @{...}} в неизменяемую карту.
     * <p>
     * <b>Дубликат ключа — ошибка, а не «последний победил».</b> В литерале объекта
     * наоборот, и расхождение намеренное: там порядок написан автором слева направо
     * и переопределение — то, зачем сливают, а здесь блоки независимы, порядок их
     * записи для читателя ничего не значит, и молчаливый выбор одного из двух
     * поставил бы смысл программы в зависимость от него. Литеральные ключи ловит
     * разбор; сюда доходят вычисляемые.
     */
    private Map<Value, Value> annotationValues(Annotations annotations, ExecutionContext context) {
        if (!annotations.written()) {
            return Map.of();
        }
        Map<Value, Value> collected = new LinkedHashMap<>();
        for (ObjectExpr.Entry entry : annotations.entries()) {
            if (!entry.isSpread()) {
                putAnnotation(collected, valueOf(entry.key(), context),
                        valueOf(entry.value(), context), entry.span());
                continue;
            }
            Value value = valueOf(entry.value(), context);
            if (!(value instanceof MapValue source)) {
                throw new WdlRuntimeError(ErrorKind.TYPE, entry.span(),
                        "раскрыть в аннотации можно только объект, а здесь "
                                + value.type().title() + " (" + value.display() + ")");
            }
            for (Map.Entry<Value, Value> pair : source.entries().entrySet()) {
                putAnnotation(collected, pair.getKey(), pair.getValue(), entry.span());
            }
        }
        return Collections.unmodifiableMap(collected);
    }

    /**
     * Аннотации объявления типа и всех его членов разом.
     * <p>
     * Собираются здесь, где есть область объявления, и уходят в значение уже готовыми:
     * иначе {@link WdlClass} пришлось бы считать чужие выражения в конструкторе,
     * то есть в момент, когда самого класса ещё нет.
     * <p>
     * Ключ у метода и у фабрики — имя в таблице ({@code FunctionExpr#memberName()}),
     * потому что по нему их и находят: у зеркального и унарного оператора имя в тексте
     * с ним расходится, и второй список правил манглинга языку не нужен.
     */
    private TypeAnnotations typeAnnotations(Annotations own, List<FunctionExpr.Param> params,
                                            List<FunctionExpr> methods,
                                            List<PropertyDecl> properties,
                                            List<ClassDeclStmt.Factory> factories,
                                            ExecutionContext context) {
        Map<String, DeclaredAnnotations> byMember = new LinkedHashMap<>();
        for (FunctionExpr method : methods) {
            collectMember(byMember, method, context);
        }
        for (ClassDeclStmt.Factory factory : factories) {
            collectMember(byMember, factory.function(), context);
        }
        Map<String, Map<Value, Value>> byProperty = new LinkedHashMap<>();
        for (PropertyDecl property : properties) {
            if (property.annotations().written()) {
                byProperty.put(property.name(), annotationValues(property.annotations(), context));
            }
        }
        Map<Value, Value> ownValues = annotationValues(own, context);
        List<Map<Value, Value>> paramValues = paramAnnotations(params, context);
        if (ownValues.isEmpty() && paramValues.isEmpty()
                && byMember.isEmpty() && byProperty.isEmpty()) {
            return TypeAnnotations.NONE;
        }
        return new TypeAnnotations(new DeclaredAnnotations(ownValues, paramValues),
                Map.copyOf(byMember), Map.copyOf(byProperty));
    }

    private void collectMember(Map<String, DeclaredAnnotations> collected, FunctionExpr member,
                               ExecutionContext context) {
        DeclaredAnnotations annotations = declaredAnnotations(member, context);
        if (!annotations.isEmpty()) {
            collected.put(member.memberName(), annotations);
        }
    }

    private static void putAnnotation(Map<Value, Value> collected, Value key, Value value,
                                      Span span) {
        if (collected.putIfAbsent(key, value) != null) {
            throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "ключ аннотации "
                    + key + " указан дважды: блоки сливаются в один объект,"
                    + " а порядок их записи ничего не значит");
        }
    }

    /**
     * Короткая форма обработки ошибки.
     * <p>
     * Ловится ровно то же, что ловит {@code catch}: {@link FatalError} проходит сквозь
     * обе формы наружу. Экземпляр ошибки при этом не создаётся — {@code try?} о ней
     * ничего не спрашивает, а {@code try!} несёт её дальше как есть.
     */
    @Override
    public Value visitTryExpr(TryExpr expr, ExecutionContext context) {
        try {
            return valueOf(expr.inner(), context);
        } catch (WdlRuntimeError error) {
            if (expr.style() == TryStyle.OPTIONAL) {
                return NullValue.NULL;
            }
            error.rememberTrace(Frame.trace(context.frame()));
            throw FatalError.assertionFailed(error.inSource(context.unit().source()));
        }
    }

    /**
     * До выполнения дело доходит только у дерева без ошибок разбора — вызывающий
     * обязан проверить {@link ru.wds.wdl.diagnostic.Diagnostics#hasErrors()}. Если узел
     * всё же встретился, это ошибка в самом движке, а не в скрипте, — отсюда
     * {@link IllegalStateException}, а не {@link WdlRuntimeError}.
     */
    @Override
    public Value visitError(ErrorExpr expr, ExecutionContext context) {
        throw brokenTree(expr.span());
    }

    // --- места записи --------------------------------------------------------

    /**
     * Куда пишет присваивание.
     * <p>
     * Двух видов ровно потому, что и слева от {@code =} бывает ровно два вида цели:
     * имя и обращение. Обращение при этом — тот же самый узел, что и при чтении,
     * поэтому новый тип-контейнер получает поддержку записи там же, где и чтения.
     */
    private sealed interface Place {

        /** Текущее значение — нужно составному присваиванию. */
        Value read();

        void write(Value value);
    }

    private record VariablePlace(Environment scope, String name, Span span,
                                 ExecutionContext context) implements Place {

        @Override
        public Value read() {
            Value value = scope.lookup(name, context, span);
            if (value == null) {
                throw new WdlRuntimeError(ErrorKind.NAME, span, "переменная '" + name
                        + "' не определена" + Declarations.hint(name, context));
            }
            return value;
        }

        @Override
        public void write(Value value) {
            // Существующее имя обновляется там, где объявлено; новое заводится здесь;
            // замороженное 'const' не меняется нигде — на то оно и константа.
            switch (scope.assign(name, value, context, span)) {
                case DONE -> { }
                case ABSENT -> scope.define(name, value);
                case CONSTANT -> throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "'" + name + "' нельзя присвоить: "
                        + "это константа, её значение задаётся один раз при объявлении");
            }
        }
    }

    /**
     * Интерпретатор здесь нужен ровно затем, что чтение экземпляра умеет отдать
     * связанный метод, а для этого нужен тот, кто умеет выполнять его тело.
     */
    private record ContainerPlace(Interpreter interpreter, Value container, Value key,
                                  AccessStyle style, Span span,
                                  ExecutionContext context) implements Place {

        @Override
        public Value read() {
            return interpreter.read(container, key, style, span, context);
        }

        @Override
        public void write(Value value) {
            Interpreter.write(container, key, value, style, span, context);
        }
    }

    private Place resolvePlace(Expr target, ExecutionContext context) {
        return switch (target) {
            case VariableExpr variable ->
                    new VariablePlace(context.scope(), variable.name(), variable.span(), context);
            case AccessExpr access -> new ContainerPlace(
                    this,
                    valueOf(access.target(), context),
                    valueOf(access.key(), context),
                    access.style(),
                    access.span(),
                    context);
            // Парсер других целей не пропускает: сюда можно попасть только из-за ошибки в движке.
            default -> throw new IllegalStateException("недопустимая цель присваивания: " + target);
        };
    }

    // --- чтение и запись по ключу --------------------------------------------

    /**
     * У экземпляра сначала ищется поле, потом метод класса — и остановка на первом
     * попадании.
     * <p>
     * <b>Поле перекрывает метод</b>, и это не случайность: {@code p.text = def() => "иначе"}
     * — законная подмена поведения у одного объекта, обычная в динамическом языке.
     * А {@code null} в конце вместо ошибки — то же решение, что у объекта: проверка
     * {@code if (p.print)} должна просто работать.
     * <p>
     * У класса читаются только его собственные поля — те, что положили записью по ключу,
     * и фабрики. Методы через класс не читаются: без экземпляра они бесполезны,
     * а до реализации родителя есть {@code super}.
     */
    private Value read(Value container, Value key, AccessStyle style, Span span,
                       ExecutionContext context) {
        return switch (container) {
            // Строковый ключ уходит в таблицу членов ДО проверки индекса: иначе
            // на 'a.size' человек получил бы «индекс массива должен быть целым
            // числом» — сообщение про то, чего он не писал.
            case ArrayValue array -> switch (key) {
                case StringValue name -> memberOrFail(array, name.value(), span, context);
                case RangeValue range -> slice(array, range, span);
                default -> array.get(Indexes.element(array.size(), key, "массива", span));
            };
            case InstanceObjectValue instance -> {
                if (instance.has(key)) {
                    yield instance.get(key);
                }
                Property property = property(instance, key);
                if (property != null) {
                    // Аксессор бывает написан на Java — у нативного класса и у моста
                    // в Java он написан всегда. Раньше этой ветки у Foreign не было,
                    // и исключение оттуда уходило наружу как крах движка.
                    yield Foreign.call(span, null, null,
                            () -> property.read(instance, context, span));
                }
                Value method = method(instance, key);
                if (method != null) {
                    yield method;
                }
                Value member = memberValue(instance, key, span, context);
                yield member != null ? member : NullValue.NULL;
            }
            case MapValue object -> {
                if (object.has(key)) {
                    yield object.get(key);
                }
                Value method = method(object, key);
                if (method != null) {
                    yield method;
                }
                Value member = memberValue(object, key, span, context);
                yield member != null ? member : NullValue.NULL;
            }
            case ClassValue declared -> readClass(declared, key, span, context);
            case ModuleValue module -> member(module, key, span, context);
            case StringValue string -> switch (key) {
                case StringValue name -> memberOrFail(string, name.value(), span, context);
                case RangeValue range -> slice(string, range, span);
                default -> StringValue.of(String.valueOf(
                        string.value().charAt(Indexes.element(string.length(), key, "строки", span))));
            };
            case NumberValue number -> memberOrIndex(number, key, style, span, context);
            // Диапазон неизменяем и по индексу не читается: у него только члены.
            case RangeValue range -> memberOrIndex(range, key, style, span, context);
            case FunctionValue function -> memberOrIndex(function, key, style, span, context);
            case TraitValue trait -> memberOrIndex(trait, key, style, span, context);
            default -> throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    "к значению типа " + container.type().title() + " нельзя обратиться " + how(style, key));
        };
    }

    /**
     * Член значения или {@code null}, если такого нет.
     * <p>
     * Спрашивается <b>после</b> собственных данных — общее правило языка: ключ,
     * положенный руками, значит больше встроенного имени. Цена решения честная:
     * у объекта, экземпляра и модуля член работает, только пока имя не занято,
     * и для кода, которому нужен гарантированный ответ, есть путь через дескриптор
     * типа ({@code Object.keys(box)}).
     */
    private Value memberValue(Value receiver, Value key, Span span, ExecutionContext context) {
        if (!(key instanceof StringValue name)) {
            return null;
        }
        Member member = memberOf(receiver, name.value(), context);
        return member == null ? null : valueOf(member, receiver, context, span);
    }

    /** Чтение члена: свойство зовёт getter, метод отдаётся связанным с получателем. */
    private static Value valueOf(Member member, Value receiver, ExecutionContext context, Span span) {
        Property property = member.property();
        return property != null ? property.read(receiver, context, span) : member.bind(receiver);
    }

    /**
     * Поиск члена: основание ядра, надстройка запуска, а у экземпляра ещё и то,
     * что добавили его классу.
     */
    private static Member memberOf(Value receiver, String name, ExecutionContext context) {
        Member builtin = BuiltinMembers.of(receiver.type()).get(name);
        if (builtin != null) {
            return builtin;
        }
        MemberTable table = context.run().members();
        Member added = table.added(receiver.type(), name);
        if (added != null) {
            return added;
        }
        return receiver instanceof InstanceObjectValue instance
                ? table.addedForClass(instance.owner(), name)
                : null;
    }

    /**
     * Член или ошибка — у значений, где строковый ключ данными быть не может.
     * <p>
     * Промолчать здесь нельзя: у строки, числа и массива своих ключей нет вовсе,
     * поэтому промах — это опечатка, и назвать её надо сразу.
     */
    private Value memberOrFail(Value receiver, String name, Span span, ExecutionContext context) {
        Member member = memberOf(receiver, name, context);
        if (member == null) {
            throw noMember(receiver, name, span, context);
        }
        return valueOf(member, receiver, context, span);
    }

    /**
     * То же, но там, где обращение по индексу бессмысленно вовсе: у числа, функции
     * и трейта ключом бывает только имя члена.
     */
    private Value memberOrIndex(Value receiver, Value key, AccessStyle style, Span span,
                                ExecutionContext context) {
        if (key instanceof StringValue name) {
            return memberOrFail(receiver, name.value(), span, context);
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, span,
                "к значению типа " + receiver.type().title() + " нельзя обратиться " + how(style, key));
    }

    /**
     * Промах по имени члена — самая частая ошибка при таком наборе, поэтому текст
     * называет тип, называет промах, предлагает похожее имя и перечисляет, что вообще
     * есть. Считается это только на пути ошибки: на удачном такого кода нет.
     */
    private static WdlRuntimeError noMember(Value receiver, String name, Span span,
                                            ExecutionContext context) {
        List<String> known = context.run().members().allNames(receiver.type());
        String closest = Names.closestTo(name, known);
        String hint = closest != null
                ? ". Похоже на '" + closest + "'"
                : known.isEmpty() ? "" : ". Есть: " + String.join(", ", known);
        return new WdlRuntimeError(ErrorKind.NAME, span,
                "у значения типа " + receiver.type().title() + " нет члена '" + name + "'" + hint);
    }

    /**
     * Обращение к классу: сначала статика, потом члены.
     * <p>
     * <b>Статика раньше членов</b> — то же правило, что у объекта: {@code Point.zero}
     * и {@code Point.methods = [...]} кладут данные, и они значат больше встроенного
     * имени. Надёжный путь к члену идёт через дескриптор: {@code Class.methods(Point)}.
     * <p>
     * <b>У дескриптора типа всё иначе</b>, и это следствие правила «сведения о типе
     * и члены типа не лежат в одной карте»: интроспекции класса у него нет (справка
     * о себе — под ключом {@code info}), а всё остальное пространство имён отдано
     * членам описываемого типа. Поэтому {@code Array.size} — это функция
     * {@code (массив) -> число}, то есть путь к члену в обход данных.
     */
    private Value readClass(ClassValue declared, Value key, Span span, ExecutionContext context) {
        if (declared.statics().has(key)) {
            return declared.statics().get(key);
        }
        if (key instanceof StringValue name) {
            if (declared instanceof TypeValue descriptor) {
                return TypeMemberFunction.of(descriptor, name.value(), span, context);
            }
            Value member = memberValue(declared, key, span, context);
            if (member != null) {
                return member;
            }
        }
        // Промах по классу отвечает null, как и промах по объекту: 'if (C.factory)'
        // должно просто работать.
        return declared.statics().get(key);
    }

    /**
     * Имя модуля. В отличие от объекта, отсутствующее имя — ошибка, а не {@code null}:
     * состав модуля задан его файлом и автору известен, поэтому {@code m.add} с опечаткой
     * стоит назвать здесь, а не через два шага, когда {@code null} попробуют вызвать.
     */
    private Value member(ModuleValue module, Value key, Span span, ExecutionContext context) {
        if (!(key instanceof StringValue name)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span, "имя в модуле '" + module.name()
                    + "' задаётся строкой, а здесь " + key.type().title() + " (" + key + ")");
        }
        Value value = module.get(name.value());
        if (value != null) {
            return value;
        }
        // Члены модуля спрашиваются после его имён — общее правило «данные раньше
        // членов». Надёжный путь, когда имя занято, — Module.names(m).
        Value member = memberValue(module, key, span, context);
        if (member != null) {
            return member;
        }
        throw new WdlRuntimeError(ErrorKind.NAME, span, "в модуле '" + module.name() + "' нет имени '"
                + name.value() + "'");
    }

    /**
     * Свойство класса этого экземпляра или {@code null}.
     * <p>
     * Ищется <b>после</b> собственных полей и <b>до</b> методов — тем же порядком,
     * что при поиске голого имени внутри метода: одно правило на две записи, иначе
     * {@code имя} и {@code this.имя} разошлись бы. Одноимённого поля при этом не бывает:
     * свойство и поле делят ячейку имени, и плоская таблица оставляет одно из двух.
     */
    private static Property property(InstanceObjectValue instance, Value key) {
        return key instanceof StringValue name
                ? instance.lookupFrom().property(name.value())
                : null;
    }

    /**
     * Запись в экземпляр: поле, свойство или новое поле.
     * <p>
     * Порядок тот же, что при чтении. Свойство без setter — <b>ошибка, а не заведение
     * поля рядом</b>, и это единственное место, где обращение к объекту не заводит
     * новый ключ. Причина в том, что имя уже занято: завести рядом второе значение
     * под тем же именем нельзя, а промолчать значило бы, что запись как будто прошла.
     */
    private static void writeMember(InstanceObjectValue instance, Value key, Value value,
                                    Span span, ExecutionContext context) {
        if (instance.has(key)) {
            instance.put(key, value);
            return;
        }
        Property property = property(instance, key);
        if (property == null) {
            instance.put(key, value);
            return;
        }
        if (!property.writable()) {
            throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "свойство '" + property.name()
                    + "' класса '" + instance.owner().name() + "' только для чтения: "
                    + "у него нет 'def set(value)'");
        }
        Foreign.call(span, null, null, () -> {
            property.write(instance, value, context, span);
            return null;
        });
    }

    /**
     * Метод класса, связанный с этим экземпляром, или {@code null}.
     * <p>
     * У обычной карты методов нет и быть не может — искать их там незачем, поэтому
     * и проверка на экземпляр стоит первой.
     */
    private Value method(MapValue object, Value key) {
        if (object instanceof InstanceObjectValue instance && key instanceof StringValue name) {
            return instance.lookupFrom().method(instance, name.value());
        }
        return null;
    }

    private static void write(Value container, Value key, Value value, AccessStyle style,
                              Span span, ExecutionContext context) {
        switch (container) {
            // Строковый ключ у массива — это член, а не индекс, и здесь он всегда
            // отказ: сеттеров у встроенных членов нет (присваивание в свойство —
            // действие, замаскированное под имя), а завести ключ массиву нельзя.
            case ArrayValue array -> {
                if (key instanceof StringValue name) {
                    throw readOnlyMember(array, name.value(), span);
                }
                // Отказ стоит до проверки индекса: иначе про диапазон человек услышал бы
                // «индекс должен быть целым числом», то есть про то, чего он не писал.
                if (key instanceof RangeValue range) {
                    throw noSliceWrite(range, span);
                }
                array.set(Indexes.element(array.size(), key, "массива", span), value);
            }
            case InstanceObjectValue instance -> writeMember(instance, key, value, span, context);
            case MapValue object -> object.put(key, value);
            // Запись в класс — «статическое поле»: обычная запись по ключу в значении.
            // У дескриптора типа (Number, Object, ...) статика общая на весь процесс,
            // и разрешить запись значило бы менять её всем интерпретаторам сразу —
            // поэтому staticsWritable() проверяется здесь же, до самой записи.
            case ClassValue declared -> {
                if (!declared.staticsWritable()) {
                    throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "в тип '" + declared.name()
                            + "' нельзя записать: дескриптор типа неизменяем");
                }
                declared.statics().put(key, value);
            }
            case ModuleValue module -> writeMember(module, key, value, span);
            // Строка неизменяема, и это не случайность реализации: строки лежат в ключах
            // объектов, и молчаливое изменение на месте испортило бы их.
            case StringValue string -> {
                if (key instanceof StringValue name) {
                    throw readOnlyMember(string, name.value(), span);
                }
                throw new WdlRuntimeError(ErrorKind.TYPE, span,
                        "строку нельзя изменить по индексу или срезу: строки неизменяемы");
            }
            default -> throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    "в значение типа " + container.type().title() + " нельзя записать " + how(style, key));
        }
    }

    /**
     * Запись в имя модуля.
     * <p>
     * Снаружи с модулем можно ровно то же, что можно его собственному коду: переменная
     * меняется, константа — нет. Иного правила и не выйдет объяснить — модуль и есть
     * скрипт, а запрет снаружи означал бы, что импортёр слабее автора файла без всякой
     * причины. Цена следует из однократности выполнения и та же, что у чтения: запись
     * видят все, кто импортировал модуль, — ровно как видят они и работу его функций.
     * <p>
     * Новое имя здесь не заводится, в отличие от объекта, и это та же разница, что при
     * чтении: состав модуля задан его файлом, поэтому {@code m.cout = 1} с опечаткой
     * должен назваться сразу, а не завести второе имя рядом с настоящим.
     */
    private static void writeMember(ModuleValue module, Value key, Value value, Span span) {
        if (!(key instanceof StringValue name)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span, "имя в модуле '" + module.name()
                    + "' задаётся строкой, а здесь " + key.type().title() + " (" + key + ")");
        }
        String member = name.value();
        if (!module.has(member)) {
            throw new WdlRuntimeError(ErrorKind.NAME, span, "в модуле '" + module.name() + "' нет имени '"
                    + member + "'");
        }
        if (module.isConstant(member)) {
            // Дословно то же сообщение, что у присваивания обычному имени: правило одно,
            // и оно не должно звучать по-разному в зависимости от того, откуда пришли.
            throw new WdlRuntimeError(ErrorKind.DECLARATION, span, "'" + member + "' нельзя присвоить: "
                    + "это константа, её значение задаётся один раз при объявлении");
        }
        module.set(member, value);
    }

    /**
     * Отказ записи в член: сообщение говорит, есть ли такой член вообще, — иначе
     * опечатка в имени и попытка записать существующий член выглядели бы одинаково.
     */
    private static WdlRuntimeError readOnlyMember(Value receiver, String name, Span span) {
        boolean known = BuiltinMembers.of(receiver.type()).has(name);
        return new WdlRuntimeError(ErrorKind.DECLARATION, span, known
                ? "член '" + name + "' у значения типа " + receiver.type().title()
                        + " только для чтения: у встроенных членов записи нет"
                : "в значение типа " + receiver.type().title() + " нельзя записать член '"
                        + name + "': такого члена нет, а завести новый нечем");
    }

    /**
     * Срез массива — новый массив, а не вид на исходный: {@code b = a[0..1]; b[0] = 9}
     * не трогает {@code a}, как и у {@code slice} с {@code copy}. Вид потребовал бы
     * либо второго вида массива, либо неявно разделяемой памяти — обе цены
     * несопоставимы с выгодой.
     * <p>
     * Размер берётся у снимка, а не у живого массива: между {@code size()}
     * и {@code subList} соседний поток вправе укоротить массив.
     */
    private static Value slice(ArrayValue array, RangeValue range, Span span) {
        List<Value> items = array.items();
        Indexes.Slice cut = Indexes.of(items.size(), range, "массива", span);
        return ArrayValue.of(items.subList(cut.from(), cut.to()));
    }

    /**
     * Срез строки. Меряется в UTF-16, как {@code s[i]}, {@code len} и {@link Span}:
     * срез посередине суррогатной пары даёт половину пары.
     */
    private static Value slice(StringValue string, RangeValue range, Span span) {
        String value = string.value();
        Indexes.Slice cut = Indexes.of(value.length(), range, "строки", span);
        return StringValue.of(value.substring(cut.from(), cut.to()));
    }

    /**
     * Записи в срез нет, и это не пробел: {@code a[1..3] = [x, y]} заменило бы кусок
     * массива вместе с его длиной, а присваивание по ключу длины не меняет. Такая
     * операция заслуживает собственного имени и собственного разговора про длину.
     */
    private static WdlRuntimeError noSliceWrite(RangeValue range, Span span) {
        return new WdlRuntimeError(ErrorKind.TYPE, span, "в срез " + range.display()
                + " нельзя записать: это заменило бы часть массива вместе с его длиной. "
                + "Меняйте элементы по одному или соберите новый массив");
    }

    /** Как человек написал обращение — чтобы сообщение говорило на его языке. */
    private static String how(AccessStyle style, Value key) {
        return style == AccessStyle.DOT ? "через точку ('." + key.display() + "')" : "по индексу";
    }

    private static IllegalStateException brokenTree(Span span) {
        return new IllegalStateException(
                "дерево с ошибками разбора не должно попадать в интерпретатор: " + span);
    }
}
