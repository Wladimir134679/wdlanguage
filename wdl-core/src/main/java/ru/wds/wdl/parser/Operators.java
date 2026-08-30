package ru.wds.wdl.parser;

import ru.wds.wdl.ast.op.AssignOp;
import ru.wds.wdl.ast.op.BinaryOp;
import ru.wds.wdl.ast.op.UnaryOp;
import ru.wds.wdl.lexer.TokenType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Таблица операторов: что во что разбирается и что к чему сильнее прилипает.
 * <p>
 * Приоритет задан числом — «силой связывания», а не глубиной вложенности методов.
 * В прошлой реализации каждый уровень приоритета был отдельным методом парсера
 * ({@code additive} звал {@code multiplicative}, тот — {@code unary}…): полтора десятка
 * почти одинаковых методов, и добавление оператора в середину списка означало правку
 * соседних уровней. Здесь новый оператор — это одна строка в таблице.
 * <p>
 * Числа взяты с шагом 2: между любыми двумя соседними уровнями остаётся место,
 * чтобы вставить новый, ничего не перенумеровывая. Абсолютные значения смысла
 * не имеют, важен только порядок.
 * <p>
 * Порядок сознательно отличается от Си: побитовые {@code &}, {@code ^}, {@code |}
 * связывают <i>сильнее</i> сравнений, поэтому {@code флаги & МАСКА == 0} считается
 * так, как читается. В Си это классическая ловушка, повторять её незачем.
 */
final class Operators {

    // Сила связывания: чем больше, тем крепче оператор держит операнды.
    static final int TERNARY = 2;
    static final int OR = 4;
    static final int AND = 6;
    static final int EQUALITY = 8;
    static final int COMPARISON = 10;
    static final int BIT_OR = 12;
    static final int BIT_XOR = 14;
    static final int BIT_AND = 16;
    /**
     * Диапазон {@code ..} — <b>сильнее сравнений</b>, иначе {@code x in 1..5}
     * разобралось бы как {@code (x in 1)..5}, и <b>слабее арифметики и сдвигов</b>,
     * чтобы {@code 0..n - 1} значило {@code 0..(n - 1)}. Место между {@link #BIT_AND}
     * и {@link #SHIFT} было свободно: шаг 2 в таблице заводился ровно для этого.
     */
    static final int RANGE = 17;
    static final int SHIFT = 18;
    static final int ADDITIVE = 20;
    static final int MULTIPLICATIVE = 22;
    static final int UNARY = 26;
    /**
     * Обращение {@code .x}, {@code [i]} и вызов {@code f(x)} — сильнее всего:
     * {@code -a.x} это {@code -(a.x)}, а {@code f(1) + 2} — {@code (f(1)) + 2}.
     */
    static final int ACCESS = 30;

    // Заполняются один раз и наружу отдаются только для чтения: изменяемого
    // статического состояния в ядре не должно быть даже в мелочах.
    private static final Map<TokenType, Infix> INFIX;
    private static final Map<TokenType, UnaryOp> PREFIX;
    private static final Map<TokenType, AssignOp> ASSIGN;
    private static final Map<TokenType, BinaryOp> NEGATED;

