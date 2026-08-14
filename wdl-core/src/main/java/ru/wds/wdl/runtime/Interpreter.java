package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.op.*;
import ru.wds.wdl.ast.stmt.*;
import ru.wds.wdl.ast.visitor.*;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.embed.NativeTrait;
import ru.wds.wdl.resolve.ClassShape;
import ru.wds.wdl.resolve.LinkError;
import ru.wds.wdl.resolve.Linker;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.ScriptTraitShape;
import ru.wds.wdl.resolve.TraitShape;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.ModuleValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.StringValue;
import ru.wds.wdl.value.Value;

import java.util.ArrayList;
import java.util.List;
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
     * Перед первой инструкцией объявления функций верхнего уровня помечаются в области
     * видимости запуска — см. {@link #hoistDeclarations}.
     *
     * @return значение последней инструкции-выражения файла, см. {@link #execute}
     */
    public Value run(Program program, ExecutionContext context) {
        return entering(context, () -> execute(program, context));
    }

    /**
     * Выполняет скрипт, зная формы его классов и трейтов.
     * <p>
     * Формы собирает {@code Resolver} — стадия между парсером и интерпретатором.
     * Скрипту без классов она не нужна, поэтому есть и вариант без неё; встретив
     * объявление класса без формы, интерпретатор скажет об этом прямо, а не
     * попытается угадать.
     */
    public Value run(Program program, Resolution resolution, ExecutionContext context) {
        ExecutionContext resolved = context.withResolution(resolution);
        return entering(context, () -> execute(program, resolved));
    }

    /**
     * Открывает выполнение: берёт замок запуска на время работы.
     * <p>
     * Это граница движка — сюда управление приходит от приложения, и с этого момента
     * внутренности запуска трогает один поток. Про то, почему замок, а не конкурентные
     * структуры, — в {@link Run}.
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
        // Верхний уровень файла — тоже область: 'defer' на нём выполняется, когда файл
        // дочитан, чем бы он ни кончился.
        Deferred pending = new Deferred();
        ExecutionContext scoped = running.withDeferred(pending);
        RuntimeException flying = null;
        Value result = NullValue.NULL;
        try {
            hoistDeclarations(program, scoped);
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
     * Помечает объявления <b>верхнего уровня</b> до начала выполнения: функции, а следом
     * трейты и классы — в порядке, который составил резолвер.
     * <p>
     * Отсюда три вещи, которых иначе бы не было: функцию можно вызвать выше её объявления
     * по тексту, две функции могут вызывать друг друга, а скрипт можно писать сверху вниз —
     * сначала главное, потом вспомогательное.
     * <p>
     * Только верхний уровень, и это не упрощение, а решение: объявление внутри блока или
     * внутри другой функции принадлежит своей области видимости, и поднимать его в корень
     * значило бы протаскивать имя наружу — ровно то, чего область видимости не должна
     * допускать. Такое объявление начинает существовать, когда до него доходит выполнение.
     * <p>
     * Замыкание помеченной функции — та же область, куда её положили, поэтому повторное
     * определение при выполнении самой инструкции даёт ровно то же значение. Гасить его
     * незачем.
     * <p>
     * Константы сюда не входят: у {@code const} есть выражение-инициализатор, и вычислять
     * его здесь значило бы выполнять пользовательский код до первой инструкции скрипта.
     * Побочный эффект повторного определения, безобидный для функций, оказывается
     * полезным именно здесь: {@code const A = 1} выше по тексту и {@code def A() {}} ниже
     * дают ошибку на строке с {@code def}, потому что помеченное объявление выполняется
     * ещё раз — уже после того, как имя заморожено.
     */
    private void hoistDeclarations(Program program, ExecutionContext context) {
        for (Stmt statement : program.statements()) {
            if (statement instanceof DefDeclStmt declaration) {
                visitDefDecl(declaration, context);
            }
        }
        // Трейты и классы — списком от резолвера, где родитель стоит раньше потомка.
        // Класса, которому нужен родитель или трейт из другого файла, в этом списке нет:
        // его связывать нечем, пока не выполнится 'import', и появляется он на своей
        // строке — как класс внутри блока.
        for (Stmt statement : context.resolution().hoisted()) {
            visit(statement, context);
        }
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
            value = Operations.binary(operation, place.read(), value, stmt.span());
        }
        place.write(value);
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
                    if (iteration(stmt, context, array.get(i))) {
                        return null;
                    }
                }
            }
            case StringValue string -> {
                String text = string.value();
                for (int i = 0; i < text.length(); i++) {
                    if (iteration(stmt, context, StringValue.of(String.valueOf(text.charAt(i))))) {
                        return null;
                    }
                }
            }
            case MapValue object -> {
                for (Value key : List.copyOf(object.entries().keySet())) {
                    if (iteration(stmt, context, key)) {
                        return null;
                    }
                }
            }
            default -> throw new WdlRuntimeError(ErrorKind.TYPE, stmt.iterable().span(),
                    "перебрать можно массив, строку или объект, а здесь "
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
     * плюс проверки требований трейтов и числа аргументов родителю. Раньше форма
     * приходила готовой от резолвера, и ради этого приходилось разбирать модули
     * до запуска; теперь всё, что нужно, уже стоит в области видимости, а модуля
     * могло не быть на диске ещё секунду назад.
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
        WdlClass declared = new WdlClass(shape, context.scope(), context.unit(), parent, scripted,
                context.run(), this);
        installFactories(declared, context);
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
        WdlTrait declared = new WdlTrait(shape, context.scope(), context.unit());
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
            mixins.add(trait instanceof WdlTrait declared ? declared.shape()
                    : ((NativeTrait) trait).shape());
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
            // Класс от приложения (embed.NativeClass): его поля и методы живут в Java,
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
     * Трейт языка и трейт от приложения ({@link NativeTrait}) равноправны: у обоих
     * есть форма, а требования обоих проверяет один и тот же {@code Linker}. Разница
     * появится дальше, при сборке значения класса, и автору скрипта не видна.
     */
    private List<TraitValue> mixinsOf(ClassDeclStmt stmt, ExecutionContext context) {
        List<TraitValue> traits = new ArrayList<>(stmt.traits().size());
        for (ClassDeclStmt.TraitRef reference : stmt.traits()) {
            Value value = typeValue(reference.type(), reference.title(), "трейт",
                    reference.span(), context);
            if (value instanceof WdlTrait || value instanceof NativeTrait) {
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
     * Дальше по цепочке подсказывать уже нечего, и работает обычная диагностика
     * обращения: «у модуля нет имени X» точнее всего, что можно придумать отсюда.
     */
    private Value typeValue(Expr type, String title, String what, Span span,
                            ExecutionContext context) {
        if (type instanceof VariableExpr variable) {
            Value value = context.scope().lookup(variable.name());
            if (value == null) {
                throw new WdlRuntimeError(ErrorKind.NAME, span, "неизвестный " + what + " '" + title
                        + "': наследоваться и подмешивать можно то, что объявлено в этом же"
                        + " файле или импортировано выше по тексту");
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
        return read(module, StringValue.of(name), AccessStyle.DOT, span);
    }

    /**
     * Фабрики кладутся в сам класс: {@code def User.of(...)} — это место записи,
     * а не особый вид члена, и снаружи ровно то же самое делает присваивание
     * {@code User.of = def(...)}.
     */
    private void installFactories(WdlClass declared, ExecutionContext context) {
        for (ClassDeclStmt.Factory factory : declared.shape().factories()) {
            declared.statics().put(factory.name(), new UserFunction(factory.function(),
                    declared.closure(), declared.unit(), context.run(), this));
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
     * с ней, ровно как {@code def} или {@code const} на том же месте. По той же причине
     * импорт не помечается до выполнения ({@link #hoistDeclarations}) — он не объявление
     * верхнего уровня, а инструкция, у которой есть побочный эффект: выполнение
     * чужого файла. Поднимать её значило бы выполнять чужой код до первой строки скрипта.
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
        Value method = read(resource, StringValue.of("close"), AccessStyle.DOT, span);
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

    private Value typeOf(TryStmt.TypeRef reference, ExecutionContext context) {
        Value value = typeValue(reference.alias(), reference.name(), reference.title(),
                "класса или трейта", reference.span(), context);
        if (value instanceof ClassValue || value instanceof TraitValue) {
            return value;
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, reference.span(),
                "после 'is' в обработчике должен стоять класс или трейт, а '" + reference.title()
                        + "' — это " + value.type().title() + " (" + value.display() + ")");
    }

    /**
     * Подходит ли ошибка обработчику.
     * <p>
     * У ошибки, брошенной скриптом, значение уже есть, и вопрос решает её класс.
     * У ошибки движка значения ещё нет и создавать его ради проверки незачем: реестр
     * запуска сравнивает формы классов, ничего не материализуя.
     */
    private static boolean catches(WdlRuntimeError error, Value target, ExecutionContext context) {
        if (error.payload() instanceof InstanceObjectValue instance) {
            return instance.owner().conformsTo(target);
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
    private boolean iteration(ForEachStmt stmt, ExecutionContext context, Value element) {
        checkInterrupted(stmt.span());
        ExecutionContext step = context.nested();
        step.scope().define(stmt.name(), element);
        return runLoopBody(stmt.body(), step);
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
        Value value = context.scope().lookup(expr.name());
        if (value == null) {
            throw new WdlRuntimeError(ErrorKind.NAME, expr.span(), "переменная '" + expr.name() + "' не определена");
        }
        return value;
    }

    @Override
    public Value visitUnary(UnaryExpr expr, ExecutionContext context) {
        return Operations.unary(expr.op(), valueOf(expr.operand(), context), expr.span());
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
        return Operations.binary(expr.op(), left, right, expr.span());
    }

    @Override
    public Value visitTernary(TernaryExpr expr, ExecutionContext context) {
        return valueOf(expr.condition(), context).isTruthy()
                ? valueOf(expr.ifTrue(), context)
                : valueOf(expr.ifFalse(), context);
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
        return read(target, key, expr.style(), expr.span());
    }

    /**
     * Вызов.
     * <p>
     * Вызывается значение, а не имя: слева от скобок может стоять что угодно, что даёт
     * функцию, — переменная, поле объекта, элемент массива, результат другого вызова.
     * Число аргументов проверяется здесь, до входа в функцию, поэтому сообщение
     * одинаково для всех функций, а сама функция начинается с дела, а не с проверок.
     */
    @Override
    public Value visitCall(CallExpr expr, ExecutionContext context) {
        Value callee = valueOf(expr.callee(), context);
        if (!(callee instanceof FunctionValue function)) {
            throw new WdlRuntimeError(ErrorKind.CALL, expr.callee().span(),
                    "вызвать можно только функцию, а здесь " + callee.type().title() + " (" + callee + ")");
        }

        List<Value> arguments = new ArrayList<>(expr.arguments().size());
        for (Expr argument : expr.arguments()) {
            arguments.add(valueOf(argument, context));
        }
        if (!function.arity().accepts(arguments.size())) {
            throw new WdlRuntimeError(ErrorKind.CALL, expr.span(), "функция '" + function.name() + "' принимает "
                    + function.arity().describeArguments() + ", а передано " + arguments.size());
        }
        if (function instanceof UserFunction) {
            return function.call(context, arguments, expr.span());
        }
        // Тело написано на Java — значит, оттуда может прилететь что угодно.
        try {
            return function.call(context, arguments, expr.span());
        } catch (WdlError | ControlSignal known) {
            throw known;
        } catch (RuntimeException | LinkageError foreign) {
            throw WdlRuntimeError.fromJava(expr.span(), foreign, moduleOf(expr.callee(), context));
        }
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

        List<Value> arguments = new ArrayList<>(expr.arguments().size());
        for (Expr argument : expr.arguments()) {
            arguments.add(valueOf(argument, context));
        }
        if (!declared.arity().accepts(arguments.size())) {
            throw new WdlRuntimeError(ErrorKind.CALL, expr.span(), "класс '" + declared.name() + "' принимает "
                    + declared.arity().describeArguments() + ", а передано " + arguments.size());
        }
        // Класс, написанный на wdl, и класс, встроенный приложением, здесь неразличимы —
        // кроме одного: у второго конструктор написан на Java, и оттуда может прилететь
        // что угодно. Это единственное место, где Java-код зовётся при создании.
        if (declared instanceof WdlClass) {
            return declared.instantiate(arguments, context, expr.span());
        }
        try {
            return declared.instantiate(arguments, context, expr.span());
        } catch (WdlError | ControlSignal known) {
            throw known;
        } catch (RuntimeException | LinkageError foreign) {
            throw WdlRuntimeError.fromJava(expr.span(), foreign, moduleOf(expr.callee(), context));
        }
    }

    @Override
    public Value visitArray(ArrayExpr expr, ExecutionContext context) {
        List<Value> items = new ArrayList<>(expr.elements().size());
        for (Expr element : expr.elements()) {
            items.add(valueOf(element, context));
        }
        return ArrayValue.of(items);
    }

    @Override
    public Value visitObject(ObjectExpr expr, ExecutionContext context) {
        MapValue object = new MapValue();
        for (ObjectExpr.Entry entry : expr.entries()) {
            object.put(valueOf(entry.key(), context), valueOf(entry.value(), context));
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
        return new UserFunction(expr, context.scope(), context.unit(), context.run(), this);
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

    private record VariablePlace(Environment scope, String name, Span span) implements Place {

        @Override
        public Value read() {
            Value value = scope.lookup(name);
            if (value == null) {
                throw new WdlRuntimeError(ErrorKind.NAME, span, "переменная '" + name + "' не определена");
            }
            return value;
        }

        @Override
        public void write(Value value) {
            // Существующее имя обновляется там, где объявлено; новое заводится здесь;
            // замороженное 'const' не меняется нигде — на то оно и константа.
            switch (scope.assign(name, value)) {
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
                                  AccessStyle style, Span span) implements Place {

        @Override
        public Value read() {
            return interpreter.read(container, key, style, span);
        }

        @Override
        public void write(Value value) {
            Interpreter.write(container, key, value, style, span);
        }
    }

    private Place resolvePlace(Expr target, ExecutionContext context) {
        return switch (target) {
            case VariableExpr variable -> new VariablePlace(context.scope(), variable.name(), variable.span());
            case AccessExpr access -> new ContainerPlace(
                    this,
                    valueOf(access.target(), context),
                    valueOf(access.key(), context),
                    access.style(),
                    access.span());
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
    private Value read(Value container, Value key, AccessStyle style, Span span) {
        return switch (container) {
            case ArrayValue array -> array.get(checkIndex(array.size(), key, "массива", span));
            case MapValue object -> {
                if (object.has(key)) {
                    yield object.get(key);
                }
                Value method = method(object, key);
                yield method != null ? method : NullValue.NULL;
            }
            case ClassValue declared -> declared.statics().get(key);
            case ModuleValue module -> member(module, key, span);
            case StringValue string -> StringValue.of(String.valueOf(
                    string.value().charAt(checkIndex(string.length(), key, "строки", span))));
            default -> throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    "к значению типа " + container.type().title() + " нельзя обратиться " + how(style, key));
        };
    }

    /**
     * Имя модуля. В отличие от объекта, отсутствующее имя — ошибка, а не {@code null}:
     * состав модуля задан его файлом и автору известен, поэтому {@code m.add} с опечаткой
     * стоит назвать здесь, а не через два шага, когда {@code null} попробуют вызвать.
     */
    private static Value member(ModuleValue module, Value key, Span span) {
        if (!(key instanceof StringValue name)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span, "имя в модуле '" + module.name()
                    + "' задаётся строкой, а здесь " + key.type().title() + " (" + key + ")");
        }
        Value value = module.get(name.value());
        if (value == null) {
            throw new WdlRuntimeError(ErrorKind.NAME, span, "в модуле '" + module.name() + "' нет имени '"
                    + name.value() + "'");
        }
        return value;
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

    private static void write(Value container, Value key, Value value, AccessStyle style, Span span) {
        switch (container) {
            case ArrayValue array -> array.set(checkIndex(array.size(), key, "массива", span), value);
            case MapValue object -> object.put(key, value);
            // Запись в класс — «статическое поле»: обычная запись по ключу в значении.
            case ClassValue declared -> declared.statics().put(key, value);
            case ModuleValue module -> writeMember(module, key, value, span);
            // Строка неизменяема, и это не случайность реализации: строки лежат в ключах
            // объектов, и молчаливое изменение на месте испортило бы их.
            case StringValue ignored -> throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    "строку нельзя изменить по индексу: строки неизменяемы");
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

    private static int checkIndex(int size, Value key, String what, Span span) {
        if (!(key instanceof NumberValue number) || !number.isInteger()) {
            throw new WdlRuntimeError(ErrorKind.INDEX, span, "индекс " + what + " должен быть целым числом, а здесь "
                    + key.type().title() + " (" + key + ")");
        }
        long index = number.asLong();
        if (index < 0 || index >= size) {
            throw new WdlRuntimeError(ErrorKind.INDEX, span, "индекс " + index + " вне границ " + what + " размером " + size);
        }
        return (int) index;
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
