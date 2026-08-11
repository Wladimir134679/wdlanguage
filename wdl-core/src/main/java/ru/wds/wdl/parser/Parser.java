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
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ru.wds.wdl.parser.TokenCursor.describe;

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
 * Здесь живут инструкции и выражения — то, что связано взаимной рекурсией и потому
 * не делится: тело {@code if} это инструкция, инструкция бывает выражением, выражение
 * бывает анонимной функцией, у которой снова тело. Отдельно от этого кольца вынесено
 * то, что от него не зависит: поток токенов ({@link TokenCursor}), контекст разбора
 * и его правила ({@link ParseState}), объявления типов ({@link TypeParser}),
 * проверка значений по умолчанию ({@link DefaultValues}) и числовые литералы
 * ({@link NumberLiterals}).
 * <p>
 * Экземпляр одноразовый; точка входа — статические методы.
 */
public final class Parser {

    private final Diagnostics diagnostics;
    private final TokenCursor cursor;
    private final ParseState state;
    private final TypeParser types;

    private Parser(List<Token> tokens, Diagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.cursor = new TokenCursor(tokens, diagnostics);
        this.state = new ParseState(diagnostics);
        this.types = new TypeParser(this, cursor, state, diagnostics);
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
        parser.cursor.expectEnd();
        return expr;
    }

    // --- инструкции ----------------------------------------------------------

    private Program program() {
        List<Stmt> statements = new ArrayList<>();
        cursor.skipSeparators();
        while (!cursor.check(TokenType.EOF)) {
            int before = cursor.position();
            statements.add(statement());
            // Страховка: если инструкция не съела ни одного токена, двигаемся сами.
            cursor.ensureProgress(before);
            cursor.skipSeparators();
        }
        return new Program(statements, cursor.wholeSpan());
    }

    /**
     * Инструкция: ветвление, цикл, блок, выход из цикла — или простая инструкция.
     * <p>
     * Вид определяется первым же токеном, без заглядывания вперёд. Здесь же решается
     * вопрос, который в языках с литералом объекта решать приходится всем:
     * <b>{@code &#123;} в начале инструкции — это блок, а не объект.</b> Диспетчер
     * перехватывает фигурную скобку раньше, чем начнётся разбор выражения, поэтому
     * литерал объекта остаётся возможен только там, где ждут значение. Ни особых
     * случаев, ни отката разбора.
     */
    private Stmt statement() {
        return switch (cursor.peek().type()) {
            case LBRACE -> block();
            case IF -> ifStatement();
            case WHILE -> whileStatement();
            case FOR -> forStatement();
            case BREAK -> breakStatement();
            case CONTINUE -> continueStatement();
            case RETURN -> returnStatement();
            case THROW -> throwStatement();
            // 'try?' и 'try!' — выражения, а не конструкция с блоком, и в начале строки
            // они тоже выражения: 'try? save()' значит «вызови, ошибку проглоти».
            case TRY -> shortTryAhead() ? simpleStatement() : tryStatement();
            // 'catch' и 'finally' сами по себе инструкцией не бывают: они часть 'try'
            // и разбираются им. Отдельное сообщение здесь лучше общего «ожидалось
            // выражение» — оно называет причину, а не симптом.
            case CATCH, FINALLY -> orphanHandler();
            case DEFER -> deferStatement();
            case USE -> useStatement();
            case CONST -> constDeclaration();
            // 'fun' с именем — объявление. 'fun(' — анонимная функция, то есть выражение:
            // её разберёт simpleStatement и скажет, что такая инструкция ничего не делает.
            case FUN -> cursor.peek(1).type() == TokenType.WORD ? funDeclaration() : simpleStatement();
            case CLASS -> types.classDeclaration();
            case TRAIT -> types.traitDeclaration();
            case IMPORT -> importStatement();
            default -> simpleStatement();
        };
    }

    /**
     * Простая инструкция: присваивание или вызов.
     * <p>
     * Разбор начинается одинаково — с выражения, — и только потом решается, чем эта
     * строка оказалась. Если дальше идёт знак присваивания, разобранное выражение
     * становится целью записи; если нет — это должен быть вызов, иначе строка ничего
     * не делает и об этом надо сказать. Заглядывать вперёд не нужно: цель присваивания
     * и обычное выражение начинаются одинаково и разбираются одним и тем же кодом.
     */
    private Stmt simpleStatement() {
        Expr expr = expression(0);

        AssignOp assign = Operators.assign(cursor.peek().type());
        if (assign != null) {
            cursor.advance();
            return assignment(expr, assign);
        }

        if (doesSomething(expr)) {
            return new ExprStmt(expr, expr.span());
        }
        if (!(expr instanceof ErrorExpr)) {
            // Строка вида «a + 1» — почти наверняка забытое присваивание или вызов.
            diagnostics.error(expr.span(), "это выражение ничего не делает: "
                    + "инструкцией может быть вызов функции или присваивание");
        }
        cursor.synchronize();
        return new ErrorStmt(expr.span());
    }

