package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.AccessExpr;
import ru.wds.wdl.ast.AccessStyle;
import ru.wds.wdl.ast.ArrayExpr;
import ru.wds.wdl.ast.AssignStmt;
import ru.wds.wdl.ast.BinaryExpr;
import ru.wds.wdl.ast.BinaryOp;
import ru.wds.wdl.ast.CallExpr;
import ru.wds.wdl.ast.ErrorExpr;
import ru.wds.wdl.ast.ErrorStmt;
import ru.wds.wdl.ast.Expr;
import ru.wds.wdl.ast.ExprStmt;
import ru.wds.wdl.ast.ExprVisitor;
import ru.wds.wdl.ast.LiteralExpr;
import ru.wds.wdl.ast.ObjectExpr;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.Stmt;
import ru.wds.wdl.ast.StmtVisitor;
import ru.wds.wdl.ast.TernaryExpr;
import ru.wds.wdl.ast.UnaryExpr;
import ru.wds.wdl.ast.VariableExpr;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.ArrayValue;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.ObjectValue;
import ru.wds.wdl.value.StringValue;
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

    /** Выполняет скрипт целиком. */
    public void run(Program program, ExecutionContext context) {
        for (Stmt statement : program.statements()) {
            visit(statement, context);
        }
    }

    /**
     * Вычисляет выражение. Ошибки скрипта прилетают как {@link WdlRuntimeError}
     * с местом в исходнике.
     */
    public Value eval(Expr expr, ExecutionContext context) {
        return visit(expr, context);
    }

    // --- инструкции ----------------------------------------------------------

    @Override
    public Void visitExprStmt(ExprStmt stmt, ExecutionContext context) {
        eval(stmt.expr(), context);
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
        Value value = eval(stmt.value(), context);
        if (stmt.op().isCompound()) {
            BinaryOp operation = stmt.op().base();
            value = Operations.binary(operation, place.read(), value, stmt.span());
        }
        place.write(value);
        return null;
    }

    @Override
    public Void visitErrorStmt(ErrorStmt stmt, ExecutionContext context) {
        throw brokenTree(stmt.span());
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
        return Operations.unary(expr.op(), eval(expr.operand(), context), expr.span());
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
        Value left = eval(expr.left(), context);
        if (expr.op() == BinaryOp.AND) {
            return left.isTruthy() ? eval(expr.right(), context) : left;
        }
        if (expr.op() == BinaryOp.OR) {
            return left.isTruthy() ? left : eval(expr.right(), context);
        }
        Value right = eval(expr.right(), context);
        return Operations.binary(expr.op(), left, right, expr.span());
    }

    @Override
    public Value visitTernary(TernaryExpr expr, ExecutionContext context) {
        return eval(expr.condition(), context).isTruthy()
                ? eval(expr.ifTrue(), context)
                : eval(expr.ifFalse(), context);
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
        Value target = eval(expr.target(), context);
        Value key = eval(expr.key(), context);
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
        Value callee = eval(expr.callee(), context);
        if (!(callee instanceof FunctionValue function)) {
            throw new WdlRuntimeError(expr.callee().span(),
                    "вызвать можно только функцию, а здесь " + callee.type().title() + " (" + callee + ")");
        }

        List<Value> arguments = new ArrayList<>(expr.arguments().size());
        for (Expr argument : expr.arguments()) {
            arguments.add(eval(argument, context));
        }
        if (!function.arity().accepts(arguments.size())) {
            throw new WdlRuntimeError(expr.span(), "функция '" + function.name() + "' принимает "
                    + function.arity().describe() + " аргументов, а передано " + arguments.size());
        }
        return function.call(context, arguments, expr.span());
    }

    @Override
    public Value visitArray(ArrayExpr expr, ExecutionContext context) {
        List<Value> items = new ArrayList<>(expr.elements().size());
        for (Expr element : expr.elements()) {
            items.add(eval(element, context));
        }
        return ArrayValue.of(items);
    }

    @Override
    public Value visitObject(ObjectExpr expr, ExecutionContext context) {
        ObjectValue object = new ObjectValue();
        for (ObjectExpr.Entry entry : expr.entries()) {
            object.put(eval(entry.key(), context), eval(entry.value(), context));
        }
        return object;
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

    private record ContainerPlace(Value container, Value key, AccessStyle style, Span span) implements Place {

        @Override
        public Value read() {
            return Interpreter.read(container, key, style, span);
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
                    eval(access.target(), context),
                    eval(access.key(), context),
                    access.style(),
                    access.span());
            // Парсер других целей не пропускает: сюда можно попасть только из-за ошибки в движке.
            default -> throw new IllegalStateException("недопустимая цель присваивания: " + target);
        };
    }

    // --- чтение и запись по ключу --------------------------------------------

    private static Value read(Value container, Value key, AccessStyle style, Span span) {
        return switch (container) {
            case ArrayValue array -> array.get(checkIndex(array.size(), key, "массива", span));
            case ObjectValue object -> object.get(key);
            case StringValue string -> StringValue.of(String.valueOf(
                    string.value().charAt(checkIndex(string.length(), key, "строки", span))));
            default -> throw new WdlRuntimeError(span,
                    "к значению типа " + container.type().title() + " нельзя обратиться " + how(style, key));
        };
    }

    private static void write(Value container, Value key, Value value, AccessStyle style, Span span) {
        switch (container) {
            case ArrayValue array -> array.set(checkIndex(array.size(), key, "массива", span), value);
            case ObjectValue object -> object.put(key, value);
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
