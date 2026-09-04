package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.op.BinaryOp;
import ru.wds.wdl.ast.op.UnaryOp;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.types.RangeValue;
import ru.wds.wdl.value.types.StringValue;
import ru.wds.wdl.value.TraitValue;
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
 * <b>Каждая операция умеет ответить «не умею», не бросая.</b> Это
 * {@link #tryBinary} и {@link #tryUnary}: они возвращают {@code null} ровно там,
 * где иначе полетела бы ошибка <i>типов</i>, — и никогда там, где ошибка честная.
 * {@code p / 0} по-прежнему говорит «деление на ноль», а не «у Point нет оператора
 * '/'»: ловить бросок целиком было бы дешевле в коде и хуже для читателя, потому что
 * настоящая причина спряталась бы за общим ответом. На этой паре стоит перегрузка
 * операторов: член у класса спрашивается там, где ядро ответило {@code null}, —
 * то есть ровно там, где оно и раньше сдавалось.
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
        Value result = tryBinary(op, left, right, span);
        return result != null ? result : unsupported(op, left, right, span);
    }

    /**
     * То же самое, но с ответом {@code null} вместо ошибки типов.
     * <p>
     * {@code null} значит ровно одно: <b>ядро для таких типов операции не знает</b>.
     * Всё, что ядро знает и на чём честно останавливается, — деление на ноль,
     * побитовая операция над вещественным, поиск строки в строке по числу — бросается
     * отсюда так же, как из {@link #binary}. Разница между «не умею» и «умею, и вот
     * что не так» и есть причина, по которой этот метод отвечает значением, а не
     * ловит собственное исключение.
     */
    public static Value tryBinary(BinaryOp op, Value left, Value right, Span span) {
        return switch (op) {
            case ADD -> add(left, right);
            case SUBTRACT, MULTIPLY -> arithmetic(op, left, right);
            case DIVIDE -> divide(left, right, span);
            case REMAINDER -> remainder(left, right, span);

            case EQUAL -> BoolValue.of(equal(left, right));
            case NOT_EQUAL -> BoolValue.of(!equal(left, right));
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> compare(op, left, right);
            case IS -> BoolValue.of(is(op, left, right, span));
            case NOT_IS -> BoolValue.of(!is(op, left, right, span));

            // Принадлежность: 'in' спрашивает у правого, 'has' — у левого, а отрицания
            // отличаются от них только знаком ответа. Реализация при этом одна.
            case IN -> membership(right, left, span, false);
            case NOT_IN -> membership(right, left, span, true);
            case HAS -> membership(left, right, span, false);
            case NOT_HAS -> membership(left, right, span, true);

            case RANGE -> range(left, right);

            case BIT_AND, BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT, SHIFT_RIGHT_UNSIGNED ->
                    bitwise(op, left, right, span);

            // Ленивые операции вычисляет интерпретатор: сюда они попасть не могут.
            case AND, OR -> throw new IllegalArgumentException("операция " + op + " вычисляется лениво");
        };
    }

    /**
     * Ошибка «операция не применима к таким типам» — та же, что бросало ядро всегда.
     * <p>
     * Отдельным методом, потому что бросить её должен и тот, кто сперва спросил
     * у значений оператор и не нашёл: сообщение обязано остаться прежним, а значит
     * и собираться в одном месте.
     */
    public static Value unsupported(BinaryOp op, Value left, Value right, Span span) {
        throw switch (op) {
            case RANGE -> typeError(span, op, left, right, "границы диапазона — числа");
            case BIT_AND, BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT, SHIFT_RIGHT_UNSIGNED ->
                    typeError(span, op, left, right, "побитовые операции работают только с целыми числами");
            case IN, NOT_IN -> notContainer(right, span);
            case HAS, NOT_HAS -> notContainer(left, span);
            default -> typeError(span, op, left, right);
        };
    }

    public static Value unary(UnaryOp op, Value value, Span span) {
        Value result = tryUnary(op, value, span);
        return result != null ? result : unsupported(op, value, span);
    }

    /** Унарная операция с ответом {@code null} вместо ошибки типов — см. {@link #tryBinary}. */
    public static Value tryUnary(UnaryOp op, Value value, Span span) {
        return switch (op) {
            case NEGATE -> negate(value);
            case PLUS -> value instanceof NumberValue ? value : null;
            // Отрицание работает с любым значением: истинность определена для всех типов.
            case NOT -> BoolValue.of(!value.isTruthy());
            case COMPLEMENT -> complement(value, span);
        };
    }

    /** Ошибка унарной операции — тем же текстом, каким она была всегда. */
    public static Value unsupported(UnaryOp op, Value value, Span span) {
        String what = switch (op) {
            case NEGATE -> "унарный '-'";
            case PLUS -> "унарный '+'";
            case COMPLEMENT -> "'~'";
            case NOT -> throw new IllegalArgumentException("'!' применим к любому значению");
        };
        throw new WdlRuntimeError(ErrorKind.TYPE, span, what
                + (op == UnaryOp.COMPLEMENT ? " применим только к целым числам, а здесь "
                        : " применим только к числам, а здесь ")
                + value.type().title() + " (" + value + ")");
    }

    // --- сложение и прочая арифметика ----------------------------------------

    /**
     * Сложение — единственная операция с тремя разными смыслами, и порядок проверок
     * здесь важен. Числа складываются; если хоть один операнд строка — получается
     * конкатенация ({@code "итого: " + 5}); два массива дают новый массив.
     * Всё остальное ядру неизвестно: {@code null + 1} почти наверняка означает, что
     * выше что-то пошло не так, и молчать об этом нельзя.
     * <p>
     * <b>Строка съедает {@code +} насовсем.</b> Ядро справляется с любым значением
     * справа от строки, значит член {@code `+`} у класса строку никогда не увидит;
     * своё представление — это {@code display}, а не оператор.
     */
    private static Value add(Value left, Value right) {
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
        return null;
    }

    private static Value arithmetic(BinaryOp op, Value left, Value right) {
        if (!(left instanceof NumberValue a) || !(right instanceof NumberValue b)) {
            return null;
        }
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
     * <b>Бросается она и отсюда</b>: ноль — это не «ядро не умеет делить такие типы»,
     * и превращать его в вопрос «а нет ли у класса оператора '/'» значило бы спрятать
     * настоящую причину.
     */
    private static Value divide(Value left, Value right, Span span) {
        if (!(left instanceof NumberValue a) || !(right instanceof NumberValue b)) {
            return null;
        }
        if (a.isInteger() && b.isInteger()) {
            long x = a.asLong();
            long y = b.asLong();
            if (y == 0) {
                throw new WdlRuntimeError(ErrorKind.ARITHMETIC, span, "деление на ноль");
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
        if (!(left instanceof NumberValue a) || !(right instanceof NumberValue b)) {
            return null;
        }
        if (b.asDouble() == 0.0) {
            throw new WdlRuntimeError(ErrorKind.ARITHMETIC, span, "остаток от деления на ноль");
        }
        if (a.isInteger() && b.isInteger()) {
            return IntValue.of(a.asLong() % b.asLong());
        }
        return FloatValue.of(a.asDouble() % b.asDouble());
    }

    private static Value negate(Value value) {
        if (!(value instanceof NumberValue number)) {
            return null;
        }
        if (number.isInteger()) {
            try {
                return IntValue.of(Math.negateExact(number.asLong()));
            } catch (ArithmeticException overflow) {
                return FloatValue.of(-number.asDouble());
            }
        }
        return FloatValue.of(-number.asDouble());
    }

    /**
     * Побитовое дополнение. Не число — ядро не умеет ({@code null}); число, но
     * вещественное — умеет и отказывает, называя причину: отбрасывать дробную часть
     * молча здесь хуже всего.
     */
    private static Value complement(Value value, Span span) {
        if (!(value instanceof NumberValue number)) {
            return null;
        }
        return IntValue.of(~requireInteger(number, span, "'~'"));
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
     * всё прочее ядру неизвестно. Строки сравниваются по кодовым точкам: это
     * предсказуемо и не зависит от локали, а сортировка по правилам языка — задача
     * стандартной библиотеки, где можно указать, какого именно языка.
     */
    private static Value compare(BinaryOp op, Value left, Value right) {
        Integer result = ordering(left, right);
        if (result == null) {
            return null;
        }
        return BoolValue.of(switch (op) {
            case LESS -> result < 0;
            case LESS_EQUAL -> result <= 0;
            case GREATER -> result > 0;
            case GREATER_EQUAL -> result >= 0;
            default -> throw new IllegalArgumentException("не операция сравнения: " + op);
        });
    }

    /**
     * Порядок двух значений: то же сравнение, что стоит за {@code <} и {@code >},
     * но ответом числом.
     * <p>
     * Вынесено сюда потому, что сортировка массива обязана упорядочивать <b>ровно
     * так же</b>, как оператор. Заведи она своё сравнение — и {@code a.sort()}
     * разошёлся бы с {@code a[0] < a[1]} на первом же смешанном массиве, а объяснить
     * такое расхождение нечем.
     */
    public static int order(Value left, Value right, Span span) {
        Integer result = ordering(left, right);
        if (result == null) {
            throw typeError(span, BinaryOp.LESS, left, right);
        }
        return result;
    }

    /** Порядок или {@code null}, если ядро такие значения упорядочивать не умеет. */
    public static Integer ordering(Value left, Value right) {
        if (left instanceof NumberValue a && right instanceof NumberValue b) {
            return (a.isInteger() && b.isInteger())
                    ? Long.compare(a.asLong(), b.asLong())
                    : Double.compare(a.asDouble(), b.asDouble());
        }
        if (left instanceof StringValue a && right instanceof StringValue b) {
            return a.value().compareTo(b.value());
        }
        return null;
    }

    /**
     * Проверка класса, трейта или типа.
     * <p>
     * Слева — что угодно: для значения, которое не подходит, ответ {@code false},
     * а не ошибка. Вопрос «этот ли это класс» осмыслен для чего угодно, и ошибку
     * здесь пришлось бы обходить проверкой типа перед проверкой класса.
     * <p>
     * Справа — только класс, трейт или {@linkplain TypeValue дескриптор типа}, и
     * {@code x is 5} это ошибка, а не {@code false}: спросить «является ли значение
     * пятёркой» через {@code is} можно только по ошибке, и молчать о ней незачем.
     * <p>
     * <b>Вопрос — к правому операнду</b>, а не к левому: ответ отдаётся
     * {@link ClassValue#matches} и {@code TraitValue.matches}, а не проверяется здесь
     * инструкцией {@code instanceof}. Так у оператора остаётся ровно один механизм —
     * не два (класс и тип), — и чужая реализация {@code ClassValue}, которую напишет
     * приложение, отвечает на {@code is} по-своему, не трогая ни этот метод, ни язык.
     * Отсюда же следует, что {@code is} не перегружается: механизм расширения у него
     * уже есть, и второй заводить незачем.
     */
    private static boolean is(BinaryOp op, Value left, Value right, Span span) {
        return switch (right) {
            case ClassValue declared -> declared.matches(left);
            case TraitValue declared -> declared.matches(left);
            default -> throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    "справа от '" + op.symbol() + "' должен стоять класс, трейт или тип, а здесь "
                            + right.type().title() + " (" + right + ")");
        };
    }

    // --- диапазон ------------------------------------------------------------

    /**
     * Диапазон из двух границ. Обе обязаны быть числами: {@code "a".."z"} выглядит
     * осмысленно, но порядок строк зависит от того, о чём спросить, и молчаливого
     * ответа тут быть не должно.
     */
    private static Value range(Value left, Value right) {
        if (!(left instanceof NumberValue from) || !(right instanceof NumberValue to)) {
            return null;
        }
        return RangeValue.of(from, to);
    }

    // --- принадлежность ------------------------------------------------------

    /**
     * Содержит ли контейнер это значение — единственная реализация принадлежности
     * на весь язык.
     * <p>
     * Сюда приходят все четыре записи оператора ({@code in}, {@code has} и их
     * отрицания) и все члены, спрашивающие о том же: {@code a.contains(x)},
     * {@code s.contains(sub)}, {@code obj.has(k)}. Разведи их по разным реализациям —
     * и {@code x in a} с {@code a.contains(x)} однажды разойдутся; тот же довод,
     * по которому {@code a.size} и длина в сообщении об ошибке берут ответ из одного
     * места.
     * <p>
     * У объекта спрашивается <b>ключ</b>, а не значение: перебор объекта тоже идёт
     * по ключам, и два разных ответа на «что в объекте» язык давать не должен.
     *
     * @param container где ищем
     * @param item      что ищем
     */
    public static boolean contains(Value container, Value item, Span span) {
        Boolean found = lookup(container, item, span);
        if (found == null) {
            throw notContainer(container, span);
        }
        return found;
    }

    /** Ответ оператора принадлежности или {@code null}, если контейнер ядру неизвестен. */
    private static Value membership(Value container, Value item, Span span, boolean negated) {
        Boolean found = lookup(container, item, span);
        return found == null ? null : BoolValue.of(negated != found);
    }

    /**
     * Поиск или {@code null} — «искать в таком ядро не умеет».
     * <p>
     * Неподходящий <b>предмет</b> при подходящем контейнере — ошибка, а не
     * {@code null}: искать в строке число можно только по ошибке выше, и назвать
     * её надо здесь, а не после безуспешного поиска члена.
     */
    public static Boolean tryContains(Value container, Value item, Span span) {
        return lookup(container, item, span);
    }

    private static Boolean lookup(Value container, Value item, Span span) {
        return switch (container) {
            case ArrayValue array -> {
                for (Value element : array.items()) {
                    if (equal(element, item)) {
                        yield true;
                    }
                }
                yield false;
            }
            // В строке ищется строка: 'in' у строки значит «подстрока», и число
            // здесь почти наверняка означает ошибку выше, а не намерение.
            case StringValue text -> {
                if (!(item instanceof StringValue part)) {
                    throw new WdlRuntimeError(ErrorKind.TYPE, span,
                            "в строке ищется строка, а здесь " + item.type().title()
                                    + " (" + item + ")");
                }
                yield text.value().contains(part.value());
            }
            case MapValue object -> object.has(item);
            // У диапазона спрашивается число, и любое: вопрос «между ли» осмыслен
            // и для вещественного, а целые границы нужны только перебору.
            case RangeValue range -> {
                if (!(item instanceof NumberValue number)) {
                    throw new WdlRuntimeError(ErrorKind.TYPE, span,
                            "в диапазоне ищется число, а здесь " + item.type().title()
                                    + " (" + item + ")");
                }
                yield range.contains(number);
            }
            default -> null;
        };
    }

    private static WdlRuntimeError notContainer(Value container, Span span) {
        return new WdlRuntimeError(ErrorKind.TYPE, span,
                "искать можно в массиве, строке, объекте или диапазоне, а здесь "
                        + container.type().title() + " (" + container + ")");
    }

    // --- биты ----------------------------------------------------------------

    /**
     * Побитовые операции и сдвиги — только для целых. Вещественное здесь почти всегда
     * означает ошибку в расчётах выше, а тихое отбрасывание дробной части её прячет.
     * Сдвиг, как и в Java, берёт величину по модулю 64.
     */
    private static Value bitwise(BinaryOp op, Value left, Value right, Span span) {
        if (!(left instanceof NumberValue a) || !(right instanceof NumberValue b)) {
            return null;
        }
        if (!a.isInteger() || !b.isInteger()) {
            // Оба операнда числа, и говорить «не применима к типам число и число» бессмысленно:
            // человеку нужно знать, что помешало именно вещественное значение, и какое.
            Value fractional = a.isInteger() ? right : left;
            throw new WdlRuntimeError(ErrorKind.TYPE, span, "операция '" + op.symbol()
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

    private static long requireInteger(NumberValue value, Span span, String what) {
        if (value.isInteger()) {
            return value.asLong();
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, span, what + " применим только к целым числам, а здесь "
                + value.type().title() + " (" + value + ")");
    }

    private static WdlRuntimeError typeError(Span span, BinaryOp op, Value left, Value right) {
        return typeError(span, op, left, right, null);
    }

    private static WdlRuntimeError typeError(Span span, BinaryOp op, Value left, Value right, String hint) {
        String message = "операция '" + op.symbol() + "' не применима к типам "
                + left.type().title() + " и " + right.type().title();
        return new WdlRuntimeError(ErrorKind.TYPE, span, hint == null ? message : message + ": " + hint);
    }
}
