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
    /**
     * Сколько циклов вокруг разбираемой сейчас инструкции. Нужен ровно для одного:
     * поймать {@code break} и {@code continue} вне цикла при разборе, а не при выполнении
     * той единственной ветки, куда до релиза никто не заглянул. Тело функции начинается
     * с нуля — из цикла нельзя выйти через границу функции.
     */
    private int loopDepth;
    /** Сколько функций вокруг: то же самое для {@code return} вне функции. */
    private int functionDepth;
    /**
     * Сколько блоков {@code finally} вокруг разбираемой сейчас инструкции.
     * <p>
     * Нужен ровно для одного: {@code return}, {@code break} и {@code continue} внутри
     * {@code finally} запрещены — они молча погасили бы ошибку, которая летит наружу.
     * Тело функции, объявленной внутри {@code finally}, начинается с нуля: её
     * {@code return} возвращает из неё самой и ничего не гасит.
     */
    private int finallyDepth;
    /**
     * Сколько блоков {@code defer} вокруг: тот же запрет на выход наружу и по той же
     * причине — отложенное действие выполняется на пути наружу, и уйти из него
     * значило бы погасить то, ради чего мы идём.
     */
    private int deferDepth;
    /**
     * Есть ли отложенное действие в блоке, который разбирается прямо сейчас.
     * <p>
     * Флаг ставится при разборе {@code defer} и снимается блоком: так {@link BlockStmt}
     * узнаёт о своих отложенных действиях даром, а выполнению не приходится заводить
     * список на каждый блок — их в скрипте тысячи, а блоков с {@code defer} единицы.
     */
    private boolean blockHasDefer;
    /**
     * Имя класса, тело которого разбирается сейчас, или {@code null}.
     * <p>
     * Нужно, чтобы отличить конструктор {@code fun Point()} от метода и проверить,
     * что фабрика {@code fun User.of(...)} объявлена на своём классе.
     */
    private String className;
    /**
     * Допустимо ли здесь {@code this}. Внутри метода и конструктора — да, включая
     * вложенные анонимные функции: {@code this} — обычное имя в области, а области
     * замыкаются по общему правилу. Внутри фабрики — нет: экземпляра ещё не
     * существует, она его и создаёт.
     */
    private boolean thisAllowed;
    /** Допустимо ли здесь {@code super}: только в методе класса, у которого есть родитель. */
    private boolean superAllowed;
    /**
     * Разбирается тело трейта, а не класса.
     * <p>
     * Нужно ровно для {@code super}: у трейта родителя нет и быть не может, и отвечать
     * на вопрос «что такое {@code super} в методе трейта, подмешанного в класс
     * с предком» язык не берётся.
     */
    private boolean inTrait;

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
        return switch (peek().type()) {
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
            case FUN -> peek(1).type() == TokenType.WORD ? funDeclaration() : simpleStatement();
            case CLASS -> classDeclaration();
            case TRAIT -> traitDeclaration();
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

        AssignOp assign = Operators.assign(peek().type());
        if (assign != null) {
            advance();
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
        synchronize();
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
            synchronize();
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
        Token keyword = advance(); // const
        if (!check(TokenType.WORD)) {
            diagnostics.error(peek().span(),
                    "после 'const' ожидалось имя, найдено " + describe(peek()));
            synchronize();
            return new ErrorStmt(keyword.span());
        }
        Token name = advance();
        if (check(TokenType.DOT) || check(TokenType.LBRACKET)) {
            // Отдельное сообщение: советовать здесь 'const имя = выражение' значило бы
            // предлагать не то — автор промахнулся не значением, а левой частью.
            diagnostics.error(peek().span(), "слева от '=' в объявлении константы стоит имя: "
                    + "заморозить можно имя, а не ячейку внутри чужого значения");
            synchronize();
            return new ErrorStmt(keyword.span().to(name.span()));
        }
        if (!check(TokenType.ASSIGN)) {
            diagnostics.error(peek().span(), "константе нужно начальное значение: "
                    + "const " + name.text() + " = выражение");
            synchronize();
            return new ErrorStmt(keyword.span().to(name.span()));
        }
        advance(); // =
        Expr value = expression(0);
        if (value instanceof ErrorExpr) {
            // О невозможном выражении уже сказано; дальше по строке разбирать нечего.
            synchronize();
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
        Token keyword = advance(); // import
        Token first = peek();
        String path;
        Span pathSpan;
        if (first.type() == TokenType.STRING) {
            advance();
            path = first.text();
            pathSpan = first.span();
        } else if (first.type() == TokenType.WORD) {
            advance();
            StringBuilder segments = new StringBuilder(first.text());
            Span last = first.span();
            while (check(TokenType.DOT)) {
                advance();
                if (!check(TokenType.WORD)) {
                    diagnostics.error(peek().span(), "после точки в пути модуля ожидалось имя,"
                            + " найдено " + describe(peek()) + ". Путь с такими символами"
                            + " записывается строкой: import \"lib/имя-модуля\"");
                    synchronize();
                    return new ErrorStmt(keyword.span().to(last));
                }
                Token segment = advance();
                last = segment.span();
                segments.append('/').append(segment.text());
            }
            path = segments.toString();
            pathSpan = first.span().to(last);
        } else {
            diagnostics.error(first.span(), "после 'import' ожидался путь модуля, найдено "
                    + describe(first) + ". Путь пишется именами через точку (import lib.math)"
                    + " или строкой (import \"lib/math\")");
            synchronize();
            return new ErrorStmt(keyword.span());
        }

        if (!match(TokenType.AS)) {
            return new ImportStmt(path, pathSpan, null, Span.NONE, keyword.span().to(pathSpan));
        }
        if (!check(TokenType.WORD)) {
            diagnostics.error(peek().span(), "после 'as' ожидалось имя, под которым модуль"
                    + " ляжет в переменную, найдено " + describe(peek()));
            synchronize();
            return new ErrorStmt(keyword.span().to(pathSpan));
        }
        Token alias = advance();
        return new ImportStmt(path, pathSpan, alias.text(), alias.span(),
                keyword.span().to(alias.span()));
    }

    // --- функции -------------------------------------------------------------

    /** Объявление: {@code fun имя(a, b) тело}. Имя проверено в {@link #statement()}. */
    private Stmt funDeclaration() {
        Token keyword = advance(); // fun
        Token name = advance();    // имя
        FunctionExpr function = functionRest(keyword, name.text());
        if (function.body() instanceof ReturnStmt returned && returned.value() instanceof ErrorExpr) {
            // Тело после '=>' не разобралось, и об этом уже сказано. Дальше по строке
            // разбирать нечего: пропускаем её целиком, иначе тот же токен вызовет ту же
            // ошибку второй раз — уже от следующей инструкции.
            synchronize();
            return new ErrorStmt(function.span());
        }
        return new FunDeclStmt(function, function.span());
    }

    /** Анонимная функция в позиции выражения: {@code fun(a, b) => a + b}. */
    private Expr functionExpr() {
        Token keyword = advance(); // fun
        return functionRest(keyword, null);
    }

    /**
     * Параметры и тело — всё, что у объявления и анонимной функции общее, то есть всё,
     * кроме имени.
     * <p>
     * Тело — отдельная территория для управляющих конструкций: {@code return} внутри
     * разрешён, а {@code break} из цикла, объемлющего объявление, — нет. Поэтому
     * {@link #loopDepth} на время разбора тела обнуляется, а не просто не растёт.
     */
    private FunctionExpr functionRest(Token keyword, String name) {
        List<FunctionExpr.Param> params = parameters("'fun'", true);

        int outerLoops = loopDepth;
        int outerFinally = finallyDepth;
        int outerDefer = deferDepth;
        loopDepth = 0;
        finallyDepth = 0;
        deferDepth = 0;
        functionDepth++;
        try {
            if (match(TokenType.FATARROW)) {
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
        } finally {
            functionDepth--;
            loopDepth = outerLoops;
            finallyDepth = outerFinally;
            deferDepth = outerDefer;
        }
    }

    // --- классы и трейты -----------------------------------------------------

    /**
     * Объявление класса: {@code class Point(x = 0, y = 0) : Shape("точка") with Printable { ... }}.
     * <p>
     * Порядок частей фиксирован — сначала родитель, потом трейты, — и он же порядок
     * сборки плоских таблиц: «сначала родитель, потом примеси, потом я сам».
     * Читать такое объявление можно слева направо, не зная никаких правил линеаризации.
     */
    private Stmt classDeclaration() {
        Token keyword = advance(); // class
        Token name = expectTypeName("класса");
        if (name == null) {
            synchronize();
            return new ErrorStmt(keyword.span());
        }

        List<FunctionExpr.Param> params = check(TokenType.LPAREN)
                ? parameters("имени класса '" + name.text() + "'", true)
                : List.of();
        ClassDeclStmt.Superclass parent = check(TokenType.COLON) ? superclass() : null;
        List<ClassDeclStmt.TraitRef> traits = traitList();
        if (check(TokenType.COLON)) {
            diagnostics.error(peek().span(), "родитель указывается перед трейтами: "
                    + "class " + name.text() + "(...) : Родитель with Трейт");
        }

        Members members = classBody(name.text(), parent != null);
        Span span = keyword.span().to(lastSpan());
        return new ClassDeclStmt(name.text(), name.span(), params, parent, traits,
                members.constructor, members.methods, members.factories, span);
    }

    /**
     * Объявление трейта: {@code trait Counted(count = 0, limit) { ... }}.
     * <p>
     * Ни родителя, ни подмешанных трейтов у трейта нет — он и есть то, что подмешивают.
     * Отсюда и вся простота сборки: трейт ни от чего не зависит, поэтому порядок
     * объявлений для него не значит ничего.
     */
    private Stmt traitDeclaration() {
        Token keyword = advance(); // trait
        Token name = expectTypeName("трейта");
        if (name == null) {
            synchronize();
            return new ErrorStmt(keyword.span());
        }

        List<FunctionExpr.Param> params = check(TokenType.LPAREN)
                ? parameters("имени трейта '" + name.text() + "'", false)
                : List.of();
        checkTraitDefaults(params);
        if (check(TokenType.COLON) || check(TokenType.WITH)) {
            diagnostics.error(peek().span(), "у трейта не бывает ни родителя, ни подмешанных трейтов: "
                    + "трейт — это то, что подмешивают");
            synchronize();
        }

        Members members = traitBody(name.text());
        Span span = keyword.span().to(lastSpan());
        return new TraitDeclStmt(name.text(), name.span(), params,
                members.methods, members.requirements, span);
    }

    private Token expectTypeName(String what) {
        if (check(TokenType.WORD)) {
            return advance();
        }
        diagnostics.error(peek().span(), "ожидалось имя " + what + ", найдено " + describe(peek()));
        return null;
    }

    /**
     * Имя типа в заголовке класса: {@code Shape} или {@code m.Shape}, где {@code m} —
     * имя именованного импорта.
     * <p>
     * Больше одной точки не принимается, и это не экономия: слева от точки здесь
     * стоит имя импорта, известное до выполнения, а не значение, из которого можно
     * доставать содержимое дальше. Модуль модуля не бывает — импорт разворачивает
     * файл целиком.
     *
     * @return {@code null}, если имени нет; об ошибке уже сказано
     */
    private TypeName typeName(String what) {
        if (!check(TokenType.WORD)) {
            diagnostics.error(peek().span(), "ожидалось имя " + what + ", найдено " + describe(peek()));
            return null;
        }
        Token first = advance();
        if (!check(TokenType.DOT)) {
            return new TypeName(null, first.text(), first.span());
        }
        advance(); // .
        if (!check(TokenType.WORD)) {
            diagnostics.error(peek().span(), "после '" + first.text() + ".' ожидалось имя "
                    + what + ", найдено " + describe(peek()));
            return null;
        }
        Token name = advance();
        if (check(TokenType.DOT)) {
            diagnostics.error(peek().span(), "слева от точки здесь стоит имя импорта, "
                    + "а не значение: '" + first.text() + "." + name.text() + "' — уже полное имя");
            return null;
        }
        return new TypeName(first.text(), name.text(), first.span().to(name.span()));
    }

    /** Имя типа так, как оно написано в заголовке. */
    private record TypeName(String alias, String name, Span span) {

        String title() {
            return alias == null ? name : alias + "." + name;
        }
    }

    /** Родитель и аргументы его заголовка: {@code : Shape("круг")}. Скобки необязательны. */
    private ClassDeclStmt.Superclass superclass() {
        Token colon = advance(); // :
        TypeName parent = typeName("класса-родителя");
        if (parent == null) {
            return null;
        }
        List<Expr> arguments = new ArrayList<>();
        Span end = parent.span();
        if (check(TokenType.LPAREN)) {
            end = argumentList(arguments).span();
        }
        return new ClassDeclStmt.Superclass(parent.alias(), parent.name(), arguments,
                colon.span().to(end));
    }

    /** Подмешанные трейты: {@code with Printable, m.Counted}. Их может быть сколько угодно. */
    private List<ClassDeclStmt.TraitRef> traitList() {
        if (!check(TokenType.WITH)) {
            return List.of();
        }
        advance(); // with
        List<ClassDeclStmt.TraitRef> traits = new ArrayList<>();
        do {
            TypeName trait = typeName("трейта");
            if (trait == null) {
                break;
            }
            for (ClassDeclStmt.TraitRef existing : traits) {
                if (existing.title().equals(trait.title())) {
                    diagnostics.error(trait.span(), "трейт '" + trait.title() + "' подмешан дважды");
                }
            }
            traits.add(new ClassDeclStmt.TraitRef(trait.alias(), trait.name(), trait.span()));
        } while (match(TokenType.COMMA));
        return traits;
    }

    /**
     * Собранное тело класса или трейта.
     * <p>
     * Разложено по видам сразу при разборе: дальше по конвейеру конструктор,
     * методы, фабрики и требования ведут себя по-разному, и раскладывать их
     * второй раз, уже по дереву, было бы работой на пустом месте.
     */
    private static final class Members {
        private FunctionExpr constructor;
        private final List<FunctionExpr> methods = new ArrayList<>();
        private final List<ClassDeclStmt.Factory> factories = new ArrayList<>();
        private final List<TraitDeclStmt.Requirement> requirements = new ArrayList<>();

        private boolean taken(String name) {
            if (constructor != null && constructor.name().equals(name)) {
                return true;
            }
            for (FunctionExpr method : methods) {
                if (method.name().equals(name)) {
                    return true;
                }
            }
            for (TraitDeclStmt.Requirement requirement : requirements) {
                if (requirement.name().equals(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Members classBody(String name, boolean hasParent) {
        return typeBody(name, hasParent, true);
    }

    private Members traitBody(String name) {
        return typeBody(name, false, false);
    }

    /**
     * Тело класса или трейта: только объявления функций.
     * <p>
     * Класс — это описание, а не код, который что-то делает в момент объявления,
     * поэтому любая другая инструкция здесь ошибка. Тела вовсе может не быть:
     * класс без методов законен и полезен — это структура данных с именем.
     */
    private Members typeBody(String name, boolean hasParent, boolean isClass) {
        Members members = new Members();
        if (!check(TokenType.LBRACE)) {
            return members;
        }
        advance(); // {

        String outerClass = className;
        boolean outerThis = thisAllowed;
        boolean outerSuper = superAllowed;
        boolean outerTrait = inTrait;
        className = name;
        inTrait = !isClass;
        try {
            skipSeparators();
            while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                int before = index;
                member(members, hasParent, isClass);
                ensureProgress(before);
                skipSeparators();
            }
        } finally {
            className = outerClass;
            thisAllowed = outerThis;
            superAllowed = outerSuper;
            inTrait = outerTrait;
        }

        expect(TokenType.RBRACE, "закрывающую скобку '}'");
        return members;
    }

    /** Один член: метод, конструктор, фабрика или — только в трейте — требование. */
    private void member(Members members, boolean hasParent, boolean isClass) {
        if (!check(TokenType.FUN)) {
            diagnostics.error(peek().span(), "в теле " + (isClass ? "класса" : "трейта")
                    + " допустимы только объявления функций, а здесь " + describe(peek())
                    + ". Начальные значения полей задаются в заголовке");
            synchronize();
            return;
        }
        Token keyword = advance(); // fun
        if (!check(TokenType.WORD)) {
            diagnostics.error(peek().span(),
                    "ожидалось имя метода, найдено " + describe(peek()));
            synchronize();
            return;
        }
        Token name = advance();

        if (check(TokenType.DOT)) {
            factory(members, keyword, name, isClass);
            return;
        }

        boolean isConstructor = isClass && name.text().equals(className);
        // Внутри метода и конструктора 'this' есть, 'super' — если есть родитель.
        thisAllowed = true;
        superAllowed = hasParent;

        List<FunctionExpr.Param> params = parameters("имени " + (isConstructor ? "конструктора" : "метода")
                + " '" + name.text() + "'", true);
        if (isConstructor && !params.isEmpty()) {
            diagnostics.error(name.span(), "конструктор не принимает параметров: "
                    + "список создания задаёт заголовок класса '" + className + "'");
        }

        Stmt body = memberBody();
        if (body == null) {
            if (isClass) {
                diagnostics.error(name.span(), "у метода '" + name.text() + "' нет тела; "
                        + "требование без тела бывает только в трейте");
                return;
            }
            if (members.taken(name.text())) {
                diagnostics.error(name.span(), duplicate(name.text()));
                return;
            }
            members.requirements.add(new TraitDeclStmt.Requirement(name.text(), params,
                    keyword.span().to(name.span())));
            return;
        }

        FunctionExpr function = new FunctionExpr(name.text(), params, body,
                bodyStyle(body), keyword.span().to(body.span()));
        if (isConstructor) {
            if (members.constructor != null) {
                diagnostics.error(name.span(), "конструктор класса '" + className + "' уже объявлен");
                return;
            }
            members.constructor = function;
            return;
        }
        if (members.taken(name.text())) {
            diagnostics.error(name.span(), duplicate(name.text()));
            return;
        }
        members.methods.add(function);
    }

    /**
     * Фабрика: {@code fun User.of(name, password)}.
     * <p>
     * Имя слева обязано совпасть с самим классом — иначе это не «способ создания,
     * записанный рядом с классом», а запись в чужое значение, для которой есть
     * обычное присваивание. {@code this} внутри нет: экземпляра ещё не существует.
     */
    private void factory(Members members, Token keyword, Token owner, boolean isClass) {
        advance(); // .
        if (!isClass) {
            diagnostics.error(owner.span(), "у трейта не бывает фабрик: "
                    + "экземпляр создаёт класс, ему они и принадлежат");
        } else if (!owner.text().equals(className)) {
            diagnostics.error(owner.span(), "фабрика объявляется на своём классе: "
                    + "здесь '" + owner.text() + "', а объявляется класс '" + className + "'");
        }
        if (!check(TokenType.WORD)) {
            diagnostics.error(peek().span(),
                    "после точки ожидалось имя фабрики, найдено " + describe(peek()));
            synchronize();
            return;
        }
        Token name = advance();
        String full = owner.text() + "." + name.text();

        thisAllowed = false;
        superAllowed = false;
        List<FunctionExpr.Param> params = parameters("имени фабрики '" + full + "'", true);
        Stmt body = memberBody();
        if (body == null) {
            diagnostics.error(name.span(), "у фабрики '" + full + "' нет тела");
            return;
        }
        for (ClassDeclStmt.Factory existing : members.factories) {
            if (existing.name().equals(name.text())) {
                diagnostics.error(name.span(), duplicate(full));
                return;
            }
        }
        members.factories.add(new ClassDeclStmt.Factory(name.text(),
                new FunctionExpr(full, params, body, bodyStyle(body), keyword.span().to(body.span())),
                keyword.span().to(body.span())));
    }

    /**
     * Тело члена: блок или {@code => выражение}, и ничего третьего.
     * <p>
     * Тело одной инструкцией без скобок здесь запрещено, в отличие от {@code if}
     * и циклов, и причина в требованиях трейта: у {@code fun report()} тела нет,
     * а следующей строкой идёт {@code fun full() => ...}. Разреши мы инструкцию
     * без скобок — второе объявление молча стало бы телом первого.
     *
     * @return {@code null}, если тела нет; для трейта это требование, для класса ошибка
     */
    private Stmt memberBody() {
        int outerLoops = loopDepth;
        int outerFinally = finallyDepth;
        int outerDefer = deferDepth;
        loopDepth = 0;
        finallyDepth = 0;
        deferDepth = 0;
        functionDepth++;
        try {
            if (match(TokenType.FATARROW)) {
                Expr value = expression(0);
                return new ReturnStmt(value, value.span());
            }
            return check(TokenType.LBRACE) ? block() : null;
        } finally {
            functionDepth--;
            loopDepth = outerLoops;
            finallyDepth = outerFinally;
            deferDepth = outerDefer;
        }
    }

    private static BodyStyle bodyStyle(Stmt body) {
        return body instanceof BlockStmt ? BodyStyle.STATEMENT : BodyStyle.ARROW;
    }

    private static String duplicate(String name) {
        return "'" + name + "' в этом теле уже объявлен: "
                + "пространство имён одно, два значения по одному ключу не лежат";
    }

    /**
     * Значение по умолчанию поля трейта вычисляется в области, где объявлен <b>трейт</b>,
     * и на каждом создании заново. Других полей оно поэтому не видит — ни левее, ни
     * правее: у трейта нет создания, в котором они связывались бы по порядку.
     */
    private void checkTraitDefaults(List<FunctionExpr.Param> params) {
        List<String> all = new ArrayList<>(params.size());
        params.forEach(param -> all.add(param.name()));
        for (FunctionExpr.Param param : params) {
            if (!param.hasDefault()) {
                continue;
            }
            VariableExpr use = findUse(param.defaultValue(), all);
            if (use != null) {
                diagnostics.error(use.span(), "значение по умолчанию поля '" + param.name()
                        + "' ссылается на поле '" + use.name() + "': значения полей трейта"
                        + " вычисляются в области объявления трейта и друг друга не видят");
            }
        }
    }

    /**
     * Список параметров, возможно со значениями по умолчанию: {@code (a, b = 10)}.
     * Запятая после последнего разрешена — как в массивах и аргументах.
     *
     * @param callSignature задаёт ли список число аргументов вызова или создания.
     *                      У заголовка трейта — нет: там параметр без значения это
     *                      не «обязательный аргумент», а требование к классу,
     *                      и порядок для него не значит ничего
     */
    private List<FunctionExpr.Param> parameters(String owner, boolean callSignature) {
        expect(TokenType.LPAREN, "открывающую скобку '(' после " + owner);
        List<FunctionExpr.Param> params = new ArrayList<>();
        while (!check(TokenType.RPAREN) && !check(TokenType.EOF)) {
            int before = index;
            if (check(TokenType.WORD)) {
                Token name = advance();
                // Значение по умолчанию — обычное выражение, а не литерал: запятая
                // оператором не является, поэтому список на нём не рвётся.
                addParameter(params, name, match(TokenType.ASSIGN) ? expression(0) : null,
                        callSignature);
                if (match(TokenType.COMMA) || check(TokenType.RPAREN)) {
                    continue;
                }
                diagnostics.error(peek().span(),
                        "ожидалась ',' или ')' в списке параметров, найдено " + describe(peek()));
            } else {
                diagnostics.error(peek().span(),
                        "ожидалось имя параметра, найдено " + describe(peek()));
            }
            ensureProgress(before);
        }
        expect(TokenType.RPAREN, "закрывающую скобку ')' после списка параметров");
        if (callSignature) {
            checkDefaultsLookLeft(params);
        }
        return params;
    }

    /**
     * Добавляет параметр, поймав одноимённый и обязательный после необязательного.
     * <p>
     * Два параметра с одним именем — не спор о вкусе: второй молча перекрыл бы первый,
     * и один из аргументов стал бы недоступен.
     * <p>
     * <b>После параметра со значением по умолчанию обязательных быть не может.</b>
     * Причина не эстетическая: пропуск в середине нечем записать, пока в языке нет
     * именованных аргументов, а число аргументов проверяется одним отрезком
     * ({@link ru.wds.wdl.value.Arity}) до входа в функцию.
     */
    private void addParameter(List<FunctionExpr.Param> params, Token name, Expr defaultValue,
                              boolean callSignature) {
        for (FunctionExpr.Param existing : params) {
            if (existing.name().equals(name.text())) {
                diagnostics.error(name.span(), "параметр '" + name.text() + "' уже объявлен");
                return;
            }
        }
        // В заголовке трейта порядок не значит ничего: 'trait Counted(count = 0, limit)' —
        // это поле со значением и требование к классу, а не два аргумента создания.
        if (callSignature && defaultValue == null
                && !params.isEmpty() && params.get(params.size() - 1).hasDefault()) {
            diagnostics.error(name.span(), "параметр '" + name.text() + "' без значения по умолчанию"
                    + " не может идти после параметра со значением по умолчанию");
        }
        params.add(new FunctionExpr.Param(name.text(), defaultValue, name.span()));
    }

    /**
     * Значение по умолчанию видит параметры <b>слева</b> от себя и не видит остальных.
     * <p>
     * Считается оно при вызове, по порядку, поэтому {@code fun f(a, b = a * 2)} — законно
     * и полезно, а {@code fun f(a = b, b = 1)} к моменту вычисления {@code a} нашло бы
     * не параметр, а одноимённую переменную снаружи — и подставило бы её молча. Язык
     * такие подмены не допускает нигде, поэтому это ошибка разбора.
     * <p>
     * Резолвер имён для проверки не нужен: достаточно посмотреть, какие имена вообще
     * встречаются в выражении по умолчанию.
     */
    private void checkDefaultsLookLeft(List<FunctionExpr.Param> params) {
        for (int i = 0; i < params.size(); i++) {
            FunctionExpr.Param param = params.get(i);
            if (!param.hasDefault()) {
                continue;
            }
            List<String> unbound = new ArrayList<>();
            for (int j = i; j < params.size(); j++) {
                unbound.add(params.get(j).name());
            }
            VariableExpr use = findUse(param.defaultValue(), unbound);
            if (use == null) {
                continue;
            }
            diagnostics.error(use.span(), "значение по умолчанию параметра '" + param.name() + "' "
                    + (use.name().equals(param.name())
                            ? "ссылается на сам параметр"
                            : "ссылается на параметр '" + use.name() + "', который связывается позже"));
        }
    }

    /**
     * Первое обращение к одному из имён в выражении — или {@code null}, если их там нет.
     * <p>
     * В тело вложенной функции обход не заходит намеренно: {@code fun f(a = fun(b) => b, b = 1)}
     * — законно, {@code b} внутри лямбды своё собственное и к параметрам {@code f}
     * отношения не имеет.
     */
    private static VariableExpr findUse(Expr expr, List<String> names) {
        return switch (expr) {
            case VariableExpr variable -> names.contains(variable.name()) ? variable : null;
            case UnaryExpr unary -> findUse(unary.operand(), names);
            case BinaryExpr binary -> firstUse(names, binary.left(), binary.right());
            case TernaryExpr ternary ->
                    firstUse(names, ternary.condition(), ternary.ifTrue(), ternary.ifFalse());
            case AccessExpr access -> firstUse(names, access.target(), access.key());
            case CallExpr call -> {
                VariableExpr inCallee = findUse(call.callee(), names);
                yield inCallee != null ? inCallee : firstUse(names, call.arguments());
            }
            case NewExpr created -> {
                VariableExpr inCallee = findUse(created.callee(), names);
                yield inCallee != null ? inCallee : firstUse(names, created.arguments());
            }
            case ArrayExpr array -> firstUse(names, array.elements());
            case ObjectExpr object -> {
                for (ObjectExpr.Entry entry : object.entries()) {
                    VariableExpr use = firstUse(names, entry.key(), entry.value());
                    if (use != null) {
                        yield use;
                    }
                }
                yield null;
            }
            case TryExpr shortForm -> findUse(shortForm.inner(), names);
            case FunctionExpr ignored -> null;
            case LiteralExpr ignored -> null;
            case ErrorExpr ignored -> null;
        };
    }

    private static VariableExpr firstUse(List<String> names, Expr... exprs) {
        return firstUse(names, List.of(exprs));
    }

    private static VariableExpr firstUse(List<String> names, List<Expr> exprs) {
        for (Expr expr : exprs) {
            VariableExpr use = findUse(expr, names);
            if (use != null) {
                return use;
            }
        }
        return null;
    }

    /**
     * Возврат из функции: {@code return выражение;} или {@code return;}.
     * <p>
     * Точка с запятой обязательна — почему именно так, разобрано в {@link ReturnStmt}.
     * Здесь важно следствие для разбора: решение «есть значение или нет» принимается
     * по одному текущему токену, без заглядывания вперёд и без оглядки на переносы строк.
     */
    private Stmt returnStatement() {
        Token keyword = advance(); // return
        if (functionDepth == 0) {
            diagnostics.error(keyword.span(), "'return' допустим только внутри функции");
        }
        forbidInFinally(keyword);
        Expr value = check(TokenType.SEMICOLON) ? null : expression(0);
        if (value instanceof ErrorExpr) {
            // Возвращаемое выражение не разобралось — об этом уже сказано. Дальше по строке
            // разбирать нечего: пропускаем её, чтобы не сыпать производными ошибками.
            synchronize();
            return new ErrorStmt(keyword.span().to(value.span()));
        }
        Token end = expect(TokenType.SEMICOLON, "точку с запятой ';' после 'return'");
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
        Token keyword = advance(); // throw
        Expr value = expression(0);
        if (value instanceof ErrorExpr) {
            synchronize();
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
        Token keyword = advance(); // try
        BlockStmt body = requiredBlock("тело 'try'");
        if (body == null) {
            synchronize();
            return new ErrorStmt(keyword.span());
        }

        List<TryStmt.Catch> handlers = new ArrayList<>();
        while (check(TokenType.CATCH)) {
            TryStmt.Catch handler = catchClause();
            if (handler == null) {
                break;
            }
            handlers.add(handler);
        }

        BlockStmt finallyBlock = null;
        if (check(TokenType.FINALLY)) {
            advance();
            finallyDepth++;
            try {
                finallyBlock = requiredBlock("тело 'finally'");
            } finally {
                finallyDepth--;
            }
        }

        if (handlers.isEmpty() && finallyBlock == null) {
            diagnostics.error(keyword.span(), "у 'try' должен быть хотя бы один 'catch' "
                    + "или 'finally': без них это обычный блок");
            return new ErrorStmt(keyword.span().to(body.span()));
        }
        return new TryStmt(body, handlers, finallyBlock, keyword.span().to(lastSpan()));
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
        Token keyword = advance(); // catch
        expect(TokenType.LPAREN, "открывающую скобку '(' после 'catch'");
        if (!check(TokenType.WORD)) {
            diagnostics.error(peek().span(), "после 'catch (' ожидалось имя, под которым"
                    + " ошибка ляжет в переменную, найдено " + describe(peek()));
            synchronize();
            return null;
        }
        Token name = advance();

        List<TryStmt.TypeRef> types = new ArrayList<>();
        if (match(TokenType.IS)) {
            do {
                TypeName type = typeName("класса или трейта ошибки");
                if (type == null) {
                    return null;
                }
                for (TryStmt.TypeRef existing : types) {
                    if (existing.title().equals(type.title())) {
                        diagnostics.error(type.span(),
                                "тип '" + type.title() + "' в этом обработчике указан дважды");
                    }
                }
                types.add(new TryStmt.TypeRef(type.alias(), type.name(), type.span()));
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RPAREN, "закрывающую скобку ')' после 'catch'");

        BlockStmt body = requiredBlock("тело 'catch'");
        if (body == null) {
            synchronize();
            return null;
        }
        return new TryStmt.Catch(name.text(), name.span(), types, body,
                keyword.span().to(body.span()));
    }

    /** Блок там, где одиночная инструкция не разрешена. {@code null}, если его нет. */
    private BlockStmt requiredBlock(String what) {
        if (check(TokenType.LBRACE)) {
            return block();
        }
        diagnostics.error(peek().span(), what + " записывается блоком в фигурных скобках,"
                + " а здесь " + describe(peek()));
        return null;
    }

    /** Следом за {@code try} стоит {@code ?} или {@code !} — то есть это короткая форма. */
    private boolean shortTryAhead() {
        TokenType next = peek(1).type();
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
        Token keyword = advance(); // try
        Token sign = advance();    // ? или !
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
        Token keyword = advance(); // use
        expect(TokenType.LPAREN, "открывающую скобку '(' после 'use'");

        List<UseStmt.Binding> resources = new ArrayList<>();
        do {
            if (!check(TokenType.WORD)) {
                diagnostics.error(peek().span(), "в 'use' ожидалось имя, под которым ресурс"
                        + " ляжет в переменную, найдено " + describe(peek()));
                synchronize();
                return new ErrorStmt(keyword.span());
            }
            Token name = advance();
            for (UseStmt.Binding existing : resources) {
                if (existing.name().equals(name.text())) {
                    diagnostics.error(name.span(), "имя '" + name.text()
                            + "' в этом 'use' уже занято");
                }
            }
            expect(TokenType.ASSIGN, "знак '=' после имени ресурса");
            Expr value = expression(0);
            if (value instanceof ErrorExpr) {
                synchronize();
                return new ErrorStmt(keyword.span().to(value.span()));
            }
            resources.add(new UseStmt.Binding(name.text(), name.span(), value,
                    name.span().to(value.span())));
        } while (match(TokenType.COMMA));

        expect(TokenType.RPAREN, "закрывающую скобку ')' после списка ресурсов");
        BlockStmt body = requiredBlock("тело 'use'");
        if (body == null) {
            synchronize();
            return new ErrorStmt(keyword.span());
        }
        return new UseStmt(resources, body, keyword.span().to(body.span()));
    }

    /** {@code catch} или {@code finally} без своего {@code try}. */
    private Stmt orphanHandler() {
        Token keyword = advance();
        diagnostics.error(keyword.span(), "'" + keyword.text() + "' без 'try': "
                + "он пишется сразу после блока 'try'");
        synchronize();
        return new ErrorStmt(keyword.span());
    }

    /**
     * Выход из {@code finally} наружу запрещён — ошибка разбора, а не предупреждение.
     * <p>
     * Это та же линия, что и {@code if (x = 5)}: конструкция, у которой единственное
     * применение — незаметно проглотить ошибку, летящую наружу, не разбирается в принципе.
     * В Java она разрешена и служит источником багов, которых не видно при чтении.
     */
    private void forbidInFinally(Token keyword) {
        if (finallyDepth == 0 && deferDepth == 0) {
            return;
        }
        String where = finallyDepth > 0 ? "блоке 'finally'" : "теле 'defer'";
        diagnostics.error(keyword.span(), "'" + keyword.text() + "' в " + where + " запрещён: "
                + "он молча погасил бы ошибку, которая сейчас летит наружу."
                + " Выходите из тела 'try' или из 'catch'");
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
        Token keyword = advance(); // defer
        blockHasDefer = true;
        deferDepth++;
        try {
            Stmt body = body("'defer'");
            return new DeferStmt(body, keyword.span().to(body.span()));
        } finally {
            deferDepth--;
        }
    }

    // --- ветвления и циклы ---------------------------------------------------

    /**
     * Блок: инструкции в фигурных скобках. Внутри — тот же цикл, что и в
     * {@link #program()}, только границей служит {@code &#125;}, а не конец файла.
     */
    private BlockStmt block() {
        Token open = advance(); // {
        List<Stmt> statements = new ArrayList<>();
        // Флаг свой на каждый блок: 'defer' во вложенном блоке принадлежит ему,
        // а не внешнему, — на то и правило «выход из своей области».
        boolean outerDefer = blockHasDefer;
        blockHasDefer = false;
        skipSeparators();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            int before = index;
            statements.add(statement());
            ensureProgress(before);
            skipSeparators();
        }
        Token close = expect(TokenType.RBRACE, "закрывающую скобку '}'");
        boolean deferred = blockHasDefer;
        blockHasDefer = outerDefer;
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
        Token keyword = advance(); // if
        Expr condition = condition("'if'");
        Stmt thenBranch = body("'if'");

        Stmt elseBranch = null;
        if (check(TokenType.ELSE)) {
            advance();
            elseBranch = check(TokenType.IF) ? ifStatement() : body("'else'");
        }
        Stmt last = elseBranch != null ? elseBranch : thenBranch;
        return new IfStmt(condition, thenBranch, elseBranch, keyword.span().to(last.span()));
    }

    private Stmt whileStatement() {
        Token keyword = advance(); // while
        Expr condition = condition("'while'");
        Stmt body = loopBody("цикла 'while'");
        return new WhileStmt(condition, body, keyword.span().to(body.span()));
    }

    /**
     * Цикл {@code for} в обеих формах.
     * <p>
     * Что это за форма, видно по двум токенам после открывающей скобки: имя и
     * {@code in} — перебор, что угодно другое — цикл со счётчиком. Заглядывание
     * ровно на два токена и без отката: {@link #peek(int)} для этого и есть.
     */
    private Stmt forStatement() {
        Token keyword = advance(); // for
        expect(TokenType.LPAREN, "открывающую скобку '(' после 'for'");
        if (check(TokenType.WORD) && peek(1).type() == TokenType.IN) {
            return forEachStatement(keyword);
        }

        // Пропущенная часть остаётся null: чего нет в тексте, того нет и в дереве,
        // а «нет условия» интерпретатор читает как «повторять всегда».
        Stmt init = check(TokenType.SEMICOLON) ? null : simpleStatement();
        expect(TokenType.SEMICOLON, "точку с запятой ';' после инициализатора цикла");
        Expr condition = check(TokenType.SEMICOLON) ? null : expression(0);
        expect(TokenType.SEMICOLON, "точку с запятой ';' после условия цикла");
        Stmt step = check(TokenType.RPAREN) ? null : simpleStatement();
        expect(TokenType.RPAREN, "закрывающую скобку ')' после шага цикла");

        Stmt body = loopBody("цикла 'for'");
        return new ForStmt(init, condition, step, body, keyword.span().to(body.span()));
    }

    /** Перебор: {@code for (товар in корзина) ...}. Открывающая скобка уже съедена. */
    private Stmt forEachStatement(Token keyword) {
        Token name = advance(); // имя переменной цикла
        advance();              // in
        Expr iterable = expression(0);
        expect(TokenType.RPAREN, "закрывающую скобку ')' после перебираемого значения");
        Stmt body = loopBody("цикла 'for'");
        return new ForEachStmt(name.text(), name.span(), iterable, body, keyword.span().to(body.span()));
    }

    private Stmt breakStatement() {
        Token keyword = advance();
        requireLoop(keyword);
        forbidInFinally(keyword);
        return new BreakStmt(keyword.span());
    }

    private Stmt continueStatement() {
        Token keyword = advance();
        requireLoop(keyword);
        forbidInFinally(keyword);
        return new ContinueStmt(keyword.span());
    }

    /**
     * Условие в скобках. Скобки обязательны, и это не дань привычке: перевод строки
     * в языке токена не даёт, поэтому без скобок {@code if x { ... }} неотличимо
     * от {@code if} с литералом объекта в условии.
     */
    private Expr condition(String owner) {
        expect(TokenType.LPAREN, "открывающую скобку '(' после " + owner);
        Expr condition = expression(0);
        expect(TokenType.RPAREN, "закрывающую скобку ')' после условия " + owner);
        return condition;
    }

    /**
     * Тело управляющей конструкции: блок или одна инструкция.
     * <p>
     * Одна инструкция разрешена намеренно — {@code if (x) println("да")} читается лучше
     * четырёх строк, — но стоит помнить, что область видимости создаёт именно блок.
     */
    private Stmt body(String owner) {
        if (check(TokenType.SEMICOLON) || check(TokenType.RBRACE) || check(TokenType.EOF)) {
            diagnostics.error(peek().span(), "ожидалось тело " + owner + ", найдено " + describe(peek()));
            return new ErrorStmt(peek().span());
        }
        return statement();
    }

    /** Тело цикла: то же, что и любое тело, но внутри него разрешены break и continue. */
    private Stmt loopBody(String owner) {
        loopDepth++;
        try {
            return body(owner);
        } finally {
            loopDepth--;
        }
    }

    private void requireLoop(Token keyword) {
        if (loopDepth == 0) {
            diagnostics.error(keyword.span(),
                    "'" + keyword.text() + "' допустим только внутри цикла");
        }
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
     * <p>
     * Третья граница — токены, за которые заходить нельзя ни при каких переносах строк:
     * закрывающая фигурная скобка и слова, начинающие следующую конструкцию. Без этого
     * опечатка в теле цикла съедала бы {@code &#125;}, и одна ошибка разваливала бы
     * разбор всего оставшегося файла.
     */
    private void synchronize() {
        while (!check(TokenType.EOF)) {
            if (check(TokenType.SEMICOLON)) {
                advance();
                return;
            }
            if (peek().afterNewline() || isStatementBoundary(peek().type())) {
                return;
            }
            advance();
        }
    }

    /** Токен, дальше которого паническое восстановление не идёт. */
    private static boolean isStatementBoundary(TokenType type) {
        return switch (type) {
            case RBRACE, IF, ELSE, WHILE, FOR, BREAK, CONTINUE, CONST, FUN, CLASS, TRAIT,
                 IMPORT, RETURN, THROW, TRY, CATCH, FINALLY, DEFER, USE -> true;
            default -> false;
        };
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
            // this и super — обычные имена, а не спецформы: их можно положить
            // в переменную и передать. Особенное в них только одно — где они допустимы,
            // и это парсер знает, поэтому ошибка здесь разбора, а не выполнения.
            case THIS -> {
                advance();
                if (!thisAllowed) {
                    diagnostics.error(token.span(), className == null
                            ? "'this' допустим только внутри класса"
                            : "'this' недопустим внутри фабрики: экземпляра ещё не существует, "
                                    + "фабрика его и создаёт");
                }
                return new VariableExpr(token.text(), token.span());
            }
            case SUPER -> {
                advance();
                if (!superAllowed) {
                    diagnostics.error(token.span(), superProblem());
                }
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
                advance();
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
        List<Expr> arguments = new ArrayList<>();
        Token close = argumentList(arguments);
        return new CallExpr(callee, arguments, callee.span().to(close.span()));
    }

    /**
     * Список аргументов в скобках — общий для вызова, создания и заголовка родителя.
     *
     * @return закрывающая скобка, чтобы вызывающий знал, где кончился список
     */
    private Token argumentList(List<Expr> arguments) {
        advance(); // (
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
        return expect(TokenType.RPAREN, "закрывающую скобку ')'");
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
        Token keyword = advance(); // new
        Expr target = newTarget();
        while (check(TokenType.DOT) || check(TokenType.LBRACKET)) {
            target = access(target);
        }
        if (!check(TokenType.LPAREN)) {
            diagnostics.error(peek().span(), "после 'new' ожидался список аргументов в скобках, найдено "
                    + describe(peek()) + ". Скобки обязательны, даже пустые");
            return new ErrorExpr(keyword.span().to(target.span()));
        }
        List<Expr> arguments = new ArrayList<>();
        Token close = argumentList(arguments);
        return new NewExpr(target, arguments, keyword.span().to(close.span()));
    }

    /** То, что стоит между {@code new} и списком аргументов: имя или скобочная группа. */
    private Expr newTarget() {
        Token token = peek();
        if (token.type() == TokenType.WORD) {
            advance();
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

    /** Место уже разобранного токена — им заканчивается объявление класса или трейта. */
    private Span lastSpan() {
        return tokens.get(Math.max(0, index - 1)).span();
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

    private String superProblem() {
        if (className == null) {
            return "'super' допустим только внутри класса";
        }
        if (inTrait) {
            return "у трейта нет родителя, 'super' здесь неприменим: трейт не знает,"
                    + " в какой класс его подмешают";
        }
        return "у класса '" + className + "' нет родителя, обращаться через 'super' не к чему";
    }

    /** Имя, обозначающее сам объект: присвоить такое нельзя. */
    private static boolean isSelfName(String name) {
        return name.equals(TokenType.THIS.text()) || name.equals(TokenType.SUPER.text());
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
