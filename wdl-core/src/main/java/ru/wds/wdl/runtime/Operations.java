package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.op.BinaryOp;
import ru.wds.wdl.ast.op.UnaryOp;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.types.StringValue;
import ru.wds.wdl.value.Value;

/**
 * Семантика операций над значениями: вся арифметика, сравнения и биты в одном месте.
 * <p>
 * Отдельно от интерпретатора по двум причинам. Во-первых, обход дерева и правила
 * сложения — разные задачи, и смешивать их значит получить класс, который невозможно
 * читать: в прошлой реализации сложение с его лестницей проверок типов занимало
 * четыре сотни строк прямо внутри узла дерева. Во-вторых, эти же правила понадобятся
 * свёртке констант, которая работает до всякого выполнения.
 * <p>
 * <b>Правила числовой арифметики.</b> Пока оба операнда целые — результат целый.
 * Появился вещественный — считаем в {@code double}. Переполнение {@code long}
 * переводит результат в {@code double}: молча заворачивать разряды хуже, чем
 * потерять точность, потому что заворот не видно, а потерю точности видно.
 * <p>
 * Методы статические и чистые: состояния у операций нет.
 */
public final class Operations {

    private Operations() {
    }

    /**
     * Применяет бинарную операцию. Ленивые {@code &&} и {@code ||} сюда не попадают —
     * их обрабатывает интерпретатор, потому что решение «вычислять ли правую часть»
     * принимается до вычисления, а здесь оба значения уже готовы.
     */
    public static Value binary(BinaryOp op, Value left, Value right, Span span) {
        return switch (op) {
            case ADD -> add(left, right, span);
            case SUBTRACT -> arithmetic(op, left, right, span);
            case MULTIPLY -> arithmetic(op, left, right, span);
            case DIVIDE -> divide(left, right, span);
            case REMAINDER -> remainder(left, right, span);

            case EQUAL -> BoolValue.of(equal(left, right));
            case NOT_EQUAL -> BoolValue.of(!equal(left, right));
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> compare(op, left, right, span);

            case BIT_AND, BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT, SHIFT_RIGHT_UNSIGNED ->
                    bitwise(op, left, right, span);

            // Ленивые операции вычисляет интерпретатор: сюда они попасть не могут.
            case AND, OR -> throw new IllegalArgumentException("операция " + op + " вычисляется лениво");
        };
    }

    public static Value unary(UnaryOp op, Value value, Span span) {
        return switch (op) {
            case NEGATE -> negate(value, span);
            case PLUS -> requireNumber(value, span, "унарный '+'");
            // Отрицание работает с любым значением: истинность определена для всех типов.
            case NOT -> BoolValue.of(!value.isTruthy());
            case COMPLEMENT -> IntValue.of(~requireInteger(value, span, "'~'"));
        };
    }

    // --- сложение и прочая арифметика ----------------------------------------

    /**
     * Сложение — единственная операция с тремя разными смыслами, и порядок проверок
     * здесь важен. Числа складываются; если хоть один операнд строка — получается
     * конкатенация ({@code "итого: " + 5}); два массива дают новый массив.
     * Всё остальное — ошибка: {@code null + 1} почти наверняка означает, что выше
     * что-то пошло не так, и молчать об этом нельзя.
     */
    private static Value add(Value left, Value right, Span span) {
        if (left instanceof NumberValue a && right instanceof NumberValue b) {
            if (a.isInteger() && b.isInteger()) {
                try {
                    return IntValue.of(Math.addExact(a.asLong(), b.asLong()));
                } catch (ArithmeticException overflow) {
                    return FloatValue.of(a.asDouble() + b.asDouble());
                }
            }
            return FloatValue.of(a.asDouble() + b.asDouble());
        }
        if (left instanceof StringValue || right instanceof StringValue) {
            return StringValue.of(left.display() + right.display());
        }
        if (left instanceof ArrayValue a && right instanceof ArrayValue b) {
            return ArrayValue.concat(a, b);
        }
        throw typeError(span, BinaryOp.ADD, left, right);
    }