    /**
     * Выражение, которое имеет смысл как целая инструкция.
     * <p>
     * Вызов и создание — единственные, которые сами по себе что-то делают: первое
     * считает и может напечатать, второе заводит объект и выполняет конструктор.
     * Короткая форма {@code try?} прозрачна: {@code try? save()} — это тот же вызов,
     * просто с проглоченной ошибкой, и запрещать его значило бы требовать переменную,
     * в которую никто не смотрит.
     */
    private static boolean doesSomething(Expr expr) {
        return switch (expr) {
            case CallExpr ignored -> true;
            case NewExpr ignored -> true;
            case TryExpr shortForm -> doesSomething(shortForm.inner());
            default -> false;
        };
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
            cursor.synchronize();
            return new ErrorStmt(span);
        }
        // this и super — имена самого объекта, а не переменные для хранения:
        // присвоить их значило бы подменить объект под ногами у выполняющегося метода.
        if (target instanceof VariableExpr variable && isSelfName(variable.name())) {
            diagnostics.error(target.span(), "'" + variable.name() + "' нельзя присвоить: "
                    + "это имя самого объекта. Поле объекта пишется как 'this." + "имя = значение'");
            return new ErrorStmt(span);
        }
        if (target instanceof VariableExpr || target instanceof AccessExpr) {
            return new AssignStmt(target, op, named(target, op, value), span);
        }
        if (!(target instanceof ErrorExpr)) {
            diagnostics.error(target.span(), "слева от '" + op.symbol()
                    + "' должно стоять имя переменной или обращение вида a.b или a[i]");
        }
        return new ErrorStmt(span);
    }

    /**
     * Даёт анонимной функции имя переменной, в которую её кладут: {@code f = fun(a) => a}.
     * <p>
     * Только ради диагностики — «функция 'f' принимает ровно 1 аргумент» вместо
     * «функция 'fun' ...». На поиск имени во время выполнения это не влияет никак:
     * функция и без того лежит в переменной, а не ищется по имени.
     */
    private static Expr named(Expr target, AssignOp op, Expr value) {
        if (op != AssignOp.ASSIGN || !(target instanceof VariableExpr variable)) {
            return value;
        }
        return named(variable.name(), value);
    }

    /** То же для {@code const f = fun(a) => a}, где имя известно и без разбора цели. */
    private static Expr named(String name, Expr value) {
        if (!(value instanceof FunctionExpr function) || function.name() != null) {
            return value;
        }
        return new FunctionExpr(name, function.params(), function.body(),
                function.style(), function.span());
    }

    /** Имя, обозначающее сам объект: присвоить такое нельзя. */
    private static boolean isSelfName(String name) {
        return name.equals(TokenType.THIS.text()) || name.equals(TokenType.SUPER.text());
    }

    // --- константы -----------------------------------------------------------

    /**
     * Объявление константы: {@code const LIMIT = 10}.
     * <p>
     * Слева допустимо только имя: заморозить можно имя, а не ячейку внутри чужого
     * значения, поэтому {@code const a.b = 1} — не «объявление поля», а ошибка.
     * Справа обязателен именно {@code =}: {@code const A += 1} читается как недописанная
     * строка, а не как объявление с операцией. Начальное значение обязательно, потому
     * что второго присваивания у константы не будет.
     */
    private Stmt constDeclaration() {
        Token keyword = cursor.advance(); // const
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(),
                    "после 'const' ожидалось имя, найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return new ErrorStmt(keyword.span());
        }
        Token name = cursor.advance();
        if (cursor.check(TokenType.DOT) || cursor.check(TokenType.LBRACKET)) {
            // Отдельное сообщение: советовать здесь 'const имя = выражение' значило бы
            // предлагать не то — автор промахнулся не значением, а левой частью.
            diagnostics.error(cursor.peek().span(), "слева от '=' в объявлении константы стоит имя: "
                    + "заморозить можно имя, а не ячейку внутри чужого значения");
            cursor.synchronize();
            return new ErrorStmt(keyword.span().to(name.span()));
        }
        if (!cursor.check(TokenType.ASSIGN)) {
            diagnostics.error(cursor.peek().span(), "константе нужно начальное значение: "
                    + "const " + name.text() + " = выражение");
            cursor.synchronize();
            return new ErrorStmt(keyword.span().to(name.span()));
        }
        cursor.advance(); // =
        Expr value = expression(0);
        if (value instanceof ErrorExpr) {
            // О невозможном выражении уже сказано; дальше по строке разбирать нечего.
            cursor.synchronize();
            return new ErrorStmt(keyword.span().to(value.span()));
        }
        return new ConstDeclStmt(name.text(), name.span(), named(name.text(), value),
                keyword.span().to(value.span()));
    }

    // --- импорт --------------------------------------------------------------

    /**
     * Импорт модуля: {@code import lib.math} или {@code import lib.math as m}.
     * <p>
     * Путь пишется двумя способами, и второй нужен потому, что первый не всё выражает.
     * Через имена — {@code import lib.math} — читается как обращение и не требует
     * кавычек; точка здесь означает вложенность каталога, а не поле, потому что
     * никакого значения тут ещё нет. Через строку — {@code import "lib/math.wdl"} —
     * годится для любого имени файла, включая те, что именами в языке быть не могут.
     * <p>
     * Ключевое слово после точки не принимается, в отличие от обращения {@code a.class}:
     * там слева уже есть значение и путаницы нет, а здесь {@code import lib.class}
     * читалось бы как начало объявления. Совет в такой ошибке один — записать строкой.
     */
    private Stmt importStatement() {
        Token keyword = cursor.advance(); // import
        Token first = cursor.peek();
        String path;
        Span pathSpan;
        if (first.type() == TokenType.STRING) {
            cursor.advance();
            path = first.text();
            pathSpan = first.span();
        } else if (first.type() == TokenType.WORD) {
            cursor.advance();
            StringBuilder segments = new StringBuilder(first.text());
            Span last = first.span();
            while (cursor.check(TokenType.DOT)) {
                cursor.advance();
                if (!cursor.check(TokenType.WORD)) {
                    diagnostics.error(cursor.peek().span(), "после точки в пути модуля ожидалось имя,"
                            + " найдено " + describe(cursor.peek()) + ". Путь с такими символами"
                            + " записывается строкой: import \"lib/имя-модуля\"");
                    cursor.synchronize();
                    return new ErrorStmt(keyword.span().to(last));
                }
                Token segment = cursor.advance();
                last = segment.span();
                segments.append('/').append(segment.text());
            }
            path = segments.toString();
            pathSpan = first.span().to(last);
        } else {
            diagnostics.error(first.span(), "после 'import' ожидался путь модуля, найдено "
                    + describe(first) + ". Путь пишется именами через точку (import lib.math)"
                    + " или строкой (import \"lib/math\")");
            cursor.synchronize();
            return new ErrorStmt(keyword.span());
        }

        if (!cursor.match(TokenType.AS)) {
            return new ImportStmt(path, pathSpan, null, Span.NONE, keyword.span().to(pathSpan));
        }
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(), "после 'as' ожидалось имя, под которым модуль"
                    + " ляжет в переменную, найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return new ErrorStmt(keyword.span().to(pathSpan));
        }
        Token alias = cursor.advance();
        return new ImportStmt(path, pathSpan, alias.text(), alias.span(),
                keyword.span().to(alias.span()));
    }

    // --- функции -------------------------------------------------------------

    /** Объявление: {@code fun имя(a, b) тело}. Имя проверено в {@link #statement()}. */
    private Stmt funDeclaration() {
        Token keyword = cursor.advance(); // fun
        Token name = cursor.advance();    // имя
        FunctionExpr function = functionRest(keyword, name.text());
        if (function.body() instanceof ReturnStmt returned && returned.value() instanceof ErrorExpr) {
            // Тело после '=>' не разобралось, и об этом уже сказано. Дальше по строке
            // разбирать нечего: пропускаем её целиком, иначе тот же токен вызовет ту же
            // ошибку второй раз — уже от следующей инструкции.
            cursor.synchronize();
            return new ErrorStmt(function.span());
        }
        return new FunDeclStmt(function, function.span());
    }

    /** Анонимная функция в позиции выражения: {@code fun(a, b) => a + b}. */
    private Expr functionExpr() {
        Token keyword = cursor.advance(); // fun
        return functionRest(keyword, null);
    }

    /**
     * Параметры и тело — всё, что у объявления и анонимной функции общее, то есть всё,
     * кроме имени. Про границу области — {@link ParseState#inFunctionBody}.
     */
    private FunctionExpr functionRest(Token keyword, String name) {
        List<FunctionExpr.Param> params = types.parameters("'fun'", true);
        return state.inFunctionBody(() -> {
            if (cursor.match(TokenType.FATARROW)) {
                // Стрелка — это return, только записанный короче. В дереве так и лежит:
                // ReturnStmt, а сама форма записи остаётся в BodyStyle для форматтера.
                Expr value = expression(0);
                Stmt body = new ReturnStmt(value, value.span());
                return new FunctionExpr(name, params, body, BodyStyle.ARROW,
                        keyword.span().to(value.span()));
            }
            Stmt body = body(name != null ? "функции '" + name + "'" : "анонимной функции");
            return new FunctionExpr(name, params, body, BodyStyle.STATEMENT,
                    keyword.span().to(body.span()));
        });
    }

    /**
     * Возврат из функции: {@code return выражение;} или {@code return;}.
     * <p>
     * Точка с запятой обязательна — почему именно так, разобрано в {@link ReturnStmt}.
     * Здесь важно следствие для разбора: решение «есть значение или нет» принимается
     * по одному текущему токену, без заглядывания вперёд и без оглядки на переносы строк.
     */
    private Stmt returnStatement() {
        Token keyword = cursor.advance(); // return
        state.requireFunction(keyword);
        state.forbidInFinally(keyword);
        Expr value = cursor.check(TokenType.SEMICOLON) ? null : expression(0);
        if (value instanceof ErrorExpr) {
            // Возвращаемое выражение не разобралось — об этом уже сказано. Дальше по строке
            // разбирать нечего: пропускаем её, чтобы не сыпать производными ошибками.
            cursor.synchronize();
            return new ErrorStmt(keyword.span().to(value.span()));
        }
        Token end = cursor.expect(TokenType.SEMICOLON, "точку с запятой ';' после 'return'");
        return new ReturnStmt(value, keyword.span().to(end.span()));
    }

    // --- ошибки --------------------------------------------------------------

    /**
     * Бросок: {@code throw new ParseError(text)}.
     * <p>
     * Точка с запятой не обязательна, в отличие от {@code return}: аргумент у
     * {@code throw} есть всегда, и решать «есть значение или нет» не приходится.
     * Что брошенное обязано быть экземпляром {@code Exception}, знает выполнение —
     * до значения парсер не добирается.
     */
    private Stmt throwStatement() {
        Token keyword = cursor.advance(); // throw
        Expr value = expression(0);
        if (value instanceof ErrorExpr) {
            cursor.synchronize();
            return new ErrorStmt(keyword.span().to(value.span()));
        }
        return new ThrowStmt(value, keyword.span().to(value.span()));
    }

    /**
     * {@code try} с обработчиками и завершающим блоком.
     * <p>
     * Тело — всегда блок: следующей строкой идёт {@code catch}, и без скобок границу
     * тела пришлось бы угадывать. По той же причине блоками записываются и сами
     * обработчики, и {@code finally}.
     * <p>
     * Обработчиков может не быть вовсе, если есть {@code finally}. А вот {@code try}
     * без того и другого не значит ничего — это просто блок, и почти наверняка
     * недописанная конструкция.
     */
    private Stmt tryStatement() {
        Token keyword = cursor.advance(); // try
        BlockStmt body = requiredBlock("тело 'try'");
        if (body == null) {
            cursor.synchronize();
            return new ErrorStmt(keyword.span());
        }

        List<TryStmt.Catch> handlers = new ArrayList<>();
        while (cursor.check(TokenType.CATCH)) {
            TryStmt.Catch handler = catchClause();
            if (handler == null) {
                break;
            }
            handlers.add(handler);
        }

        BlockStmt finallyBlock = null;
        if (cursor.check(TokenType.FINALLY)) {
            cursor.advance();
            finallyBlock = state.inFinallyBlock(() -> requiredBlock("тело 'finally'"));
        }

        if (handlers.isEmpty() && finallyBlock == null) {
            diagnostics.error(keyword.span(), "у 'try' должен быть хотя бы один 'catch' "
                    + "или 'finally': без них это обычный блок");
            return new ErrorStmt(keyword.span().to(body.span()));
        }
        return new TryStmt(body, handlers, finallyBlock, keyword.span().to(cursor.lastSpan()));
    }

    /**
     * Один обработчик: {@code catch (e is IoError, ValueError) { ... }}.
     * <p>
     * Тип после {@code is} — имя, а не выражение, как после {@code :} и {@code with}
     * в объявлении класса: тип обработчика должен быть виден глазами. Квалифицированная
     * форма работает — {@code catch (e is db.QueryError)}.
     *
     * @return {@code null}, если обработчик не разобрался; об ошибке уже сказано
     */
    private TryStmt.Catch catchClause() {
        Token keyword = cursor.advance(); // catch
        cursor.expect(TokenType.LPAREN, "открывающую скобку '(' после 'catch'");
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(), "после 'catch (' ожидалось имя, под которым"
                    + " ошибка ляжет в переменную, найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return null;
        }
        Token name = cursor.advance();

        List<TryStmt.TypeRef> handled = new ArrayList<>();
        if (cursor.match(TokenType.IS)) {
            do {
                TypeName type = types.typeName("класса или трейта ошибки");
                if (type == null) {
                    return null;
                }
                for (TryStmt.TypeRef existing : handled) {
                    if (existing.title().equals(type.title())) {
                        diagnostics.error(type.span(),
                                "тип '" + type.title() + "' в этом обработчике указан дважды");
                    }
                }
                handled.add(new TryStmt.TypeRef(type.alias(), type.name(), type.span()));
            } while (cursor.match(TokenType.COMMA));
        }
        cursor.expect(TokenType.RPAREN, "закрывающую скобку ')' после 'catch'");

        BlockStmt body = requiredBlock("тело 'catch'");
        if (body == null) {
            cursor.synchronize();
            return null;
        }
        return new TryStmt.Catch(name.text(), name.span(), handled, body,
                keyword.span().to(body.span()));
    }

    /** Блок там, где одиночная инструкция не разрешена. {@code null}, если его нет. */
    private BlockStmt requiredBlock(String what) {
        if (cursor.check(TokenType.LBRACE)) {
            return block();
        }
        diagnostics.error(cursor.peek().span(), what + " записывается блоком в фигурных скобках,"
                + " а здесь " + describe(cursor.peek()));
        return null;
    }

    /** Следом за {@code try} стоит {@code ?} или {@code !} — то есть это короткая форма. */
    private boolean shortTryAhead() {
        TokenType next = cursor.peek(1).type();
        return next == TokenType.QUESTION || next == TokenType.NOT;
    }

    /**
     * {@code try? выражение} и {@code try! выражение}.
     * <p>
     * Операнд разбирается с силой унарного оператора: обращение и вызов крепче
     * ({@code try? config.port()} — это {@code try?} вокруг всего вызова), а сложение
     * слабее ({@code try? f() + 1} — это {@code (try? f()) + 1}).
     */
    private Expr shortTry() {
        Token keyword = cursor.advance(); // try
        Token sign = cursor.advance();    // ? или !
        TryStyle style = sign.type() == TokenType.QUESTION ? TryStyle.OPTIONAL : TryStyle.FORCED;
        if (sign.span().start() != keyword.span().end()) {
            // Двусмысленности с тернарным оператором тут нет — после 'try' левого
            // операнда для '?' не существует, — но одинаковая запись двух разных вещей
            // сбивает читателя, а он здесь главный.
            diagnostics.error(keyword.span().to(sign.span()),
                    "'" + style.text() + "' пишется слитно, без пробела");
        }
        Expr inner = expression(Operators.UNARY);
        return new TryExpr(inner, style, keyword.span().to(inner.span()));
    }

    /**
     * Работа с ресурсом: {@code use (src = io.open(from), dst = io.create(to)) { ... }}.
     * <p>
     * Слева от {@code =} — имя, а не произвольная цель: {@code use} заводит имя
     * в своей области, как {@code for (x in ...)}, а не пишет в чужое значение.
     * Ресурсов может быть сколько угодно, но хотя бы один — {@code use ()} не значит
     * ничего.
     */
    private Stmt useStatement() {
        Token keyword = cursor.advance(); // use
        cursor.expect(TokenType.LPAREN, "открывающую скобку '(' после 'use'");

        List<UseStmt.Binding> resources = new ArrayList<>();
        do {
            if (!cursor.check(TokenType.WORD)) {
                diagnostics.error(cursor.peek().span(), "в 'use' ожидалось имя, под которым ресурс"
                        + " ляжет в переменную, найдено " + describe(cursor.peek()));
                cursor.synchronize();
                return new ErrorStmt(keyword.span());
            }
            Token name = cursor.advance();
            for (UseStmt.Binding existing : resources) {
                if (existing.name().equals(name.text())) {
                    diagnostics.error(name.span(), "имя '" + name.text()
                            + "' в этом 'use' уже занято");
                }
            }
            cursor.expect(TokenType.ASSIGN, "знак '=' после имени ресурса");
            Expr value = expression(0);
            if (value instanceof ErrorExpr) {
                cursor.synchronize();
                return new ErrorStmt(keyword.span().to(value.span()));
            }
            resources.add(new UseStmt.Binding(name.text(), name.span(), value,
                    name.span().to(value.span())));
        } while (cursor.match(TokenType.COMMA));

        cursor.expect(TokenType.RPAREN, "закрывающую скобку ')' после списка ресурсов");
        BlockStmt body = requiredBlock("тело 'use'");
        if (body == null) {
            cursor.synchronize();
            return new ErrorStmt(keyword.span());
        }
        return new UseStmt(resources, body, keyword.span().to(body.span()));
    }

    /** {@code catch} или {@code finally} без своего {@code try}. */
    private Stmt orphanHandler() {
        Token keyword = cursor.advance();
        diagnostics.error(keyword.span(), "'" + keyword.text() + "' без 'try': "
                + "он пишется сразу после блока 'try'");
        cursor.synchronize();
        return new ErrorStmt(keyword.span());
    }

    /**
     * Отложенное действие: {@code defer file.close()}.
     * <p>
     * Тело — одна инструкция или блок, как у {@code if}: {@code defer} обычно
     * и есть один вызов, и требовать вокруг него скобки значило бы делать частый
     * случай многословным. Границу тут угадывать не приходится — за {@code defer}
     * не следует ничего, что могло бы к нему прилипнуть.
     */
    private Stmt deferStatement() {
        Token keyword = cursor.advance(); // defer
        Stmt body = state.inDeferBody(() -> body("'defer'"));
        return new DeferStmt(body, keyword.span().to(body.span()));
    }

    // --- ветвления и циклы ---------------------------------------------------

    /**
     * Блок: инструкции в фигурных скобках. Внутри — тот же цикл, что и в
     * {@link #program()}, только границей служит {@code &#125;}, а не конец файла.
     */
    BlockStmt block() {
        Token open = cursor.advance(); // {
        List<Stmt> statements = new ArrayList<>();
        boolean outerDefer = state.enterBlock();
        cursor.skipSeparators();
        while (!cursor.check(TokenType.RBRACE) && !cursor.check(TokenType.EOF)) {
            int before = cursor.position();
            statements.add(statement());
            cursor.ensureProgress(before);
            cursor.skipSeparators();
        }
        Token close = cursor.expect(TokenType.RBRACE, "закрывающую скобку '}'");
        boolean deferred = state.leaveBlock(outerDefer);
        return new BlockStmt(statements, deferred, open.span().to(close.span()));
    }

    /**
     * Ветвление, вместе со всей цепочкой {@code else if}.
     * <p>
     * Цепочка не требует ни особого узла, ни цикла: {@code else if} — это рекурсивный
     * вызов, кладущий следующий {@link IfStmt} в иначе-ветвь текущего. Здесь же сам собой
     * решается висячий {@code else}: он достаётся тому {@code if}, который разбирается
     * прямо сейчас, то есть ближайшему.
     */
    private Stmt ifStatement() {
        Token keyword = cursor.advance(); // if
        Expr condition = condition("'if'");
        Stmt thenBranch = body("'if'");

        Stmt elseBranch = null;
        if (cursor.check(TokenType.ELSE)) {
            cursor.advance();
            elseBranch = cursor.check(TokenType.IF) ? ifStatement() : body("'else'");
        }
        Stmt last = elseBranch != null ? elseBranch : thenBranch;
        return new IfStmt(condition, thenBranch, elseBranch, keyword.span().to(last.span()));
    }

    private Stmt whileStatement() {
        Token keyword = cursor.advance(); // while
        Expr condition = condition("'while'");
        Stmt body = loopBody("цикла 'while'");
        return new WhileStmt(condition, body, keyword.span().to(body.span()));
    }

    /**
     * Цикл {@code for} в обеих формах.
     * <p>
     * Что это за форма, видно по двум токенам после открывающей скобки: имя и
     * {@code in} — перебор, что угодно другое — цикл со счётчиком. Заглядывание
     * ровно на два токена и без отката: {@link TokenCursor#peek(int)} для этого и есть.
     */
    private Stmt forStatement() {
        Token keyword = cursor.advance(); // for
        cursor.expect(TokenType.LPAREN, "открывающую скобку '(' после 'for'");
        if (cursor.check(TokenType.WORD) && cursor.peek(1).type() == TokenType.IN) {
            return forEachStatement(keyword);
        }

        // Пропущенная часть остаётся null: чего нет в тексте, того нет и в дереве,
        // а «нет условия» интерпретатор читает как «повторять всегда».
        Stmt init = cursor.check(TokenType.SEMICOLON) ? null : simpleStatement();
        cursor.expect(TokenType.SEMICOLON, "точку с запятой ';' после инициализатора цикла");
        Expr condition = cursor.check(TokenType.SEMICOLON) ? null : expression(0);
        cursor.expect(TokenType.SEMICOLON, "точку с запятой ';' после условия цикла");
        Stmt step = cursor.check(TokenType.RPAREN) ? null : simpleStatement();
        cursor.expect(TokenType.RPAREN, "закрывающую скобку ')' после шага цикла");

        Stmt body = loopBody("цикла 'for'");
        return new ForStmt(init, condition, step, body, keyword.span().to(body.span()));
    }

    /** Перебор: {@code for (item in cart) ...}. Открывающая скобка уже съедена. */
    private Stmt forEachStatement(Token keyword) {
        Token name = cursor.advance(); // имя переменной цикла
        cursor.advance();              // in
        Expr iterable = expression(0);
        cursor.expect(TokenType.RPAREN, "закрывающую скобку ')' после перебираемого значения");
        Stmt body = loopBody("цикла 'for'");
        return new ForEachStmt(name.text(), name.span(), iterable, body, keyword.span().to(body.span()));
    }

    private Stmt breakStatement() {
        Token keyword = cursor.advance();
        state.requireLoop(keyword);
        state.forbidInFinally(keyword);
        return new BreakStmt(keyword.span());
    }

    private Stmt continueStatement() {
        Token keyword = cursor.advance();
        state.requireLoop(keyword);
        state.forbidInFinally(keyword);
        return new ContinueStmt(keyword.span());
    }

    /**
     * Условие в скобках. Скобки обязательны, и это не дань привычке: перевод строки
     * в языке токена не даёт, поэтому без скобок {@code if x { ... }} неотличимо
     * от {@code if} с литералом объекта в условии.
     */
    private Expr condition(String owner) {
        cursor.expect(TokenType.LPAREN, "открывающую скобку '(' после " + owner);
        Expr condition = expression(0);
        cursor.expect(TokenType.RPAREN, "закрывающую скобку ')' после условия " + owner);
        return condition;
    }

    /**
     * Тело управляющей конструкции: блок или одна инструкция.
     * <p>
     * Одна инструкция разрешена намеренно — {@code if (x) println("да")} читается лучше
     * четырёх строк, — но стоит помнить, что область видимости создаёт именно блок.
     */
    private Stmt body(String owner) {
        if (cursor.check(TokenType.SEMICOLON) || cursor.check(TokenType.RBRACE)
                || cursor.check(TokenType.EOF)) {
            diagnostics.error(cursor.peek().span(),
                    "ожидалось тело " + owner + ", найдено " + describe(cursor.peek()));
            return new ErrorStmt(cursor.peek().span());
        }
        return statement();
    }

    /** Тело цикла: то же, что и любое тело, но внутри него разрешены break и continue. */
    private Stmt loopBody(String owner) {
        return state.inLoopBody(() -> body(owner));
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
    Expr expression(int minPower) {
        Expr left = prefix();
        while (true) {
            TokenType type = cursor.peek().type();

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
            cursor.advance();
            Expr right = expression(infix.rightPower());
            left = new BinaryExpr(infix.op(), left, right, left.span().to(right.span()));
        }
    }

    /**
     * Начало выражения: литерал, имя, скобка или префиксный оператор.
     * <p>
     * Операнд унарной операции разбирается с силой {@link Operators#UNARY}, а не
     * «одним примитивом»: поэтому {@code -a.x} — это {@code -(a.x)} (обращение крепче),
     * {@code -a * b} — это {@code (-a) * b} (умножение слабее), а {@code !!flag}
     * и {@code - -x} вообще разбираются. В прошлой реализации унарный оператор
     * применялся к строго одному примитиву, и двойное отрицание просто не работало.
     */
    private Expr prefix() {
        Token token = cursor.peek();

        UnaryOp unary = Operators.prefix(token.type());
        if (unary != null) {
            cursor.advance();
            Expr operand = expression(Operators.UNARY);
            return new UnaryExpr(unary, operand, token.span().to(operand.span()));
        }

        switch (token.type()) {
            case INT, FLOAT, HEX, BIN -> {
                cursor.advance();
                return new LiteralExpr(NumberLiterals.of(token, diagnostics), token.span());
            }
            case STRING -> {
                cursor.advance();
                return new LiteralExpr(StringValue.of(token.text()), token.span());
            }
            case TRUE -> {
                cursor.advance();
                return new LiteralExpr(BoolValue.TRUE, token.span());
            }
            case FALSE -> {
                cursor.advance();
                return new LiteralExpr(BoolValue.FALSE, token.span());
            }
            case NULL -> {
                cursor.advance();
                return new LiteralExpr(NullValue.NULL, token.span());
            }
            case WORD -> {
                cursor.advance();
                return new VariableExpr(token.text(), token.span());
            }
            // this и super — обычные имена, а не спецформы: их можно положить
            // в переменную и передать. Особенное в них только одно — где они допустимы,
            // и это парсер знает, поэтому ошибка здесь разбора, а не выполнения.
            case THIS -> {
                cursor.advance();
                state.checkThis(token);
                return new VariableExpr(token.text(), token.span());
            }
            case SUPER -> {
                cursor.advance();
                state.checkSuper(token);
                return new VariableExpr(token.text(), token.span());
            }
            case NEW -> {
                return newExpr();
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
            case FUN -> {
                return functionExpr();
            }
            case TRY -> {
                if (shortTryAhead()) {
                    return shortTry();
                }
                diagnostics.error(token.span(), "'try' в позиции выражения пишется коротко: "
                        + "'try? выражение' даёт null при ошибке, 'try! выражение' объявляет"
                        + " ошибку невозможной. Конструкция с блоком — это инструкция");
                cursor.advance();
                return new ErrorExpr(token.span());
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
        cursor.advance(); // (
        Expr inner = expression(0);
        cursor.expect(TokenType.RPAREN, "закрывающую скобку ')'");
        return inner;
    }

    /**
     * Условное выражение. Правая часть разбирается с порогом самого тернарника,
     * поэтому вложенность идёт вправо: {@code a ? b : c ? d : e} — это
     * {@code a ? b : (c ? d : e)}, как и ожидает любой, кто писал такие цепочки.
     */
    private Expr ternary(Expr condition) {
        cursor.advance(); // ?
        Expr ifTrue = expression(0);
        cursor.expect(TokenType.COLON, "двоеточие ':' в условном выражении");
        Expr ifFalse = expression(Operators.TERNARY);
        return new TernaryExpr(condition, ifTrue, ifFalse, condition.span().to(ifFalse.span()));
    }

    /**
     * Обращение к содержимому: {@code .имя} или {@code [выражение]}.
     * <p>
     * Обе записи дают один и тот же узел {@link AccessExpr} — точка просто избавляет
     * от кавычек: {@code point.x} превращается в ключ-строку {@code "x"} прямо здесь,
     * и дальше по конвейеру идёт неотличимо от {@code point["x"]}.
     * <p>
     * После точки принимается и ключевое слово: {@code answer.match} — обращение
     * к полю с именем {@code match}, а не синтаксическая ошибка. Ключевые слова
     * особенные только там, где с них начинается конструкция; в позиции имени поля
     * двусмысленности нет, а запрещать половину словаря языка ради ничего — плохая сделка.
     */
    private Expr access(Expr target) {
        Token operator = cursor.advance(); // . или [
        if (operator.type() == TokenType.DOT) {
            Token name = cursor.peek();
            if (name.type() != TokenType.WORD && !name.type().isKeyword()) {
                diagnostics.error(name.span(),
                        "после точки ожидалось имя поля, найдено " + describe(name));
                return new AccessExpr(target, new ErrorExpr(name.span()), AccessStyle.DOT,
                        target.span().to(operator.span()));
            }
            cursor.advance();
            Expr key = new LiteralExpr(StringValue.of(name.text()), name.span());
            return new AccessExpr(target, key, AccessStyle.DOT, target.span().to(name.span()));
        }

        Expr key = expression(0);
        Token close = cursor.expect(TokenType.RBRACKET, "закрывающую скобку ']'");
        return new AccessExpr(target, key, AccessStyle.BRACKET, target.span().to(close.span()));
    }

    /**
     * Вызов: список аргументов в скобках.
     * <p>
     * Вызывается уже разобранное выражение, каким бы оно ни было, — поэтому
     * {@code f()()}, {@code point.asText()} и {@code handlers[0](x)} разбираются
     * тем же кодом, без единого особого случая. Отдельного «вызова метода» в языке нет.
     */
    private Expr call(Expr callee) {
        List<Expr> arguments = new ArrayList<>();
        Token close = argumentList(arguments);
        return new CallExpr(callee, arguments, callee.span().to(close.span()));
    }

    /**
     * Список аргументов в скобках — общий для вызова, создания и заголовка родителя.
     *
     * @return закрывающая скобка, чтобы вызывающий знал, где кончился список
     */
    Token argumentList(List<Expr> arguments) {
        cursor.advance(); // (
        while (!cursor.check(TokenType.RPAREN) && !cursor.check(TokenType.EOF)) {
            int before = cursor.position();
            arguments.add(expression(0));
            if (cursor.match(TokenType.COMMA) || cursor.check(TokenType.RPAREN)) {
                continue;
            }
            diagnostics.error(cursor.peek().span(),
                    "ожидалась ',' или ')' в списке аргументов, найдено " + describe(cursor.peek()));
            cursor.ensureProgress(before);
        }
        return cursor.expect(TokenType.RPAREN, "закрывающую скобку ')'");
    }

    /**
     * Создание экземпляра: {@code new Point(20, 30)}.
     * <p>
     * {@code new} забирает себе <b>одно</b> обращение и <b>один</b> список аргументов,
     * а всё, что идёт дальше, достаётся уже экземпляру — поэтому
     * {@code new Circle(5).area()} читается однозначно и не требует скобок, а
     * {@code new kinds[0](1, 2)} создаёт экземпляр класса из массива.
     * <p>
     * Скобки обязательны, даже пустые: {@code new} без списка аргументов — это
     * не «создать по умолчанию», а недописанная строка.
     */
    private Expr newExpr() {
        Token keyword = cursor.advance(); // new
        Expr target = newTarget();
        while (cursor.check(TokenType.DOT) || cursor.check(TokenType.LBRACKET)) {
            target = access(target);
        }
        if (!cursor.check(TokenType.LPAREN)) {
            diagnostics.error(cursor.peek().span(),
                    "после 'new' ожидался список аргументов в скобках, найдено "
                            + describe(cursor.peek()) + ". Скобки обязательны, даже пустые");
            return new ErrorExpr(keyword.span().to(target.span()));
        }
        List<Expr> arguments = new ArrayList<>();
        Token close = argumentList(arguments);
        return new NewExpr(target, arguments, keyword.span().to(close.span()));
    }

    /** То, что стоит между {@code new} и списком аргументов: имя или скобочная группа. */
    private Expr newTarget() {
        Token token = cursor.peek();
        if (token.type() == TokenType.WORD) {
            cursor.advance();
            return new VariableExpr(token.text(), token.span());
        }
        if (token.type() == TokenType.LPAREN) {
            return group();
        }
        diagnostics.error(token.span(), "после 'new' ожидалось имя класса, найдено " + describe(token));
        return new ErrorExpr(token.span());
    }

    // --- литералы коллекций --------------------------------------------------

    /** Массив: {@code [1, 2, 3]}. Запятая после последнего элемента разрешена. */
    private Expr arrayLiteral() {
        Token open = cursor.advance(); // [
        List<Expr> elements = new ArrayList<>();
        while (!cursor.check(TokenType.RBRACKET) && !cursor.check(TokenType.EOF)) {
            int before = cursor.position();
            elements.add(expression(0));
            if (cursor.match(TokenType.COMMA) || cursor.check(TokenType.RBRACKET)) {
                continue;
            }
            diagnostics.error(cursor.peek().span(),
                    "ожидалась ',' или ']' в массиве, найдено " + describe(cursor.peek()));
            cursor.ensureProgress(before);
        }
        Token close = cursor.expect(TokenType.RBRACKET, "закрывающую скобку ']'");
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
        Token open = cursor.advance(); // {
        List<ObjectExpr.Entry> entries = new ArrayList<>();
        while (!cursor.check(TokenType.RBRACE) && !cursor.check(TokenType.EOF)) {
            int before = cursor.position();
            Expr key = objectKey();
            cursor.expect(TokenType.COLON, "двоеточие ':' после ключа объекта");
            Expr value = expression(0);
            entries.add(new ObjectExpr.Entry(key, value));
            if (cursor.match(TokenType.COMMA) || cursor.check(TokenType.RBRACE)) {
                continue;
            }
            diagnostics.error(cursor.peek().span(),
                    "ожидалась ',' или '}' в объекте, найдено " + describe(cursor.peek()));
            cursor.ensureProgress(before);
        }
        Token close = cursor.expect(TokenType.RBRACE, "закрывающую скобку '}'");
        return new ObjectExpr(entries, open.span().to(close.span()));
    }

    /** Ключ пары объекта: имя без кавычек, ключевое слово или любое выражение. */
    private Expr objectKey() {
        Token token = cursor.peek();
        boolean bareName = (token.type() == TokenType.WORD || token.type().isKeyword())
                && cursor.peek(1).type() == TokenType.COLON;
        if (bareName) {
            cursor.advance();
            return new LiteralExpr(StringValue.of(token.text()), token.span());
        }
        return expression(0);
    }
}
