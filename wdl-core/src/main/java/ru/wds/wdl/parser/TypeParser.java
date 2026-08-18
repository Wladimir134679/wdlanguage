package ru.wds.wdl.parser;

import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.ast.expr.BodyStyle;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.Modifier;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.ErrorStmt;
import ru.wds.wdl.ast.stmt.ReturnStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.lexer.TokenType;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static ru.wds.wdl.parser.TokenCursor.describe;

/**
 * Разбор объявлений типов: классы, трейты, их тела и списки параметров.
 * <p>
 * Отделено от {@link Parser} потому, что зависимость почти односторонняя: отсюда
 * наружу нужны только выражение, блок и список аргументов — всё остальное замкнуто
 * внутри. Заодно здесь собраны все правила, которые язык проверяет при разборе
 * объявления: конструктор без параметров, фабрика на своём классе, одноимённые
 * члены, трейт без родителя.
 * <p>
 * Список параметров и имя типа нужны и самому парсеру — анонимной функции и
 * обработчику {@code catch}, — поэтому {@link #parameters} и {@link #typeName}
 * видны из пакета.
 */
final class TypeParser {

    private final Parser parser;
    private final TokenCursor cursor;
    private final ParseState state;
    private final Diagnostics diagnostics;

    TypeParser(Parser parser, TokenCursor cursor, ParseState state, Diagnostics diagnostics) {
        this.parser = parser;
        this.cursor = cursor;
        this.state = state;
        this.diagnostics = diagnostics;
    }