    private static Value arithmetic(BinaryOp op, Value left, Value right, Span span) {
        NumberValue a = number(left, right, op, span, true);
        NumberValue b = number(left, right, op, span, false);
        if (a.isInteger() && b.isInteger()) {
            try {
                long x = a.asLong();
                long y = b.asLong();
                return IntValue.of(op == BinaryOp.SUBTRACT ? Math.subtractExact(x, y) : Math.multiplyExact(x, y));
            } catch (ArithmeticException overflow) {
                // Не влезло в long — продолжаем в double, чтобы не потерять знак и порядок.
                return FloatValue.of(op == BinaryOp.SUBTRACT
                        ? a.asDouble() - b.asDouble()
                        : a.asDouble() * b.asDouble());
            }
        }
        return FloatValue.of(op == BinaryOp.SUBTRACT
                ? a.asDouble() - b.asDouble()
                : a.asDouble() * b.asDouble());
    }

    /**
     * Деление целых даёт целое, только если оно точное: {@code 6 / 3} — это {@code 2},
     * а {@code 7 / 2} — {@code 3.5}, а не {@code 3}. Округление вниз в динамическом
     * языке — источник тихих ошибок в расчётах; когда понадобится именно целочисленное
     * деление, для него будет отдельная функция с говорящим именем.
     * <p>
     * Деление на ноль — всегда ошибка, в том числе для вещественных: {@code inf}
     * в середине расчёта обнаруживается через десяток строк и уже без объяснения причины.
     */
    private static Value divide(Value left, Value right, Span span) {
        NumberValue a = number(left, right, BinaryOp.DIVIDE, span, true);
        NumberValue b = number(left, right, BinaryOp.DIVIDE, span, false);
        if (a.isInteger() && b.isInteger()) {
            long x = a.asLong();
            long y = b.asLong();
            if (y == 0) {
                throw new WdlRuntimeError(span, "деление на ноль");
            }
            boolean exact = x % y == 0;
            boolean overflow = x == Long.MIN_VALUE && y == -1;
            if (exact && !overflow) {
                return IntValue.of(x / y);
            }
            return FloatValue.of((double) x / (double) y);
        }
        if (b.asDouble() == 0.0) {
            throw new WdlRuntimeError(span, "деление на ноль");
        }
        return FloatValue.of(a.asDouble() / b.asDouble());
    }

    private static Value remainder(Value left, Value right, Span span) {
        NumberValue a = number(left, right, BinaryOp.REMAINDER, span, true);
        NumberValue b = number(left, right, BinaryOp.REMAINDER, span, false);
        if (b.asDouble() == 0.0) {
            throw new WdlRuntimeError(span, "остаток от деления на ноль");
        }
        if (a.isInteger() && b.isInteger()) {
            return IntValue.of(a.asLong() % b.asLong());
        }
        return FloatValue.of(a.asDouble() % b.asDouble());
    }

    private static Value negate(Value value, Span span) {
        NumberValue number = requireNumberValue(value, span, "унарный '-'");
        if (number.isInteger()) {
            try {
                return IntValue.of(Math.negateExact(number.asLong()));
            } catch (ArithmeticException overflow) {
                return FloatValue.of(-number.asDouble());
            }
        }
        return FloatValue.of(-number.asDouble());
    }

    // --- сравнения -----------------------------------------------------------

    /**
     * Равенство без приведения типов: {@code 1 == "1"} — ложь.
     * <p>
     * Языки, где такое сравнение истинно, десятилетиями расплачиваются таблицами
     * исключений, которые никто не помнит наизусть. Числа при этом сравниваются
     * по величине независимо от внутреннего представления: {@code 1 == 1.0} — истина.
     * Массивы и объекты сравниваются по ссылке: два одинаковых с виду массива —
     * всё-таки два разных массива.
     */
    public static boolean equal(Value left, Value right) {
        if (left instanceof NumberValue a && right instanceof NumberValue b) {
            if (a.isInteger() && b.isInteger()) {
                return a.asLong() == b.asLong();
            }
            return a.asDouble() == b.asDouble();
        }
        return left.equals(right);
    }

