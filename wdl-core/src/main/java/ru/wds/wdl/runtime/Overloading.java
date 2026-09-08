package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.op.BinaryOp;
import ru.wds.wdl.ast.op.Overloads;
import ru.wds.wdl.ast.op.UnaryOp;
import ru.wds.wdl.runtime.members.BuiltinMembers;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.FunctionValue;
import ru.wds.wdl.value.Member;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;

/**
 * Перегрузка операторов: единственное место, где у значения спрашивают оператор.
 * <p>
 * <b>Ядро важнее.</b> {@code 2 + 2} не делает ни одного поиска члена: сложение чисел
 * считает {@link Operations}, а член спрашивается ровно там, где ядро ответило
 * «не умею», — и если члена нет, летит та же ошибка, что и раньше. Отсюда три
 * следствия. Перегрузка ничего не стоит на горячем пути. Арифметику чисел не сломать
 * ни из какого модуля: таблица членов принадлежит запуску целиком, и спроси мы член
 * первым, одна библиотека испортила бы умножение всей программе. И приоритетов
 * не существует вовсе — конфликта нет, а значит нет и правил его разрешения.
 * <p>
 * <b>Два исключения, и оба об одном.</b> {@code ==} и {@code in} у экземпляра класса
 * спрашивают член <b>раньше</b> ядра. Не потому, что так удобнее, а потому, что ядро
 * на них не падает: экземпляр — это объект, и ядро молча сравнит его по ссылке
 * и молча поищет среди его полей. Запасному пути тут нечего ловить, а молчаливый
 * ответ не о том хуже ошибки. Упорядочивание исключения не требует: на паре
 * экземпляров оно честно сдаётся.
 * <p>
 * <b>Почему отдельный класс, а не метод интерпретатора.</b> Оператор спрашивают трое:
 * вычисление выражения, сортировка массива ({@code a.sort()}) и члены, отвечающие
 * на тот же вопрос другими словами ({@code a.contains(x)}, {@code obj.has(k)}).
 * Живи правило в {@code Interpreter}, у членов не было бы к нему доступа — и
 * {@code a.sort()} разошёлся бы с {@code a[0] < a[1]} на первом же массиве
 * экземпляров. Всё, что для этого нужно от среды, приходит одним
 * {@link CallContext}.
 */
public final class Overloading {

    private Overloading() {
    }

    // --- бинарные операции ---------------------------------------------------

    /**
     * Значение бинарного выражения: ядро, потом оператор у значений, потом ошибка.
     * <p>
     * Цена решения честная, и её надо знать: <b>переопределить то, что ядро уже
     * умеет, нельзя</b>. {@code extend Number} с оператором {@code *} объявляется,
     * но срабатывает только там, где второй операнд не число.
     */
    public static Value binary(BinaryOp op, Value left, Value right, Span span, CallContext context) {
        return switch (op) {
            case EQUAL -> BoolValue.of(equal(left, right, span, context));
            case NOT_EQUAL -> BoolValue.of(!equal(left, right, span, context));
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> comparison(op, left, right, span, context);
            case IN, NOT_IN -> membership(op, right, left, span, context);
            case HAS, NOT_HAS -> membership(op, left, right, span, context);
            default -> direct(op, left, right, span, context);
        };
    }

    /**
     * Операция, у которой ответ члена и есть ответ выражения: арифметика, биты,
     * диапазон. Здесь правило запасного пути видно целиком.
     */
    private static Value direct(BinaryOp op, Value left, Value right, Span span, CallContext context) {
        Value core = Operations.tryBinary(op, left, right, span);
        if (core != null) {
            return core;
        }
        if (op.overloadable()) {
            FunctionValue own = operator(left, op.member(), context);
            if (own != null) {
                return apply(own, right, op.symbol(), span, context);
            }
            // Зеркало: получатель справа, параметром приходит левый операнд, а тело
            // возвращает ответ всего выражения так, как оно написано.
            FunctionValue mirror = operator(right, Overloads.mirrorName(op.member()), context);
            if (mirror != null) {
                return apply(mirror, left, op.symbol(), span, context);
            }
        }
        return noOperator(op, left, right, span);
    }

