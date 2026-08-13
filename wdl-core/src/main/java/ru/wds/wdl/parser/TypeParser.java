package ru.wds.wdl.parser;

import ru.wds.wdl.ast.expr.BodyStyle;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
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

    /** Родитель и аргументы его заголовка: {@code : Shape("круг")}. Скобки необязательны. */
    private ClassDeclStmt.Superclass superclass() {
        Token colon = cursor.advance(); // :
        TypeName parent = typeName("класса-родителя");
        if (parent == null) {
            return null;
        }
        List<Expr> arguments = new ArrayList<>();
        Span end = parent.span();
        if (cursor.check(TokenType.LPAREN)) {
            end = parser.argumentList(arguments).span();
        }
        return new ClassDeclStmt.Superclass(parent.alias(), parent.name(), arguments,
                colon.span().to(end));
    }

    /** Подмешанные трейты: {@code with Printable, m.Counted}. Их может быть сколько угодно. */
    private List<ClassDeclStmt.TraitRef> traitList() {
        if (!cursor.check(TokenType.WITH)) {
            return List.of();
        }
        cursor.advance(); // with
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
        if (!cursor.check(TokenType.DEF)) {
            diagnostics.error(cursor.peek().span(), "в теле " + (isClass ? "класса" : "трейта")
                    + " допустимы только объявления функций, а здесь " + describe(cursor.peek())
                    + ". Начальные значения полей задаются в заголовке");
            cursor.synchronize();
            return;
        }
        Token keyword = cursor.advance(); // def
        if (!cursor.check(TokenType.WORD)) {
            diagnostics.error(cursor.peek().span(),
                    "ожидалось имя метода, найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return;
        }
        Token name = cursor.advance();

        if (cursor.check(TokenType.DOT)) {
            factory(members, keyword, name, isClass);
            return;
        }

        String className = state.className();
        boolean isConstructor = isClass && name.text().equals(className);
        // Внутри метода и конструктора 'this' есть, 'super' — если есть родитель.
        state.allowSelf(true, hasParent);

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
     * Фабрика: {@code def User.of(name, password)}.
     * <p>
     * Имя слева обязано совпасть с самим классом — иначе это не «способ создания,
     * записанный рядом с классом», а запись в чужое значение, для которой есть
     * обычное присваивание. {@code this} внутри нет: экземпляра ещё не существует.
     */
    private void factory(Members members, Token keyword, Token owner, boolean isClass) {
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
     * Список параметров, возможно со значениями по умолчанию: {@code (a, b = 10)}.
     * Запятая после последнего разрешена — как в массивах и аргументах.
     *
     * @param callSignature задаёт ли список число аргументов вызова или создания.
     *                      У заголовка трейта — нет: там параметр без значения это
     *                      не «обязательный аргумент», а требование к классу,
     *                      и порядок для него не значит ничего
     */
    List<FunctionExpr.Param> parameters(String owner, boolean callSignature) {
        cursor.expect(TokenType.LPAREN, "открывающую скобку '(' после " + owner);
        List<FunctionExpr.Param> params = new ArrayList<>();
        while (!cursor.check(TokenType.RPAREN) && !cursor.check(TokenType.EOF)) {
            int before = cursor.position();
            if (cursor.check(TokenType.WORD)) {
                Token name = cursor.advance();
                // Значение по умолчанию — обычное выражение, а не литерал: запятая
                // оператором не является, поэтому список на нём не рвётся.
                addParameter(params, name,
                        cursor.match(TokenType.ASSIGN) ? parser.expression(0) : null,
                        callSignature);
                if (cursor.match(TokenType.COMMA) || cursor.check(TokenType.RPAREN)) {
                    continue;
                }
                diagnostics.error(cursor.peek().span(),
                        "ожидалась ',' или ')' в списке параметров, найдено " + describe(cursor.peek()));
            } else {
                diagnostics.error(cursor.peek().span(),
                        "ожидалось имя параметра, найдено " + describe(cursor.peek()));
            }
            cursor.ensureProgress(before);
        }
        cursor.expect(TokenType.RPAREN, "закрывающую скобку ')' после списка параметров");
        if (callSignature) {
            DefaultValues.checkLookLeft(params, diagnostics);
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
}