    /**
     * Упорядочивание. Сравнивать можно числа между собой и строки между собой;
     * всё прочее — ошибка. Строки сравниваются по кодовым точкам: это предсказуемо
     * и не зависит от локали, а сортировка по правилам языка — задача стандартной
     * библиотеки, где можно указать, какого именно языка.
     */
    private static Value compare(BinaryOp op, Value left, Value right, Span span) {
        int result;
        if (left instanceof NumberValue a && right instanceof NumberValue b) {
            result = (a.isInteger() && b.isInteger())
                    ? Long.compare(a.asLong(), b.asLong())
                    : Double.compare(a.asDouble(), b.asDouble());
        } else if (left instanceof StringValue a && right instanceof StringValue b) {
            result = a.value().compareTo(b.value());
        } else {
            throw typeError(span, op, left, right);
        }
        return BoolValue.of(switch (op) {
            case LESS -> result < 0;
            case LESS_EQUAL -> result <= 0;
            case GREATER -> result > 0;
            case GREATER_EQUAL -> result >= 0;
            default -> throw new IllegalArgumentException("не операция сравнения: " + op);
        });
    }

    // --- биты ----------------------------------------------------------------

    /**
     * Побитовые операции и сдвиги — только для целых. Вещественное здесь почти всегда
     * означает ошибку в расчётах выше, а тихое отбрасывание дробной части её прячет.
     * Сдвиг, как и в Java, берёт величину по модулю 64.
     */
    private static Value bitwise(BinaryOp op, Value left, Value right, Span span) {
        if (!(left instanceof NumberValue a) || !(right instanceof NumberValue b)) {
            throw typeError(span, op, left, right, "побитовые операции работают только с целыми числами");
        }
        if (!a.isInteger() || !b.isInteger()) {
            // Оба операнда числа, и говорить «не применима к типам число и число» бессмысленно:
            // человеку нужно знать, что помешало именно вещественное значение, и какое.
            Value fractional = a.isInteger() ? right : left;
            throw new WdlRuntimeError(span, "операция '" + op.symbol()
                    + "' работает только с целыми числами, а здесь вещественное " + fractional);
        }
        long x = a.asLong();
        long y = b.asLong();
        return IntValue.of(switch (op) {
            case BIT_AND -> x & y;
            case BIT_OR -> x | y;
            case BIT_XOR -> x ^ y;
            case SHIFT_LEFT -> x << y;
            case SHIFT_RIGHT -> x >> y;
            case SHIFT_RIGHT_UNSIGNED -> x >>> y;
            default -> throw new IllegalArgumentException("не побитовая операция: " + op);
        });
    }

    // --- проверки и ошибки ---------------------------------------------------

    private static NumberValue number(Value left, Value right, BinaryOp op, Span span, boolean wantLeft) {
        Value value = wantLeft ? left : right;
        if (value instanceof NumberValue number) {
            return number;
        }
        throw typeError(span, op, left, right);
    }

    private static Value requireNumber(Value value, Span span, String what) {
        return requireNumberValue(value, span, what);
    }

    private static NumberValue requireNumberValue(Value value, Span span, String what) {
        if (value instanceof NumberValue number) {
            return number;
        }
        throw new WdlRuntimeError(span, what + " применим только к числам, а здесь "
                + value.type().title() + " (" + value + ")");
    }

    private static long requireInteger(Value value, Span span, String what) {
        if (value instanceof NumberValue number && number.isInteger()) {
            return number.asLong();
        }
        throw new WdlRuntimeError(span, what + " применим только к целым числам, а здесь "
                + value.type().title() + " (" + value + ")");
    }

    private static WdlRuntimeError typeError(Span span, BinaryOp op, Value left, Value right) {
        return typeError(span, op, left, right, null);
    }

    private static WdlRuntimeError typeError(Span span, BinaryOp op, Value left, Value right, String hint) {
        String message = "операция '" + op.symbol() + "' не применима к типам "
                + left.type().title() + " и " + right.type().title();
        return new WdlRuntimeError(span, hint == null ? message : message + ": " + hint);
    }
}