    // --- равенство -----------------------------------------------------------

    /**
     * Равны ли значения — с учётом члена {@code `==`}.
     * <p>
     * <b>Единственное исключение из правила запасного пути.</b> Ядро на равенстве
     * не падает: оно сравнивает по ссылке и молча отвечает {@code false}, — значит
     * ловить нечего. Поэтому правило такое: ядро отвечает само, только когда оба
     * операнда — числа, строки, логические или {@code null}; во всех прочих случаях
     * сперва спрашивается {@code `==`}, и лишь если его нет — сравнение по ссылке,
     * как всегда.
     */
    public static boolean equal(Value left, Value right, Span span, CallContext context) {
        if (!plain(left) || !plain(right)) {
            FunctionValue own = operator(left, BinaryOp.EQUAL.member(), context);
            if (own != null) {
                return apply(own, right, "==", span, context).isTruthy();
            }
            FunctionValue mirror = operator(right,
                    Overloads.mirrorName(BinaryOp.EQUAL.member()), context);
            if (mirror != null) {
                return apply(mirror, left, "==", span, context).isTruthy();
            }
        }
        return Operations.equal(left, right);
    }

    /**
     * Значение, на которое у ядра есть свой ответ о равенстве: число, строка,
     * логическое, {@code null}. Всё остальное — «сначала спроси класс».
     */
    private static boolean plain(Value value) {
        return value instanceof NumberValue || value instanceof StringValue
                || value instanceof BoolValue || value == NullValue.NULL;
    }

    // --- упорядочивание ------------------------------------------------------

    private static Value comparison(BinaryOp op, Value left, Value right, Span span,
                                    CallContext context) {
        Integer order = ordering(left, right, span, context);
        if (order == null) {
            return noOperator(op, left, right, span);
        }
        return BoolValue.of(switch (op) {
            case LESS -> order < 0;
            case LESS_EQUAL -> order <= 0;
            case GREATER -> order > 0;
            case GREATER_EQUAL -> order >= 0;
            default -> throw new IllegalArgumentException("не операция сравнения: " + op);
        });
    }

    /**
     * Порядок двух значений: ядро, потом член {@code `<=>`}, потом зеркальный.
     * <p>
     * <b>Один член на четыре сравнения и на сортировку.</b> Требуй язык четыре метода
     * — они однажды разошлись бы между собой, а {@code a.sort()} разошёлся бы
     * с {@code a[0] < a[1]}, и объяснить такое расхождение нечем.
     *
     * @return отрицательное, ноль или положительное; {@code null}, если упорядочить
     *         эти значения не умеет никто
     */
    public static Integer ordering(Value left, Value right, Span span, CallContext context) {
        Integer core = Operations.ordering(left, right);
        if (core != null) {
            return core;
        }
        FunctionValue own = operator(left, Overloads.ORDER, context);
        if (own != null) {
            return sign(apply(own, right, Overloads.ORDER, span, context), left, span);
        }
        FunctionValue mirror = operator(right, Overloads.mirrorName(Overloads.ORDER), context);
        if (mirror != null) {
            return sign(apply(mirror, left, Overloads.ORDER, span, context), right, span);
        }
        return null;
    }

    /**
     * Порядок или ошибка — там, где сравнение обязано ответить: у {@code a.sort()}
     * и у оператора.
     */
    public static int order(Value left, Value right, Span span, CallContext context) {
        Integer order = ordering(left, right, span, context);
        if (order == null) {
            noOperator(BinaryOp.LESS, left, right, span);
        }
        return order;
    }