    static {
        Map<TokenType, AssignOp> assign = new EnumMap<>(TokenType.class);
        assign.put(TokenType.ASSIGN, AssignOp.ASSIGN);
        assign.put(TokenType.PLUSASSIGN, AssignOp.ADD);
        assign.put(TokenType.MINUSASSIGN, AssignOp.SUBTRACT);
        assign.put(TokenType.STARASSIGN, AssignOp.MULTIPLY);
        assign.put(TokenType.SLASHASSIGN, AssignOp.DIVIDE);
        assign.put(TokenType.PERCENTASSIGN, AssignOp.REMAINDER);
        assign.put(TokenType.AMPASSIGN, AssignOp.BIT_AND);
        assign.put(TokenType.BARASSIGN, AssignOp.BIT_OR);
        assign.put(TokenType.CARETASSIGN, AssignOp.BIT_XOR);
        assign.put(TokenType.SHLASSIGN, AssignOp.SHIFT_LEFT);
        assign.put(TokenType.SHRASSIGN, AssignOp.SHIFT_RIGHT);
        assign.put(TokenType.USHRASSIGN, AssignOp.SHIFT_RIGHT_UNSIGNED);
        ASSIGN = Collections.unmodifiableMap(assign);

        Map<TokenType, Infix> infix = new EnumMap<>(TokenType.class);
        put(infix, TokenType.OROR, BinaryOp.OR, OR);
        put(infix, TokenType.ANDAND, BinaryOp.AND, AND);

        put(infix, TokenType.BAR, BinaryOp.BIT_OR, BIT_OR);
        put(infix, TokenType.CARET, BinaryOp.BIT_XOR, BIT_XOR);
        put(infix, TokenType.AMP, BinaryOp.BIT_AND, BIT_AND);

        put(infix, TokenType.EQ, BinaryOp.EQUAL, EQUALITY);
        put(infix, TokenType.NOTEQ, BinaryOp.NOT_EQUAL, EQUALITY);

        put(infix, TokenType.LT, BinaryOp.LESS, COMPARISON);
        put(infix, TokenType.LTEQ, BinaryOp.LESS_EQUAL, COMPARISON);
        put(infix, TokenType.GT, BinaryOp.GREATER, COMPARISON);
        put(infix, TokenType.GTEQ, BinaryOp.GREATER_EQUAL, COMPARISON);

        // 'is' — тоже сравнение: 'фигура is Круг == истина' читается как сравнение
        // ответа, а 'фигура is Круг && ...' не требует скобок.
        put(infix, TokenType.IS, BinaryOp.IS, COMPARISON);

        // Принадлежность стоит там же, где 'is', и по тем же причинам:
        // 'role in roles == true' читается как сравнение ответа, а 'a in b && c'
        // не требует скобок.
        put(infix, TokenType.IN, BinaryOp.IN, COMPARISON);
        put(infix, TokenType.HAS, BinaryOp.HAS, COMPARISON);

        put(infix, TokenType.DOTDOT, BinaryOp.RANGE, RANGE);

        put(infix, TokenType.SHL, BinaryOp.SHIFT_LEFT, SHIFT);
        put(infix, TokenType.SHR, BinaryOp.SHIFT_RIGHT, SHIFT);
        put(infix, TokenType.USHR, BinaryOp.SHIFT_RIGHT_UNSIGNED, SHIFT);

        put(infix, TokenType.PLUS, BinaryOp.ADD, ADDITIVE);
        put(infix, TokenType.MINUS, BinaryOp.SUBTRACT, ADDITIVE);

        put(infix, TokenType.STAR, BinaryOp.MULTIPLY, MULTIPLICATIVE);
        put(infix, TokenType.SLASH, BinaryOp.DIVIDE, MULTIPLICATIVE);
        put(infix, TokenType.PERCENT, BinaryOp.REMAINDER, MULTIPLICATIVE);
        INFIX = Collections.unmodifiableMap(infix);

        Map<TokenType, UnaryOp> prefix = new EnumMap<>(TokenType.class);
        prefix.put(TokenType.MINUS, UnaryOp.NEGATE);
        prefix.put(TokenType.PLUS, UnaryOp.PLUS);
        prefix.put(TokenType.NOT, UnaryOp.NOT);
        prefix.put(TokenType.TILDE, UnaryOp.COMPLEMENT);
        PREFIX = Collections.unmodifiableMap(prefix);

        Map<TokenType, BinaryOp> negated = new EnumMap<>(TokenType.class);
        negated.put(TokenType.IS, BinaryOp.NOT_IS);
        negated.put(TokenType.IN, BinaryOp.NOT_IN);
        negated.put(TokenType.HAS, BinaryOp.NOT_HAS);
        NEGATED = Collections.unmodifiableMap(negated);
    }

    private Operators() {
    }

    private static void put(Map<TokenType, Infix> table, TokenType token, BinaryOp op, int power) {
        table.put(token, new Infix(op, power, false));
    }

    /** Бинарный оператор для токена или {@code null}, если токен им не является. */
    static Infix infix(TokenType type) {
        return INFIX.get(type);
    }

    /** Префиксный оператор для токена или {@code null}. */
    static UnaryOp prefix(TokenType type) {
        return PREFIX.get(type);
    }

    /**
     * Отрицающая операция для слова после {@code !}: {@code !is}, {@code !in},
     * {@code !has}. Для всего остального — {@code null}.
     * <p>
     * Два токена, а не одна лексема: {@code !} и слово лексер слить не может — между
     * ними бывает пробел, — а парсеру это стоит одной проверки в инфиксной позиции.
     * Силу связывания отдельно задавать не надо: она берётся у той же операции
     * без отрицания, потому что это она и есть, только с обратным ответом.
     */
    static BinaryOp negated(TokenType type) {
        return NEGATED.get(type);
    }

    /**
     * Вид присваивания для токена или {@code null}.
     * <p>
     * Присваивания нет в таблице приоритетов, потому что оно не выражение: его
     * разбирает {@code statement()}, увидев такой токен после уже разобранного
     * выражения. Так {@code if (x = 5)} становится невозможным по построению.
     */
    static AssignOp assign(TokenType type) {
        return ASSIGN.get(type);
    }

    /**
     * Описание бинарного оператора для парсера.
     *
     * @param op               операция в дереве
     * @param power            сила связывания
     * @param rightAssociative правоассоциативен ли оператор
     */
    record Infix(BinaryOp op, int power, boolean rightAssociative) {

        /**
         * С какой минимальной силой разбирать правый операнд.
         * <p>
         * Здесь и заключается вся ассоциативность. Для левоассоциативного оператора
         * порог на единицу выше собственной силы: встретив второй такой же оператор,
         * разбор правого операнда остановится, и {@code a - b - c} сложится в
         * {@code (a - b) - c}. Для правоассоциативного порог равен силе — тот же
         * пример дал бы {@code a - (b - c)}.
         */
        int rightPower() {
            return rightAssociative ? power : power + 1;
        }
    }
}