    /**
     * Объявление класса: {@code class Point(x = 0, y = 0) : Shape("точка") with Printable { ... }}.
     * <p>
     * Порядок частей фиксирован — сначала родитель, потом трейты, — и он же порядок
     * сборки плоских таблиц: «сначала родитель, потом примеси, потом я сам».
     * Читать такое объявление можно слева направо, не зная никаких правил линеаризации.
     */
    Stmt classDeclaration() {
        Token keyword = cursor.advance(); // class
        Token name = expectTypeName("класса");
        if (name == null) {
            cursor.synchronize();
            return new ErrorStmt(keyword.span());
        }

        List<FunctionExpr.Param> params = cursor.check(TokenType.LPAREN)
                ? parameters("имени класса '" + name.text() + "'", true)
                : List.of();
        ClassDeclStmt.Superclass parent = cursor.check(TokenType.COLON) ? superclass() : null;
        List<ClassDeclStmt.TraitRef> traits = traitList();
        if (cursor.check(TokenType.COLON)) {
            diagnostics.error(cursor.peek().span(), "родитель указывается перед трейтами: "
                    + "class " + name.text() + "(...) : Родитель with Трейт");
        }

        Members members = typeBody(name.text(), parent != null, true);
        Span span = keyword.span().to(cursor.lastSpan());
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
    Stmt traitDeclaration() {
        Token keyword = cursor.advance(); // trait
        Token name = expectTypeName("трейта");
        if (name == null) {
            cursor.synchronize();
            return new ErrorStmt(keyword.span());
        }

        List<FunctionExpr.Param> params = cursor.check(TokenType.LPAREN)
                ? parameters("имени трейта '" + name.text() + "'", false)
                : List.of();
        DefaultValues.checkTraitFields(params, diagnostics);
        if (cursor.check(TokenType.COLON) || cursor.check(TokenType.WITH)) {
            diagnostics.error(cursor.peek().span(), "у трейта не бывает ни родителя, ни подмешанных трейтов: "
                    + "трейт — это то, что подмешивают");
            cursor.synchronize();
        }

        Members members = typeBody(name.text(), false, false);
        Span span = keyword.span().to(cursor.lastSpan());
        return new TraitDeclStmt(name.text(), name.span(), params,
                members.methods, members.requirements, span);
    }

    private Token expectTypeName(String what) {
        if (cursor.check(TokenType.WORD)) {
            return cursor.advance();
        }
        diagnostics.error(cursor.peek().span(),
                "ожидалось имя " + what + ", найдено " + describe(cursor.peek()));
        return null;
    }

    /**
     * Имя типа в обработчике {@code catch}: {@code Shape} или {@code m.Shape},
     * где {@code m} — имя именованного импорта.
     * <p>
     * Больше одной точки здесь не принимается — в отличие от заголовка класса,
     * где стоит {@link #typeExpression выражение}. Причина не в разборе, а в том,
     * что {@code catch} перебирает свои типы на каждой ошибке: вычислять там цепочку
     * с вызовами значило бы звать чужой код на пути обработки уже случившейся
     * ошибки — ровно в тот момент, когда лишним побочным эффектам верить нельзя.
     *
     * @return {@code null}, если имени нет; об ошибке уже сказано
     */
    TypeName typeName(String what) {
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(),
                    "ожидалось имя " + what + ", найдено " + describe(cursor.peek()));
            return null;
        }
        Token first = cursor.advance();
        if (!cursor.check(TokenType.DOT)) {
            return new TypeName(null, first.text(), first.span());
        }
        cursor.advance(); // .
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(), "после '" + first.text() + ".' ожидалось имя "
                    + what + ", найдено " + describe(cursor.peek()));
            return null;
        }
        Token name = cursor.advance();
        if (cursor.check(TokenType.DOT)) {
            diagnostics.error(cursor.peek().span(), "слева от точки здесь стоит имя импорта, "
                    + "а не значение: '" + first.text() + "." + name.text() + "' — уже полное имя");
            return null;
        }
        return new TypeName(first.text(), name.text(), first.span().to(name.span()));
    }

    /**
     * Ссылка на тип в заголовке класса: цепочка обращений и вызовов, дающая класс
     * или трейт.
     * <p>
     * Разбирается силой {@link Operators#ACCESS}, поэтому в цепочку попадают только
     * {@code .имя}, {@code [ключ]} и {@code (аргументы)} — то есть ровно способы
     * достать значение из другого значения. Любой бинарный оператор слабее и цепочку
     * обрывает: {@code class A : x + y} не разберётся, и это правильно — складывать
     * классы незачем, а внятная ошибка лучше вычисления неизвестно чего.
     * <p>
     * Начинается цепочка всегда с имени. Не из-за разбора — {@code prefix()} принял бы
     * и литерал, — а из-за сообщения: «ожидалось имя класса-родителя» на месте
     * {@code : 42} полезнее, чем «наследоваться можно только от класса» при выполнении.
     *
     * @return {@code null}, если имени нет; об ошибке уже сказано
     */
    private Expr typeExpression(String what) {
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(),
                    "ожидалось имя " + what + ", найдено " + describe(cursor.peek()));
            return null;
        }
        return parser.expression(Operators.ACCESS);
    }

    /**
     * Родитель и аргументы его заголовка: {@code : Shape("круг")}, {@code : m.Shape},
     * {@code : registry.classes["Shape"](1)}.
     * <p>
     * <b>Последние скобки цепочки — аргументы заголовка, а не вызов.</b> Разобрать
     * их отдельно нельзя: {@code Shape("круг")} — это уже готовый {@link CallExpr},
     * и решение принимается здесь, расщеплением. Иначе основная форма записи
     * означала бы «вызвать Shape и наследоваться от того, что вернулось».
     * Вызову внутри цепочки это не мешает: у {@code registry.all()["Shape"]}
     * последняя операция — обращение, и скобки достаются {@code all}.
     */
    private ClassDeclStmt.Superclass superclass() {
        Token colon = cursor.advance(); // :
        Expr type = typeExpression("класса-родителя");
        if (type == null) {
            return null;
        }
        List<Argument> arguments = List.of();
        if (type instanceof CallExpr call) {
            arguments = call.arguments();
            type = call.callee();
        }
        return new ClassDeclStmt.Superclass(type, arguments, colon.span().to(cursor.lastSpan()));
    }

    /**
     * Подмешанные трейты: {@code with Printable, m.Counted, plugins["Logged"]}.
     * Их может быть сколько угодно.
     * <p>
     * Скобки здесь, в отличие от {@link #superclass()}, — обычный вызов: передавать
     * трейту нечего, конструктора у него нет, поэтому {@code with make()} однозначно
     * значит «вызвать и подмешать результат».
     */
    private List<ClassDeclStmt.TraitRef> traitList() {
        if (!cursor.check(TokenType.WITH)) {
            return List.of();
        }
        cursor.advance(); // with
        List<ClassDeclStmt.TraitRef> traits = new ArrayList<>();
        do {
            Expr type = typeExpression("трейта");
            if (type == null) {
                break;
            }
            ClassDeclStmt.TraitRef trait = new ClassDeclStmt.TraitRef(type, type.span());
            for (ClassDeclStmt.TraitRef existing : traits) {
                // Сравниваются записи, а не значения: одинаковый текст — почти наверняка
                // описка, а разный текст может дать один трейт, и это выяснится только
                // при выполнении. Повтор там не ошибка — таблицы всё равно плоские.
                if (existing.title().equals(trait.title())) {
                    diagnostics.error(trait.span(), "трейт '" + trait.title() + "' подмешан дважды");
                }
            }
            traits.add(trait);
        } while (cursor.match(TokenType.COMMA));
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

    /**
     * Тело класса или трейта: только объявления функций.
     * <p>
     * Класс — это описание, а не код, который что-то делает в момент объявления,
     * поэтому любая другая инструкция здесь ошибка. Тела вовсе может не быть:
     * класс без методов законен и полезен — это структура данных с именем.
     */
    private Members typeBody(String name, boolean hasParent, boolean isClass) {
        Members members = new Members();
        if (!cursor.check(TokenType.LBRACE)) {
            return members;
        }
        cursor.advance(); // {

        state.inTypeBody(name, isClass, () -> {
            cursor.skipSeparators();
            while (!cursor.check(TokenType.RBRACE) && !cursor.check(TokenType.EOF)) {
                int before = cursor.position();
                member(members, hasParent, isClass);
                cursor.ensureProgress(before);
                cursor.skipSeparators();
            }
            return null;
        });

        cursor.expect(TokenType.RBRACE, "закрывающую скобку '}'");
        return members;
    }

    /** Один член: метод, конструктор, фабрика или — только в трейте — требование. */
    private void member(Members members, boolean hasParent, boolean isClass) {
        Token start = cursor.peek();
        Set<Modifier> modifiers = Set.of();
        if (cursor.check(TokenType.SYNCHRONIZED)) {
            cursor.advance();
            if (!cursor.check(TokenType.DEF)) {
                diagnostics.error(start.span(), "'synchronized' — это модификатор метода: "
                        + "он ставится перед 'def'");
                cursor.synchronize();
                return;
            }
            modifiers = Set.of(Modifier.SYNCHRONIZED);
        }
        if (!cursor.check(TokenType.DEF)) {
            diagnostics.error(cursor.peek().span(), "в теле " + (isClass ? "класса" : "трейта")
                    + " допустимы только объявления функций, а здесь " + describe(cursor.peek())
                    + ". Начальные значения полей задаются в заголовке");
            cursor.synchronize();
            return;
        }
        cursor.advance(); // def
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(),
                    "ожидалось имя метода, найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return;
        }
        Token name = cursor.advance();

        if (cursor.check(TokenType.DOT)) {
            factory(members, start, name, isClass, modifiers);
            return;
        }

        String className = state.className();
        boolean isConstructor = isClass && name.text().equals(className);
        // Внутри метода и конструктора 'this' есть, 'super' — если есть родитель.
        state.allowSelf(true, hasParent);

        Params header = parameters("имени " + (isConstructor ? "конструктора" : "метода")
                + " '" + name.text() + "'", true, true);
        List<FunctionExpr.Param> params = header.params();
        if (isConstructor && !(params.isEmpty() && header.rest() == null && header.namedRest() == null)) {
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
                    header.rest() != null, start.span().to(name.span())));
            return;
        }

        FunctionExpr function = new FunctionExpr(name.text(), modifiers, params,
                header.rest(), header.namedRest(), body,
                bodyStyle(body), start.span().to(body.span()));
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
     * Фабрика: {@code def User.of(name, password)}.
     * <p>
     * Имя слева обязано совпасть с самим классом — иначе это не «способ создания,
     * записанный рядом с классом», а запись в чужое значение, для которой есть
     * обычное присваивание. {@code this} внутри нет: экземпляра ещё не существует.
     */
    private void factory(Members members, Token start, Token owner, boolean isClass,
                         Set<Modifier> modifiers) {
        cursor.advance(); // .
        if (!isClass) {
            diagnostics.error(owner.span(), "у трейта не бывает фабрик: "
                    + "экземпляр создаёт класс, ему они и принадлежат");
        } else if (!owner.text().equals(state.className())) {
            diagnostics.error(owner.span(), "фабрика объявляется на своём классе: "
                    + "здесь '" + owner.text() + "', а объявляется класс '" + state.className() + "'");
        }
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(),
                    "после точки ожидалось имя фабрики, найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return;
        }
        Token name = cursor.advance();
        String full = owner.text() + "." + name.text();

        state.allowSelf(false, false);
        Params header = parameters("имени фабрики '" + full + "'", true, true);
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
                new FunctionExpr(full, modifiers, header.params(), header.rest(), header.namedRest(),
                        body, bodyStyle(body), start.span().to(body.span())),
                start.span().to(body.span())));
    }

    /**
     * Тело члена: блок или {@code => выражение}, и ничего третьего.
     * <p>
     * Тело одной инструкцией без скобок здесь запрещено, в отличие от {@code if}
     * и циклов, и причина в требованиях трейта: у {@code def report()} тела нет,
     * а следующей строкой идёт {@code def full() => ...}. Разреши мы инструкцию
     * без скобок — второе объявление молча стало бы телом первого.
     *
     * @return {@code null}, если тела нет; для трейта это требование, для класса ошибка
     */
    private Stmt memberBody() {
        return state.inFunctionBody(() -> {
            if (cursor.match(TokenType.FATARROW)) {
                Expr value = parser.expression(0);
                return new ReturnStmt(value, value.span());
            }
            return cursor.check(TokenType.LBRACE) ? parser.block() : null;
        });
    }

    private static BodyStyle bodyStyle(Stmt body) {
        return body instanceof BlockStmt ? BodyStyle.STATEMENT : BodyStyle.ARROW;
    }

    private static String duplicate(String name) {
        return "'" + name + "' в этом теле уже объявлен: "
                + "пространство имён одно, два значения по одному ключу не лежат";
    }

    // --- параметры -----------------------------------------------------------

    /**
     * Разобранный заголовок: параметры по позициям и, отдельно, остатки.
     * <p>
     * Отдельно — потому что остаток позиции не занимает, а список параметров читается
     * везде как список позиций. Подробнее — в {@link FunctionExpr.Rest}.
     */
    record Params(List<FunctionExpr.Param> params, FunctionExpr.Rest rest,
                  FunctionExpr.Rest namedRest) {
    }

    /**
     * Список параметров без остатков: {@code (a, b = 10)}.
     * <p>
     * Такой список у заголовка класса и трейта. Остаток там запрещён не из осторожности:
     * заголовок класса задаёт ещё и поля, у каждого из которых есть номер параметра
     * ({@code resolve.FieldSlot}), и поле-остаток требует сперва решить, чем оно является
     * для наследника, для трейта и для требования. Отдельная работа, и она ничего
     * не ломает: раскрытие в {@code new} классам достаётся и так — список аргументов
     * в скобках в языке один.
     */
    List<FunctionExpr.Param> parameters(String owner, boolean callSignature) {
        return parameters(owner, callSignature, false).params();
    }

    /**
     * Список параметров, возможно со значениями по умолчанию и остатками:
     * {@code (a, b = 10, *args, **named)}.
     * Запятая после последнего разрешена — как в массивах и аргументах.
     *
     * @param callSignature задаёт ли список число аргументов вызова или создания.
     *                      У заголовка трейта — нет: там параметр без значения это
     *                      не «обязательный аргумент», а требование к классу,
     *                      и порядок для него не значит ничего
     * @param allowRest     разрешены ли остаточные параметры — то есть функция это
     *                      или метод, а не заголовок типа
     */
    Params parameters(String owner, boolean callSignature, boolean allowRest) {
        cursor.expect(TokenType.LPAREN, "открывающую скобку '(' после " + owner);
        List<FunctionExpr.Param> params = new ArrayList<>();
        FunctionExpr.Rest rest = null;
        FunctionExpr.Rest namedRest = null;
        while (!cursor.check(TokenType.RPAREN) && !cursor.check(TokenType.EOF)) {
            int before = cursor.position();
            if (cursor.check(TokenType.STAR) || cursor.check(TokenType.STARSTAR)) {
                boolean named = cursor.check(TokenType.STARSTAR);
                cursor.advance(); // * или **
                FunctionExpr.Rest declared = restParameter(named, params, rest, namedRest, allowRest);
                if (declared != null && named) {
                    namedRest = declared;
                } else if (declared != null) {
                    rest = declared;
                }
            } else if (cursor.check(TokenType.WORD)) {
                Token name = cursor.advance();
                // Значение по умолчанию — обычное выражение, а не литерал: запятая
                // оператором не является, поэтому список на нём не рвётся.
                addParameter(params, name,
                        cursor.match(TokenType.ASSIGN) ? parser.expression(0) : null,
                        callSignature, rest, namedRest);
            } else {
                diagnostics.error(cursor.peek().span(),
                        "ожидалось имя параметра, найдено " + describe(cursor.peek()));
                cursor.ensureProgress(before);
                continue;
            }
            if (cursor.match(TokenType.COMMA) || cursor.check(TokenType.RPAREN)) {
                continue;
            }
            diagnostics.error(cursor.peek().span(),
                    "ожидалась ',' или ')' в списке параметров, найдено " + describe(cursor.peek()));
            cursor.ensureProgress(before);
        }
        cursor.expect(TokenType.RPAREN, "закрывающую скобку ')' после списка параметров");
        if (callSignature) {
            DefaultValues.checkLookLeft(params, diagnostics);
        }
        return new Params(params, rest, namedRest);
    }

    /**
     * Остаточный параметр: {@code *args} или {@code **named}. Звёздочка уже прочитана.
     * <p>
     * Все запреты здесь — про порядок, и все они об одном: остаток заканчивает заголовок.
     * Обычный параметр после {@code *args} потребовал бы понятия «только именованный
     * параметр» — его в языке нет, и добавить его позже эта форма не мешает.
     *
     * @return объявленный остаток или {@code null}, если объявить его не вышло
     */
    private FunctionExpr.Rest restParameter(boolean named, List<FunctionExpr.Param> params,
                                            FunctionExpr.Rest rest, FunctionExpr.Rest namedRest,
                                            boolean allowRest) {
        String stars = named ? "**" : "*";
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(), "после '" + stars
                    + "' ожидалось имя остаточного параметра, найдено " + describe(cursor.peek()));
            return null;
        }
        Token name = cursor.advance();
        if (cursor.match(TokenType.ASSIGN)) {
            // Выражение всё равно разбирается: иначе список порвётся на ровном месте
            // и к одной ошибке добавится вторая, про неожиданный токен.
            parser.expression(0);
            diagnostics.error(name.span(), "у остаточного параметра '" + name.text()
                    + "' не может быть значения по умолчанию: пустой остаток и есть его значение");
        }
        if (!allowRest) {
            diagnostics.error(name.span(), "остаточный параметр '" + name.text() + "' здесь"
                    + " не разрешён: этот список задаёт поля, а не аргументы вызова");
            return null;
        }
        FunctionExpr.Rest same = named ? namedRest : rest;
        if (same != null) {
            diagnostics.error(name.span(), "остаточный параметр '" + stars + same.name()
                    + "' уже объявлен");
            return null;
        }
        if (!named && namedRest != null) {
            diagnostics.error(name.span(), "остаточный параметр '*" + name.text()
                    + "' не может идти после '**" + namedRest.name() + "': именованный остаток"
                    + " заканчивает заголовок");
            return null;
        }
        for (FunctionExpr.Param existing : params) {
            if (existing.name().equals(name.text())) {
                diagnostics.error(name.span(), "параметр '" + name.text() + "' уже объявлен");
                return null;
            }
        }
        if (rest != null && rest.name().equals(name.text())) {
            diagnostics.error(name.span(), "параметр '" + name.text() + "' уже объявлен");
            return null;
        }
        return new FunctionExpr.Rest(name.text(), name.span());
    }

    /**
     * Добавляет параметр, поймав одноимённый, обязательный после необязательного
     * и обычный после остаточного.
     * <p>
     * Два параметра с одним именем — не спор о вкусе: второй молча перекрыл бы первый,
     * и один из аргументов стал бы недоступен.
     * <p>
     * <b>После параметра со значением по умолчанию обязательных быть не может.</b>
     * Причина не эстетическая: позиционный вызов читается по префиксу списка параметров,
     * и у {@code def f(a = 1, b)} вызов {@code f(1)} оказался бы либо ошибкой, либо
     * тихой догадкой о том, куда пошла единица. Именованные аргументы это правило
     * не отменяют: они дают пропуск записать, но не делают позиционный вызов понятнее.
     * Число аргументов при этом остаётся одним отрезком ({@link ru.wds.wdl.value.Arity})
     * — до тех пор, пока не объявлен {@code *args}.
     */
    private void addParameter(List<FunctionExpr.Param> params, Token name, Expr defaultValue,
                              boolean callSignature, FunctionExpr.Rest rest,
                              FunctionExpr.Rest namedRest) {
        for (FunctionExpr.Param existing : params) {
            if (existing.name().equals(name.text())) {
                diagnostics.error(name.span(), "параметр '" + name.text() + "' уже объявлен");
                return;
            }
        }
        if (rest != null || namedRest != null) {
            FunctionExpr.Rest after = rest != null ? rest : namedRest;
            String stars = rest != null ? "*" : "**";
            diagnostics.error(name.span(), "параметр '" + name.text() + "' не может идти"
                    + " после остаточного параметра '" + stars + after.name() + "'");
            return;
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
}
