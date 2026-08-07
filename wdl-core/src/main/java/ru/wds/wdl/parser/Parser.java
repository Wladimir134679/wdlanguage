package ru.wds.wdl.parser;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.op.*;
import ru.wds.wdl.ast.stmt.*;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.lexer.TokenType;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.FloatValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;
import ru.wds.wdl.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Парсер: поток токенов → синтаксическое дерево.
 * <p>
 * Выражения разбираются алгоритмом Пратта (precedence climbing). Вся идея —
 * в {@link #expression(int)}: разбери начало выражения, а дальше присоединяй
 * операторы, пока они держат крепче, чем тот, что уже ждёт слева. Приоритеты
 * при этом лежат в {@link Operators} — данными, а не структурой кода.
 * <p>
 * <b>Ошибка не прерывает разбор.</b> Как и лексер, парсер складывает найденное
 * в {@link Diagnostics} и продолжает, а на месте неразобранного выражения оставляет
 * {@link ErrorExpr}. Дерево возвращается всегда: с ним работают форматтер, подсветка
 * и будущий LSP, которым битый файл — обычное дело. Интерпретатору такое дерево
 * отдавать нельзя, поэтому перед выполнением проверяется {@link Diagnostics#hasErrors()}.
 * <p>
 * Экземпляр одноразовый; точка входа — статические методы.
 */
public final class Parser {

    private final List<Token> tokens;
    private final Diagnostics diagnostics;
    private int index;

    private Parser(List<Token> tokens, Diagnostics diagnostics) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Разбирает скрипт целиком: последовательность инструкций до конца файла. */
    public static Program parseProgram(List<Token> tokens, Diagnostics diagnostics) {
        return new Parser(tokens, diagnostics).program();
    }

    /**
     * Разбирает весь поток как одно выражение и требует, чтобы после него
     * ничего не осталось.
     * <p>
     * Нужен там, где выражение вычисляют ради значения, а не ради действия: REPL,
     * будущий {@code engine.eval("a + b")}, вычисление константы в тесте.
     */
    public static Expr parseExpression(List<Token> tokens, Diagnostics diagnostics) {
        Parser parser = new Parser(tokens, diagnostics);
        Expr expr = parser.expression(0);
        parser.expectEnd();
        return expr;
    }

    // --- инструкции ----------------------------------------------------------

    private Program program() {
        List<Stmt> statements = new ArrayList<>();
        skipSeparators();
        while (!check(TokenType.EOF)) {
            int before = index;
            statements.add(statement());
            // Страховка: если инструкция не съела ни одного токена, двигаемся сами.
            ensureProgress(before);
            skipSeparators();
        }
        Span span = tokens.isEmpty()
                ? Span.NONE
                : tokens.get(0).span().to(tokens.get(tokens.size() - 1).span());
        return new Program(statements, span);
    }

    /**
     * Инструкция: присваивание или вызов.
     * <p>
     * Разбор начинается одинаково — с выражения, — и только потом решается, чем эта
     * строка оказалась. Если дальше идёт знак присваивания, разобранное выражение
     * становится целью записи; если нет — это должен быть вызов, иначе строка ничего
     * не делает и об этом надо сказать. Заглядывать вперёд не нужно: цель присваивания
     * и обычное выражение начинаются одинаково и разбираются одним и тем же кодом.
     */
    private Stmt statement() {
        Expr expr = expression(0);

        AssignOp assign = Operators.assign(peek().type());
        if (assign != null) {
            advance();
            return assignment(expr, assign);
        }

        if (expr instanceof CallExpr) {
            return new ExprStmt(expr, expr.span());
        }
        if (!(expr instanceof ErrorExpr)) {
            // Строка вида «a + 1» — почти наверняка забытое присваивание или вызов.
            diagnostics.error(expr.span(), "это выражение ничего не делает: "
                    + "инструкцией может быть вызов функции или присваивание");
        }
        synchronize();
        return new ErrorStmt(expr.span());
    }

    /**
     * Правая часть присваивания и проверка левой.
     * <p>
     * Слева допустимы только имя и обращение. Проверка тут, а не в дереве: узел
     * {@link AssignStmt} с недопустимой целью просто не должен возникать. Правая часть
     * разбирается в любом случае — чтобы одна ошибка не порождала вторую на том же месте.
     */
    private Stmt assignment(Expr target, AssignOp op) {
        Expr value = expression(0);
        Span span = target.span().to(value.span());
        if (value instanceof ErrorExpr) {
            // Правая часть не разобралась — об этом уже сказано. Дальше по строке
            // разбирать нечего: пропускаем её целиком, чтобы не сыпать производными ошибками.
            synchronize();
            return new ErrorStmt(span);
        }
        if (target instanceof VariableExpr || target instanceof AccessExpr) {
            return new AssignStmt(target, op, value, span);
        }
        if (!(target instanceof ErrorExpr)) {
            diagnostics.error(target.span(), "слева от '" + op.symbol()
                    + "' должно стоять имя переменной или обращение вида a.b или a[i]");
        }
        return new ErrorStmt(span);
    }

    /** Точка с запятой — необязательный разделитель, и подряд их может быть сколько угодно. */
    private void skipSeparators() {
        while (match(TokenType.SEMICOLON)) {
            // пропускаем
        }
    }

    /**
     * Паническое восстановление: пропустить испорченную инструкцию и продолжить
     * со следующей.
     * <p>
     * Границей служит точка с запятой или начало новой строки — здесь и пригождается
     * {@link Token#afterNewline()}. Перевод строки не влияет на грамматику и не даёт
     * токена, но человек всё же пишет инструкции по строкам, и восстанавливаться
     * разумнее по тому, как текст выглядит, а не по тому, как он разбирается.
     */
    private void synchronize() {
        while (!check(TokenType.EOF)) {
            if (check(TokenType.SEMICOLON)) {
                advance();
                return;
            }
            if (peek().afterNewline()) {
                return;
            }
            advance();
        }
    }

    // --- ядро: разбор по силе связывания -------------------------------------

    /**
     * Разбирает выражение, присоединяя операторы силой не ниже {@code minPower}.
     * <p>
     * Как это читается на примере {@code 1 + 2 * 3}: разобрали {@code 1}, увидели
     * {@code +} (сила 20) — она не ниже порога 0, значит {@code +} наш; правый операнд
     * разбираем с порогом 21, поэтому {@code *} (сила 22) достаётся ему, а не нам.
     * Получается {@code 1 + (2 * 3)}. Если бы оператор оказался слабее порога, цикл
     * просто вернул бы левую часть тому, кто вызвал, — и тот присоединил бы оператор
     * к себе.
     *
     * @param minPower минимальная сила оператора, который этот вызов имеет право забрать
     */
    private Expr expression(int minPower) {
        Expr left = prefix();
        while (true) {
            TokenType type = peek().type();

            // Обращение, вызов и условное выражение — не бинарные операторы: у первых
            // правая часть не выражение, а ключ или список аргументов, у последнего
            // операндов три.
            if (type == TokenType.DOT || type == TokenType.LBRACKET) {
                if (Operators.ACCESS < minPower) {
                    return left;
                }
                left = access(left);
                continue;
            }
            if (type == TokenType.LPAREN) {
                if (Operators.ACCESS < minPower) {
                    return left;
                }
                left = call(left);
                continue;
            }
            if (type == TokenType.QUESTION) {
                if (Operators.TERNARY < minPower) {
                    return left;
                }
                left = ternary(left);
                continue;
            }

            Operators.Infix infix = Operators.infix(type);
            if (infix == null || infix.power() < minPower) {
                return left;
            }
            advance();
            Expr right = expression(infix.rightPower());
            left = new BinaryExpr(infix.op(), left, right, left.span().to(right.span()));
        }
    }

    /**
     * Начало выражения: литерал, имя, скобка или префиксный оператор.
     * <p>
     * Операнд унарной операции разбирается с силой {@link Operators#UNARY}, а не
     * «одним примитивом»: поэтому {@code -a.x} — это {@code -(a.x)} (обращение крепче),
     * {@code -a * b} — это {@code (-a) * b} (умножение слабее), а {@code !!флаг}
     * и {@code - -x} вообще разбираются. В прошлой реализации унарный оператор
     * применялся к строго одному примитиву, и двойное отрицание просто не работало.
     */
    private Expr prefix() {
        Token token = peek();

        UnaryOp unary = Operators.prefix(token.type());
        if (unary != null) {
            advance();
            Expr operand = expression(Operators.UNARY);
            return new UnaryExpr(unary, operand, token.span().to(operand.span()));
        }

        switch (token.type()) {
            case INT, FLOAT, HEX, BIN -> {
                advance();
                return new LiteralExpr(number(token), token.span());
            }
            case STRING -> {
                advance();
                return new LiteralExpr(StringValue.of(token.text()), token.span());
            }
            case TRUE -> {
                advance();
                return new LiteralExpr(BoolValue.TRUE, token.span());
            }
            case FALSE -> {
                advance();
                return new LiteralExpr(BoolValue.FALSE, token.span());
            }
            case NULL -> {
                advance();
                return new LiteralExpr(NullValue.NULL, token.span());
            }
            case WORD -> {
                advance();
                return new VariableExpr(token.text(), token.span());
            }
            case LPAREN -> {
                return group();
            }
            case LBRACKET -> {
                return arrayLiteral();
            }
            case LBRACE -> {
                return objectLiteral();
            }
            default -> {
                diagnostics.error(token.span(), "ожидалось выражение, найдено " + describe(token));
                // Токен не съедаем: закрывающая скобка или запятая нужны тому, кто нас
                // вызвал, чтобы закончить свой список. За продвижение отвечает вызывающий.
                return new ErrorExpr(token.span());
            }
        }
    }

    /**
     * Скобочная группа. В дереве скобок не остаётся: они влияют только на форму
     * дерева, а восстановить их при печати можно по приоритетам операторов.
     */
    private Expr group() {
        advance(); // (
        Expr inner = expression(0);
        expect(TokenType.RPAREN, "закрывающую скобку ')'");
        return inner;
    }

    /**
     * Условное выражение. Правая часть разбирается с порогом самого тернарника,
     * поэтому вложенность идёт вправо: {@code a ? b : c ? d : e} — это
     * {@code a ? b : (c ? d : e)}, как и ожидает любой, кто писал такие цепочки.
     */
    private Expr ternary(Expr condition) {
        advance(); // ?
        Expr ifTrue = expression(0);
        expect(TokenType.COLON, "двоеточие ':' в условном выражении");
        Expr ifFalse = expression(Operators.TERNARY);
        return new TernaryExpr(condition, ifTrue, ifFalse, condition.span().to(ifFalse.span()));
    }

    /**
     * Обращение к содержимому: {@code .имя} или {@code [выражение]}.
     * <p>
     * Обе записи дают один и тот же узел {@link AccessExpr} — точка просто избавляет
     * от кавычек: {@code точка.x} превращается в ключ-строку {@code "x"} прямо здесь,
     * и дальше по конвейеру идёт неотличимо от {@code точка["x"]}.
     * <p>
     * После точки принимается и ключевое слово: {@code ответ.match} — обращение
     * к полю с именем {@code match}, а не синтаксическая ошибка. Ключевые слова
     * особенные только там, где с них начинается конструкция; в позиции имени поля
     * двусмысленности нет, а запрещать половину словаря языка ради ничего — плохая сделка.
     */
    private Expr access(Expr target) {
        Token operator = advance(); // . или [
        if (operator.type() == TokenType.DOT) {
            Token name = peek();
            if (name.type() != TokenType.WORD && !name.type().isKeyword()) {
                diagnostics.error(name.span(), "после точки ожидалось имя поля, найдено " + describe(name));
                return new AccessExpr(target, new ErrorExpr(name.span()), AccessStyle.DOT,
                        target.span().to(operator.span()));
            }
            advance();
            Expr key = new LiteralExpr(StringValue.of(name.text()), name.span());
            return new AccessExpr(target, key, AccessStyle.DOT, target.span().to(name.span()));
        }

        Expr key = expression(0);
        Token close = expect(TokenType.RBRACKET, "закрывающую скобку ']'");
        return new AccessExpr(target, key, AccessStyle.BRACKET, target.span().to(close.span()));
    }

    /**
     * Вызов: список аргументов в скобках.
     * <p>
     * Вызывается уже разобранное выражение, каким бы оно ни было, — поэтому
     * {@code f()()}, {@code точка.строкой()} и {@code обработчики[0](x)} разбираются
     * тем же кодом, без единого особого случая. Отдельного «вызова метода» в языке нет.
     */
    private Expr call(Expr callee) {
        advance(); // (
        List<Expr> arguments = new ArrayList<>();
        while (!check(TokenType.RPAREN) && !check(TokenType.EOF)) {
            int before = index;
            arguments.add(expression(0));
            if (match(TokenType.COMMA) || check(TokenType.RPAREN)) {
                continue;
            }
            diagnostics.error(peek().span(),
                    "ожидалась ',' или ')' в списке аргументов, найдено " + describe(peek()));
            ensureProgress(before);
        }
        Token close = expect(TokenType.RPAREN, "закрывающую скобку ')'");
        return new CallExpr(callee, arguments, callee.span().to(close.span()));
    }

    // --- литералы коллекций --------------------------------------------------

    /** Массив: {@code [1, 2, 3]}. Запятая после последнего элемента разрешена. */
    private Expr arrayLiteral() {
        Token open = advance(); // [
        List<Expr> elements = new ArrayList<>();
        while (!check(TokenType.RBRACKET) && !check(TokenType.EOF)) {
            int before = index;
            elements.add(expression(0));
            if (match(TokenType.COMMA) || check(TokenType.RBRACKET)) {
                continue;
            }
            diagnostics.error(peek().span(),
                    "ожидалась ',' или ']' в массиве, найдено " + describe(peek()));
            ensureProgress(before);
        }
        Token close = expect(TokenType.RBRACKET, "закрывающую скобку ']'");
        return new ArrayExpr(elements, open.span().to(close.span()));
    }

    /**
     * Объект: {@code {"ключ": 1, имя: 2, (a + b): 3}}.
     * <p>
     * Имя без кавычек — тот же сахар, что и точка в обращении, и раскрывается так же:
     * в ключ-строку. Всё остальное разбирается как обычное выражение, поэтому ключ
     * можно вычислить.
     */
    private Expr objectLiteral() {
        Token open = advance(); // {
        List<ObjectExpr.Entry> entries = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            int before = index;
            Expr key = objectKey();
            expect(TokenType.COLON, "двоеточие ':' после ключа объекта");
            Expr value = expression(0);
            entries.add(new ObjectExpr.Entry(key, value));
            if (match(TokenType.COMMA) || check(TokenType.RBRACE)) {
                continue;
            }
            diagnostics.error(peek().span(),
                    "ожидалась ',' или '}' в объекте, найдено " + describe(peek()));
            ensureProgress(before);
        }
        Token close = expect(TokenType.RBRACE, "закрывающую скобку '}'");
        return new ObjectExpr(entries, open.span().to(close.span()));
    }

    /** Ключ пары объекта: имя без кавычек, ключевое слово или любое выражение. */
    private Expr objectKey() {
        Token token = peek();
        boolean bareName = (token.type() == TokenType.WORD || token.type().isKeyword())
                && peek(1).type() == TokenType.COLON;
        if (bareName) {
            advance();
            return new LiteralExpr(StringValue.of(token.text()), token.span());
        }
        return expression(0);
    }

    // --- литералы чисел ------------------------------------------------------

    /**
     * Превращает текст числового токена в значение — один раз, при разборе.
     * Лексер уже убрал префиксы и разделители разрядов, здесь остаётся только
     * выбрать представление и поймать выход за границы.
     */
    private Value number(Token token) {
        String text = token.text();
        try {
            return switch (token.type()) {
                case INT -> IntValue.of(Long.parseLong(text));
                // Шестнадцатеричный и двоичный литерал — это про биты, а не про величину:
                // 0xFFFFFFFFFFFFFFFF законно читается как маска из 64 единиц, то есть -1.
                case HEX -> IntValue.of(Long.parseUnsignedLong(text, 16));
                case BIN -> IntValue.of(Long.parseUnsignedLong(text, 2));
                default -> floatLiteral(token, text);
            };
        } catch (NumberFormatException e) {
            if (token.type() == TokenType.INT) {
                // Целое не влезло в 64 бита: считаем приблизительно, но говорим об этом.
                diagnostics.warning(token.span(),
                        "целое число не помещается в 64 бита, оно станет вещественным и потеряет точность");
                return FloatValue.of(Double.parseDouble(text));
            }
            diagnostics.error(token.span(), "не удалось разобрать число '" + text + "'");
            return IntValue.ZERO;
        }
    }

    private Value floatLiteral(Token token, String text) {
        double value = Double.parseDouble(text);
        if (Double.isInfinite(value)) {
            diagnostics.warning(token.span(), "число слишком велико для вещественного типа, получилось inf");
        }
        return FloatValue.of(value);
    }

    // --- работа с потоком токенов --------------------------------------------

    private void expectEnd() {
        if (check(TokenType.EOF)) {
            return;
        }
        diagnostics.error(peek().span(), "лишнее после выражения: " + describe(peek())
                + ". Пока скрипт — это одно выражение; инструкции появятся дальше");
    }

    private Token peek() {
        return peek(0);
    }

    private Token peek(int offset) {
        int at = index + offset;
        // Последний токен потока — всегда EOF, и «выйти за конец» значит остаться на нём.
        return at < tokens.size() ? tokens.get(at) : tokens.get(tokens.size() - 1);
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token advance() {
        Token token = peek();
        if (index < tokens.size() - 1) {
            index++;
        }
        return token;
    }

    private boolean match(TokenType type) {
        if (!check(type)) {
            return false;
        }
        advance();
        return true;
    }

    /**
     * Требует токен и возвращает его. Если токена нет — сообщает об ошибке и отдаёт
     * текущий, не сдвигаясь: пропущенная скобка не должна съедать то, что за ней,
     * иначе одна ошибка порождает цепочку следующих.
     */
    private Token expect(TokenType type, String what) {
        Token token = peek();
        if (token.type() == type) {
            advance();
            return token;
        }
        diagnostics.error(token.span(), "ожидалось " + what + ", найдено " + describe(token));
        return token;
    }

    /**
     * Страховка от вечного цикла в разборе списков: если после неудачной итерации
     * позиция не сдвинулась, двигаем её принудительно. Без этого мусорный токен
     * внутри {@code [ ... ]} завесил бы парсер намертво.
     */
    private void ensureProgress(int positionBefore) {
        if (index == positionBefore) {
            advance();
        }
    }

    private static String describe(Token token) {
        return switch (token.type()) {
            case EOF -> "конец файла";
            case WORD -> "имя '" + token.text() + "'";
            case STRING -> "строку";
            case INT, FLOAT, HEX, BIN -> "число " + token.text();
            default -> token.type().describe();
        };
    }
}