    /**
     * Ответ {@code `<=>`} обязан быть числом.
     * <p>
     * Иначе сравнение молча стало бы «истинным объектом», и {@code if (a < b)} начало
     * бы врать — а найти такую ошибку по последствиям почти невозможно.
     */
    private static int sign(Value answer, Value receiver, Span span) {
        if (!(answer instanceof NumberValue number)) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span, "оператор '" + Overloads.ORDER
                    + "' у " + title(receiver) + " вернул " + answer.type().title()
                    + ", а сравнение требует число");
        }
        return Double.compare(number.asDouble(), 0.0);
    }

    // --- принадлежность ------------------------------------------------------

    private static Value membership(BinaryOp op, Value container, Value item, Span span,
                                    CallContext context) {
        Boolean found = containment(container, item, span, context);
        if (found == null) {
            return noOperator(op, op == BinaryOp.IN || op == BinaryOp.NOT_IN ? item : container,
                    op == BinaryOp.IN || op == BinaryOp.NOT_IN ? container : item, span);
        }
        boolean negated = op == BinaryOp.NOT_IN || op == BinaryOp.NOT_HAS;
        return BoolValue.of(negated != found);
    }

    /**
     * Содержит ли контейнер это значение — с учётом члена {@code `in`}.
     * <p>
     * <b>Член объявляется на контейнере и один на четыре записи</b>: {@code x in c},
     * {@code c has x} и оба отрицания — это одно отношение, записанное с разных
     * сторон, и второй способ ответить на тот же вопрос породил бы два разных ответа.
     * Поэтому зеркала у {@code `in`} нет вовсе.
     * <p>
     * У экземпляра член спрашивается <b>раньше ядра</b> — второе исключение
     * из правила запасного пути, и по той же причине, что у {@code ==}: экземпляр это
     * объект, и ядро молча ответило бы про его поля.
     * <p>
     * <b>Перегрузка {@code `in`} не делает класс перебираемым.</b> {@code for (x in c)} —
     * это протокол перебора, отдельная вещь, и здесь она не появляется.
     *
     * @return {@code null}, если искать в таком не умеет никто
     */
    public static Boolean containment(Value container, Value item, Span span, CallContext context) {
        if (container instanceof InstanceObjectValue) {
            FunctionValue member = operator(container, BinaryOp.IN.member(), context);
            if (member != null) {
                return apply(member, item, BinaryOp.IN.member(), span, context).isTruthy();
            }
        }
        // Элементы массива сравниваются тем же равенством, что и оператор '==':
        // иначе 'p in points' и 'p == points[0]' разошлись бы на классе с '=='.
        if (container instanceof ArrayValue array) {
            for (Value element : array.items()) {
                if (equal(element, item, span, context)) {
                    return true;
                }
            }
            return false;
        }
        Boolean core = Operations.tryContains(container, item, span);
        if (core != null) {
            return core;
        }
        FunctionValue member = operator(container, BinaryOp.IN.member(), context);
        return member == null
                ? null
                : apply(member, item, BinaryOp.IN.member(), span, context).isTruthy();
    }

    /** Принадлежность или ошибка — там, где ответ обязателен: у члена {@code contains}. */
    public static boolean contains(Value container, Value item, Span span, CallContext context) {
        Boolean found = containment(container, item, span, context);
        if (found == null) {
            noOperator(BinaryOp.IN, item, container, span);
        }
        return found;
    }

    // --- унарные -------------------------------------------------------------

    /**
     * Значение унарного выражения: ядро, потом член без параметров, потом ошибка.
     * <p>
     * Унарный член отличается от бинарного <b>арностью</b>, а не именем, поэтому
     * и ключ у него свой ({@link Overloads#unaryName}): {@code def `-`()} и
     * {@code def `-`(right)} — два разных члена одного класса. Зеркала у унарного
     * не бывает: операнд один, и переворачивать нечего.
     */
    public static Value unary(UnaryOp op, Value value, Span span, CallContext context) {
        Value core = Operations.tryUnary(op, value, span);
        if (core != null) {
            return core;
        }
        if (op.overloadable()) {
            FunctionValue member = operator(value, Overloads.unaryName(op.member()), context);
            if (member != null) {
                if (!member.arity().accepts(0)) {
                    throw new WdlRuntimeError(ErrorKind.CALL, span, "унарный оператор '"
                            + op.symbol() + "' аргументов не принимает, а объявлен так, что принимает "
                            + member.arity().describeArguments());
                }
                return member.call(context, List.of(), span);
            }
        }
        if (value instanceof InstanceObjectValue instance) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span, "у " + instance.owner().name()
                    + " нет унарного оператора '" + op.symbol() + "'");
        }
        return Operations.unsupported(op, value, span);
    }

    // --- поиск и вызов -------------------------------------------------------

    /**
     * Оператор с этим именем у значения или {@code null}.
     * <p>
     * Путей два, и оба уже написаны: собственные методы класса отдаёт
     * {@code ClassValue.method}, добавленные {@code extend} и приложением — таблица
     * запуска. Новой таблицы не заводится, и отсюда даром получается то, что операторы
     * работают у классов от приложения и у типов, открытых мостом: они реализуют тот же
     * интерфейс.
     * <p>
     * Свойство оператором не бывает: за оператором стоит вызов, а свойство читают
     * именем — это другая операция.
     */
    public static FunctionValue operator(Value receiver, String name, CallContext context) {
        if (receiver instanceof InstanceObjectValue instance) {
            FunctionValue own = instance.lookupFrom().method(instance, name);
            if (own != null) {
                return own;
            }
        }
        // Основание ядра спрашивается для порядка: имён операторов в нём нет и быть
        // не может, но правило поиска члена в языке одно, и исключений у него не заводится.
        Member builtin = BuiltinMembers.of(receiver.type()).get(name);
        Member member = builtin != null ? builtin : context.members().added(receiver.type(), name);
        if (member == null && receiver instanceof InstanceObjectValue instance) {
            member = context.members().addedForClass(instance.owner(), name);
        }
        return member == null || member.property() != null ? null : member.bind(receiver);
    }

    /**
     * Зовёт тело оператора — обычным путём вызова метода.
     * <p>
     * Не через {@link Foreign}: {@code Foreign} — граница чужого Java-кода, а оператор,
     * написанный на wdl, своим быть не перестаёт. У нативного класса тело и так идёт
     * через {@code Foreign} внутри самого класса, и оборачивать его второй раз незачем.
     * Рекурсия оператора отдельной защиты не требует — она упирается
     * в предел вложенности ({@link Limits#maxCallDepth()}), как любой вызов.
     */
    private static Value apply(FunctionValue function, Value argument, String symbol, Span span,
                               CallContext context) {
        if (!function.arity().accepts(1)) {
            throw new WdlRuntimeError(ErrorKind.CALL, span, "оператор '" + symbol
                    + "' принимает один аргумент, а объявлен так, что принимает "
                    + function.arity().describeArguments());
        }
        return function.call(context, List.of(argument), span);
    }

    /**
     * Ошибка, когда не справились ни ядро, ни операторы.
     * <p>
     * Про экземпляр говорится именем его класса и типом второго операнда — это первое,
     * что хочет знать читатель. Когда экземпляра нет ни слева, ни справа, речь и не шла
     * ни о какой перегрузке, поэтому сообщение остаётся ровно тем, каким было всегда.
     */
    private static Value noOperator(BinaryOp op, Value left, Value right, Span span) {
        if (op.overloadable() && left instanceof InstanceObjectValue instance) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span, "у " + instance.owner().name()
                    + " нет оператора '" + op.symbol() + "': справа " + right.type().title()
                    + " (" + right + ")");
        }
        if (op.overloadable() && right instanceof InstanceObjectValue instance) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span, "слева " + left.type().title()
                    + ", справа " + instance.owner().name() + ": у " + instance.owner().name()
                    + " нет " + (Overloads.allowsMirror(op.member()) ? "зеркального " : "")
                    + "оператора '" + op.symbol() + "'");
        }
        return Operations.unsupported(op, left, right, span);
    }

    /** Как назвать получателя в сообщении: класс — именем, остальное — типом. */
    private static String title(Value value) {
        return value instanceof InstanceObjectValue instance
                ? instance.owner().name()
                : value.type().title();
    }
}
