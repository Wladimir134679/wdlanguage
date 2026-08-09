package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.op.*;
import ru.wds.wdl.ast.stmt.*;
import ru.wds.wdl.ast.visitor.*;
import ru.wds.wdl.resolve.ClassShape;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.TraitShape;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.StringValue;
import ru.wds.wdl.value.Value;

import java.util.ArrayList;
import java.util.List;

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
     */
    public void run(Program program, ExecutionContext context) {
        run(program, context.resolution(), context);
    }

    /**
     * Выполняет скрипт, зная формы его классов и типажей.
     * <p>
     * Формы собирает {@code Resolver} — стадия между парсером и интерпретатором.
     * Скрипту без классов она не нужна, поэтому есть и вариант без неё; встретив
     * объявление класса без формы, интерпретатор скажет об этом прямо, а не
     * попытается угадать.
     */
    public void run(Program program, Resolution resolution, ExecutionContext context) {
        ExecutionContext running = context.withResolution(resolution);
        try {
            hoistDeclarations(program, running);
            for (Stmt statement : program.statements()) {
                visit(statement, running);
            }
        } catch (ControlSignal signal) {
            // break, continue или return вне своей конструкции. Парсер такое не пропускает,
            // поэтому сюда можно попасть только с деревом, собранным в обход разбора, —
            // то есть из-за ошибки в движке, а не в скрипте.
            throw new IllegalStateException(
                    "сигнал управления вне цикла или функции: дерево собрано неверно", signal);
        } catch (StackOverflowError e) {
            throw stackExhausted();
        }
    }

    /**
     * Помечает объявления функций <b>верхнего уровня</b> до начала выполнения.
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
     */
    private void hoistDeclarations(Program program, ExecutionContext context) {
        for (Stmt statement : program.statements()) {
            if (statement instanceof FunDeclStmt declaration) {
                visitFunDecl(declaration, context);
            }
        }
        // Типажи первыми: они ни от чего не зависят. Классы вторыми, и каждый по пути
        // заводит своего родителя, если до него ещё не дошла очередь, — отсюда
        // и свобода порядка объявлений.
        for (Stmt statement : program.statements()) {
            if (statement instanceof TraitDeclStmt declaration) {
                visitTraitDecl(declaration, context);
            }
        }
        for (Stmt statement : program.statements()) {
            if (statement instanceof ClassDeclStmt declaration) {
                visitClassDecl(declaration, context);
            }
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
        try {
            return visit(expr, context);
        } catch (StackOverflowError e) {
            throw stackExhausted();
        }
    }

    /** Вычисление выражения внутри дерева — без страховок, их место на границе. */
    private Value valueOf(Expr expr, ExecutionContext context) {
        return visit(expr, context);
    }

    /**
     * Стек потока кончился раньше, чем счётчик вложенных вызовов
     * ({@link ExecutionContext#MAX_CALL_DEPTH}).
     * <p>
     * Счётчик — основная защита от бесконечной рекурсии, и он предсказуем: одно и то же
     * число вызовов в любом окружении. Но сколько кадров Java уходит на один вызов wdl,
     * зависит от формы тела, а сколько их вообще влезает — от размера стека потока,
     * который движку не подчиняется. Поэтому здесь и стоит вторая линия: в чужом
     * приложении скрипт обязан падать ошибкой скрипта, а не {@code StackOverflowError}
     * посреди чужого кода.
     * <p>
     * Ловится ошибка только на границе выполнения, где стек уже раскручен: собирать
     * сообщение в тот момент, когда стека нет, — верный способ получить второе
     * переполнение вместо диагностики. Места в исходнике здесь нет и быть не может —
     * его знал тот кадр, которого уже не существует.
     */
    private static WdlRuntimeError stackExhausted() {
        return new WdlRuntimeError(Span.NONE, "стек вызовов исчерпан: рекурсия оказалась глубже, "
                + "чем выдерживает поток. Проверьте условие выхода из рекурсии");
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
     * области видимости: отдельного объявления в языке нет. Существующее имя
     * присваивается там, где оно объявлено, — вложенная область не создаёт себе копию.
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
        for (Stmt statement : stmt.statements()) {
            visit(statement, inner);
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
            default -> throw new WdlRuntimeError(stmt.iterable().span(),
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
     * есть снаружи, — {@code fun} внутри функции или блока не портит внешнее имя,
     * в отличие от присваивания {@code имя = fun(...)}.
     */
    @Override
    public Void visitFunDecl(FunDeclStmt stmt, ExecutionContext context) {
        context.scope().define(stmt.name(), valueOf(stmt.function(), context));
        return null;
    }

    /**
     * Объявление класса: имя заводится в текущей области, как и у функции.
     * <p>
     * Значение собирается из формы, которую резолвер приготовил до выполнения, и
     * текущей области видимости — она станет замыканием методов. Поэтому класс,
     * объявленный внутри функции, на каждом вызове даёт новое значение, но для
     * {@code is} остаётся тем же классом: форма-то одна.
     */
    @Override
    public Void visitClassDecl(ClassDeclStmt stmt, ExecutionContext context) {
        ClassShape shape = context.resolution().classShape(stmt);
        if (shape == null) {
            throw notResolved(stmt.span(), "класс '" + stmt.name() + "'");
        }
        classOf(shape, context);
        return null;
    }

    @Override
    public Void visitTraitDecl(TraitDeclStmt stmt, ExecutionContext context) {
        TraitShape shape = context.resolution().traitShape(stmt);
        if (shape == null) {
            throw notResolved(stmt.span(), "типаж '" + stmt.name() + "'");
        }
        traitOf(shape, context);
        return null;
    }

    /**
     * Значение класса — то, что уже лежит под этим именем, или новое.
     * <p>
     * Повторно объявлять нельзя не из экономии: помеченный до выполнения класс
     * и класс, созданный заново на своей же инструкции, — два разных значения,
     * и потомок держал бы ссылку на первое, а имя указывало бы на второе.
     * Тогда {@code c is Shape} давало бы ложь при совершенно правильном скрипте.
     */
    private WdlClass classOf(ClassShape shape, ExecutionContext context) {
        if (context.scope().lookup(shape.name()) instanceof WdlClass existing
                && existing.shape() == shape) {
            return existing;
        }
        WdlClass parent = shape.parent() == null ? null : classOf(shape.parent(), context);
        List<WdlTrait> traits = new ArrayList<>(shape.traits().size());
        for (TraitShape trait : shape.traits()) {
            traits.add(traitOf(trait, context));
        }

        WdlClass declared = new WdlClass(shape, context.scope(), parent, traits, this);
        installFactories(declared, context);
        context.scope().define(shape.name(), declared);
        return declared;
    }

    private WdlTrait traitOf(TraitShape shape, ExecutionContext context) {
        if (context.scope().lookup(shape.name()) instanceof WdlTrait existing
                && existing.shape() == shape) {
            return existing;
        }
        WdlTrait declared = new WdlTrait(shape, context.scope());
        context.scope().define(shape.name(), declared);
        return declared;
    }

    /**
     * Фабрики кладутся в сам класс: {@code fun User.of(...)} — это место записи,
     * а не особый вид члена, и снаружи ровно то же самое делает присваивание
     * {@code User.of = fun(...)}.
     */
    private void installFactories(WdlClass declared, ExecutionContext context) {
        for (ClassDeclStmt.Factory factory : declared.shape().factories()) {
            declared.statics().put(factory.name(),
                    new UserFunction(factory.function(), declared.closure(), this));
        }
    }

    private static WdlRuntimeError notResolved(Span span, String what) {
        return new WdlRuntimeError(span, what + " не разобран резолвером: "
                + "программу с классами нужно провести через Resolver до выполнения");
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
     */
    private static void checkInterrupted(Span span) {
        if (Thread.currentThread().isInterrupted()) {
            throw new WdlRuntimeError(span, "выполнение прервано");
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
            throw new WdlRuntimeError(expr.span(), "переменная '" + expr.name() + "' не определена");
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
            throw new WdlRuntimeError(expr.callee().span(),
                    "вызвать можно только функцию, а здесь " + callee.type().title() + " (" + callee + ")");
        }

        List<Value> arguments = new ArrayList<>(expr.arguments().size());
        for (Expr argument : expr.arguments()) {
            arguments.add(valueOf(argument, context));
        }
        if (!function.arity().accepts(arguments.size())) {
            throw new WdlRuntimeError(expr.span(), "функция '" + function.name() + "' принимает "
                    + function.arity().describeArguments() + ", а передано " + arguments.size());
        }
        return function.call(context, arguments, expr.span());
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
            throw new WdlRuntimeError(expr.callee().span(),
                    "'" + trait.name() + "' — типаж, экземпляр создаёт класс");
        }
        if (!(target instanceof ClassValue declared)) {
            throw new WdlRuntimeError(expr.callee().span(), "создать экземпляр можно только классом, "
                    + "а здесь " + target.type().title() + " (" + target + ")");
        }

        List<Value> arguments = new ArrayList<>(expr.arguments().size());
        for (Expr argument : expr.arguments()) {
            arguments.add(valueOf(argument, context));
        }
        if (!declared.arity().accepts(arguments.size())) {
            throw new WdlRuntimeError(expr.span(), "класс '" + declared.name() + "' принимает "
                    + declared.arity().describeArguments() + ", а передано " + arguments.size());
        }
        // Класс, написанный на wdl, и класс, встроенный приложением, здесь неразличимы.
        return declared.instantiate(arguments, context, expr.span());
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
        return new UserFunction(expr, context.scope(), this);
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
                throw new WdlRuntimeError(span, "переменная '" + name + "' не определена");
            }
            return value;
        }

        @Override
        public void write(Value value) {
            // Существующее имя обновляется там, где объявлено; новое заводится здесь.
            if (!scope.assign(name, value)) {
                scope.define(name, value);
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
     * <b>Поле перекрывает метод</b>, и это не случайность: {@code p.text = fun() => "иначе"}
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
            case StringValue string -> StringValue.of(String.valueOf(
                    string.value().charAt(checkIndex(string.length(), key, "строки", span))));
            default -> throw new WdlRuntimeError(span,
                    "к значению типа " + container.type().title() + " нельзя обратиться " + how(style, key));
        };
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
            // Строка неизменяема, и это не случайность реализации: строки лежат в ключах
            // объектов, и молчаливое изменение на месте испортило бы их.
            case StringValue ignored -> throw new WdlRuntimeError(span,
                    "строку нельзя изменить по индексу: строки неизменяемы");
            default -> throw new WdlRuntimeError(span,
                    "в значение типа " + container.type().title() + " нельзя записать " + how(style, key));
        }
    }

    private static int checkIndex(int size, Value key, String what, Span span) {
        if (!(key instanceof NumberValue number) || !number.isInteger()) {
            throw new WdlRuntimeError(span, "индекс " + what + " должен быть целым числом, а здесь "
                    + key.type().title() + " (" + key + ")");
        }
        long index = number.asLong();
        if (index < 0 || index >= size) {
            throw new WdlRuntimeError(span, "индекс " + index + " вне границ " + what + " размером " + size);
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
