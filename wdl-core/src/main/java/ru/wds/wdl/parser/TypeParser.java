package ru.wds.wdl.parser;

import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.AccessStyle;
import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.ast.expr.BodyStyle;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.LiteralExpr;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.ast.expr.Modifier;
import ru.wds.wdl.ast.op.Overloads;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.ErrorStmt;
import ru.wds.wdl.ast.stmt.ExtendStmt;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.ast.stmt.PropertyStyle;
import ru.wds.wdl.ast.stmt.ReturnStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.lexer.TokenType;
import ru.wds.wdl.value.types.StringValue;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.EnumSet;
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

    /**
     * Контекстные слова свойства. Не {@link TokenType}, и это осознанно: ключевые слова
     * лексера глобальны, а отнимать у чужих скриптов имена {@code property}, {@code get}
     * и {@code set} ради трёх мест разбора — плохая сделка. Слово {@code field}
     * не попало даже сюда: оно вообще не слово разбора, а имя, которое заводит область
     * аксессора, как {@code this}.
     */
    private static final String PROPERTY = "property";
    private static final String EXTEND = "extend";
    /** Слово перед {@code def} у оператора, получатель которого стоит справа. */
    static final String MIRROR = "mirror";
    private static final String GET = "get";
    private static final String SET = "set";

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
     * Начинается ли здесь расширение: {@code extend Array {}}.
     * <p>
     * Контекстное слово, а не {@link TokenType}, — по той же причине, что
     * у {@link #PROPERTY}: ключевые слова лексера глобальны, и отнимать у чужих
     * скриптов имя {@code extend} ради одной конструкции плохая сделка. Спутать
     * не с чем: два имени подряд выражением не бывают.
     */
    boolean isExtend() {
        return cursor.check(TokenType.WORD)
                && EXTEND.equals(cursor.peek().text())
                && cursor.peek(1).type() == TokenType.WORD;
    }

    /**
     * Расширение типа или класса: {@code extend Array { property second => this[1] }}.
     * <p>
     * Тело разбирается тем же кодом, что тело класса, — слова в нём те же, и заводить
     * им второе написание язык не станет. Отличий от класса ровно три, и все три
     * проверяются здесь: цель уже существует (поэтому конструктора не бывает),
     * родителя нет (поэтому нет и {@code super}), а хранить значение члену негде
     * (поэтому нет скрытого поля).
     *
     * @param topLevel стоит ли инструкция на верхнем уровне файла; иначе объявление
     *                 меняло бы поведение уже отработавшего кода в середине запуска
     */
    Stmt extendDeclaration(boolean topLevel) {
        Token keyword = cursor.advance(); // extend
        Token first = cursor.advance();   // имя — проверено в isExtend
        Expr target = new VariableExpr(first.text(), first.span());
        StringBuilder label = new StringBuilder(first.text());
        // Цель бывает и из модуля: 'extend shapes.Point'. Класс — обычное значение,
        // и требовать здесь одно голое имя значило бы заводить исключение.
        while (cursor.check(TokenType.DOT) && cursor.peek(1).type() == TokenType.WORD) {
            cursor.advance();
            Token part = cursor.advance();
            label.append('.').append(part.text());
            target = new AccessExpr(target, new LiteralExpr(StringValue.of(part.text()), part.span()),
                    AccessStyle.DOT, first.span().to(part.span()));
        }
        if (!topLevel) {
            diagnostics.error(keyword.span(), "'extend' разрешён только на верхнем уровне файла: "
                    + "объявление внутри условия или функции меняло бы поведение уже "
                    + "отработавшего кода в середине запуска");
        }
        if (!cursor.check(TokenType.LBRACE)) {
            diagnostics.error(cursor.peek().span(), "после 'extend " + label
                    + "' ожидается тело в фигурных скобках, найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return new ErrorStmt(keyword.span().to(cursor.lastSpan()));
        }
        Members members = typeBody(label.toString(), false, true);
        if (members.constructor != null) {
            diagnostics.error(members.constructor.span(), "у расширения нет конструктора: "
                    + "'" + label + "' уже существует, а 'def " + label + "()' в теле "
                    + "расширения читается как попытка его создать");
        }
        for (ClassDeclStmt.Factory factory : members.factories) {
            diagnostics.error(factory.span(), "фабрика в расширении не бывает: "
                    + "она принадлежит самому классу, а расширение добавляет члены его значениям");
        }
        for (PropertyDecl property : members.properties) {
            if (property.hasBackingField()) {
                diagnostics.error(property.span(), "у свойства расширения не бывает скрытого поля: "
                        + "хранить его негде — получатель чужой");
            }
        }
        return new ExtendStmt(target, label.toString(), List.copyOf(members.methods),
                List.copyOf(members.properties), keyword.span().to(cursor.lastSpan()));
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

        // Остаток в заголовке разрешён: без него класс-обёртка не смог бы перебросить
        // аргументы родителю, а именно так декоратор оборачивает класс.
        Params header = cursor.check(TokenType.LPAREN)
                ? parameters("имени класса '" + name.text() + "'", true, true)
                : Params.empty();
        List<FunctionExpr.Param> params = header.params();
        ClassDeclStmt.Superclass parent = cursor.check(TokenType.COLON) ? superclass() : null;
        List<ClassDeclStmt.TraitRef> traits = traitList();
        if (cursor.check(TokenType.COLON)) {
            diagnostics.error(cursor.peek().span(), "родитель указывается перед трейтами: "
                    + "class " + name.text() + "(...) : Родитель with Трейт");
        }

        Members members = typeBody(name.text(), parent != null, true);
        Span span = keyword.span().to(cursor.lastSpan());
        return new ClassDeclStmt(name.text(), name.span(), params, header.rest(), header.namedRest(),
                parent, traits, members.constructor, members.methods, members.factories,
                members.properties, span);
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
                members.methods, members.requirements, members.properties, span);
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
        private final List<PropertyDecl> properties = new ArrayList<>();

        /**
         * Занято ли имя. Сравнение идёт по <b>ключу члена</b>, а не по написанию:
         * {@code def `+`(right)} и {@code mirror def `+`(left)} — два разных члена
         * одного класса, и ячейки у них разные.
         */
        private boolean taken(String name) {
            if (constructor != null && constructor.name().equals(name)) {
                return true;
            }
            for (FunctionExpr method : methods) {
                if (method.memberName().equals(name)) {
                    return true;
                }
            }
            for (TraitDeclStmt.Requirement requirement : requirements) {
                if (key(requirement).equals(name)) {
                    return true;
                }
            }
            // Свойство занимает ячейку имени наравне с методом: пространство имён одно,
            // и два значения по одному ключу не лежат.
            for (PropertyDecl property : properties) {
                if (property.name().equals(name)) {
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

    /**
     * Один член: метод, конструктор, фабрика, свойство или — только в трейте —
     * требование.
     */
    private void member(Members members, boolean hasParent, boolean isClass) {
        Token start = cursor.peek();
        Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        if (isMirror()) {
            cursor.advance();
            modifiers.add(Modifier.MIRROR);
        }
        if (cursor.check(TokenType.SYNCHRONIZED)) {
            cursor.advance();
            if (isProperty()) {
                diagnostics.error(start.span(), "'synchronized' у свойства не бывает: "
                        + "замок экземпляра берёт synchronized-метод, а свойство читают "
                        + "внутри выражения, где он всё равно ничего не склеит");
                cursor.synchronize();
                return;
            }
            if (!cursor.check(TokenType.DEF)) {
                diagnostics.error(start.span(), "'synchronized' — это модификатор метода: "
                        + "он ставится перед 'def'");
                cursor.synchronize();
                return;
            }
            modifiers.add(Modifier.SYNCHRONIZED);
        }
        if (isProperty()) {
            property(members, hasParent, isClass);
            return;
        }
        if (!cursor.check(TokenType.DEF)) {
            diagnostics.error(cursor.peek().span(), "в теле " + (isClass ? "класса" : "трейта")
                    + " допустимы только объявления функций и свойств, а здесь "
                    + describe(cursor.peek())
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
        if (!operatorName(name, modifiers)) {
            cursor.synchronize();
            return;
        }

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
        if (name.quoted()) {
            operatorArity(name, header, modifiers);
        }
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
            TraitDeclStmt.Requirement requirement = new TraitDeclStmt.Requirement(name.text(),
                    params, header.rest() != null, modifiers.contains(Modifier.MIRROR),
                    start.span().to(name.span()));
            if (members.taken(key(requirement))) {
                diagnostics.error(name.span(), duplicate(name.text()));
                return;
            }
            members.requirements.add(requirement);
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
        if (members.taken(function.memberName())) {
            diagnostics.error(name.span(), duplicate(name.text()));
            return;
        }
        members.methods.add(function);
    }

    /**
     * Начинается ли член со слова {@code property}.
     * <p>
     * <b>Контекстное слово, а не ключевое.</b> Ключевые слова лексера глобальны:
     * заведи мы {@code property} там — и обычная переменная с таким именем перестала бы
     * существовать во всех чужих скриптах. Здесь этого не нужно: член тела класса
     * иначе может начаться только с {@code def} или {@code synchronized}, поэтому
     * слово, за которым стоит имя, ни с чем не спутать. Метод назвать
     * {@code property} по-прежнему можно — он начинается с {@code def}.
     */
    private boolean isProperty() {
        return cursor.check(TokenType.WORD)
                && PROPERTY.equals(cursor.peek().text())
                && cursor.peek(1).type() == TokenType.WORD;
    }

    /**
     * Начинается ли член со слова {@code mirror}.
     * <p>
     * Контекстное слово, как {@link #PROPERTY} и {@link #EXTEND}, и по той же
     * записанной причине: отбирать у чужих скриптов имя ради одной конструкции —
     * плохая сделка. Спутать не с чем, слово значимо только вплотную перед
     * {@code def} (или перед {@code synchronized def}); метод, названный
     * {@code mirror}, по-прежнему объявляется — он начинается с {@code def}.
     * <p>
     * {@code synchronized} здесь не образец: он был токеном лексера ещё до того,
     * как это правило появилось.
     */
    private boolean isMirror() {
        return cursor.check(TokenType.WORD)
                && !cursor.peek().quoted()
                && MIRROR.equals(cursor.peek().text())
                && (cursor.peek(1).type() == TokenType.DEF
                        || cursor.peek(1).type() == TokenType.SYNCHRONIZED);
    }

    /**
     * Проверяет имя члена: обычное оно или оператор в обратных кавычках.
     * <p>
     * <b>Проверка стоит при разборе, а не при выполнении.</b> И имя оператора,
     * и его допустимость известны прямо по тексту, а узнавать об опечатке в
     * {@code `=<`} на первом же вычислении незачем. Промах здесь почти всегда значит
     * одно из двух — написана производная запись ({@code `!=`}, {@code `<`},
     * {@code `has`}) или оператор, который не перегружается в принципе, — поэтому
     * {@link Overloads} отвечает причиной, а не общим «такого оператора нет».
     *
     * @return {@code false}, если разбирать этот член дальше нечего
     */
    private boolean operatorName(Token name, Set<Modifier> modifiers) {
        String text = name.text();
        if (!name.quoted()) {
            if (modifiers.contains(Modifier.MIRROR)) {
                diagnostics.error(name.span(), "'mirror' бывает только у оператора: "
                        + "у метода '" + text + "' сторон нет");
                return false;
            }
            return true;
        }
        if (cursor.check(TokenType.DOT)) {
            diagnostics.error(name.span(), "фабрика оператором не бывает: имя в обратных "
                    + "кавычках объявляет оператор, а он принадлежит значению, а не классу");
            return false;
        }
        String derived = Overloads.derived(text);
        if (derived != null) {
            diagnostics.error(name.span(), "оператор '" + text + "' не объявляют: " + derived);
            return false;
        }
        String forbidden = Overloads.forbidden(text);
        if (forbidden != null) {
            diagnostics.error(name.span(), "оператор '" + text + "' не перегружается: " + forbidden);
            return false;
        }
        if (!Overloads.isBinary(text) && !Overloads.isUnary(text)) {
            diagnostics.error(name.span(), "'" + text + "' — не оператор: в обратных кавычках "
                    + "у члена типа стоит имя оператора. Список — docs/expressions.md");
            return false;
        }
        if (modifiers.contains(Modifier.MIRROR) && !Overloads.isBinary(text)) {
            diagnostics.error(name.span(), "'mirror' бывает только у бинарного оператора: "
                    + "у унарного один операнд, и переворачивать нечего");
            return false;
        }
        if (modifiers.contains(Modifier.MIRROR) && !Overloads.allowsMirror(text)) {
            diagnostics.error(name.span(), "у оператора '" + text + "' не бывает 'mirror': "
                    + "'x in c' и 'c has x' — одна и та же запись с разных сторон");
            return false;
        }
        return true;
    }

    /**
     * Проверяет форму заголовка оператора: бинарный — один параметр, унарный — ни одного.
     * <p>
     * <b>Арность и есть различие</b> между {@code `-`} как вычитанием и {@code `-`}
     * как сменой знака: больше их различать нечем, и незачем. Ни остатка, ни значения
     * по умолчанию у оператора не бывает — операндов у выражения ровно столько,
     * сколько написано в тексте, и «необязательный правый операнд» смысла не имеет.
     */
    private void operatorArity(Token name, Params header, Set<Modifier> modifiers) {
        String text = name.text();
        boolean plain = header.rest() == null && header.namedRest() == null
                && header.params().stream().noneMatch(FunctionExpr.Param::hasDefault);
        int count = header.params().size();
        if (plain && count == 1 && Overloads.isBinary(text)) {
            return;
        }
        if (plain && count == 0 && Overloads.isUnary(text)) {
            if (modifiers.contains(Modifier.MIRROR)) {
                diagnostics.error(name.span(), "'mirror' бывает только у бинарного оператора: "
                        + "у унарного один операнд, и переворачивать нечего");
            }
            return;
        }
        diagnostics.error(name.span(), arityMessage(text));
    }

    /** Ключ требования в таблице членов — тем же правилом, что у метода. */
    private static String key(TraitDeclStmt.Requirement requirement) {
        return Overloads.key(requirement.name(), requirement.mirror(),
                requirement.params().isEmpty() && !requirement.variadic());
    }

    private static String arityMessage(String text) {
        if (Overloads.isBinary(text) && Overloads.isUnary(text)) {
            return "у оператора '" + text + "' либо один параметр — слева получатель, "
                    + "справа единственный аргумент, — либо ни одного: тогда он унарный";
        }
        if (Overloads.isBinary(text)) {
            return "у оператора '" + text + "' один параметр: слева получатель, "
                    + "справа единственный аргумент";
        }
        return "у оператора '" + text + "' параметров не бывает: он применяется "
                + "к одному операнду, и это получатель";
    }

    /**
     * Свойство: {@code property area => this.w * this.h} или блок с аксессорами.
     * <p>
     * Разбирается здесь, а не через {@link #memberBody()}, потому что тело у свойства
     * не одно: блок свойства — это список аксессоров, а не инструкции. Общее у них
     * только то, что внутри есть {@code this}, и оно включается тем же
     * {@link ParseState#allowSelf}.
     */
    private void property(Members members, boolean hasParent, boolean isClass) {
        Token keyword = cursor.advance(); // property
        Token name = cursor.advance();    // имя — проверено в isProperty

        // Начальное значение скрытого поля: 'property x = 0 { ... }'. Обычное выражение,
        // как значение по умолчанию у параметра, и вычисляется оно там же — при создании.
        Expr initial = cursor.match(TokenType.ASSIGN) ? parser.expression(0) : null;
        state.allowSelf(true, hasParent);

        if (cursor.match(TokenType.FATARROW)) {
            if (initial != null) {
                diagnostics.error(name.span(), "у свойства '" + name.text()
                        + "' есть скрытое поле, поэтому короткой формы '=>' ему мало: "
                        + "запишите 'def get()' и 'def set(value)' блоком");
            }
            Expr value = state.inFunctionBody(() -> parser.expression(0));
            Span span = keyword.span().to(value.span());
            add(members, new PropertyDecl(name.text(), name.span(), initial,
                    new PropertyDecl.Accessor(getterOf(value, name), value.span()), null,
                    PropertyStyle.ARROW, span), name);
            return;
        }
        if (!cursor.check(TokenType.LBRACE)) {
            diagnostics.error(cursor.peek().span(), "у свойства '" + name.text()
                    + "' нет тела: ожидалось '=> выражение' или блок с 'def get()', "
                    + "найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return;
        }
        accessors(members, keyword, name, initial, hasParent, isClass);
    }

    /** Блок аксессоров: {@code { def get() ... def set(value) ... }}. */
    private void accessors(Members members, Token keyword, Token name, Expr initial,
                           boolean hasParent, boolean isClass) {
        cursor.advance(); // {
        PropertyDecl.Accessor getter = null;
        PropertyDecl.Accessor setter = null;
        cursor.skipSeparators();
        while (!cursor.check(TokenType.RBRACE) && !cursor.check(TokenType.EOF)) {
            int before = cursor.position();
            Accessor parsed = accessor(name, hasParent, isClass);
            if (parsed != null) {
                boolean isGetter = GET.equals(parsed.kind());
                if (isGetter && getter != null || !isGetter && setter != null) {
                    diagnostics.error(parsed.accessor().span(), "'" + parsed.kind()
                            + "' у свойства '" + name.text() + "' уже объявлен");
                } else if (isGetter) {
                    getter = parsed.accessor();
                } else {
                    setter = parsed.accessor();
                }
            }
            cursor.ensureProgress(before);
            cursor.skipSeparators();
        }
        cursor.expect(TokenType.RBRACE, "закрывающую скобку '}'");
        Span span = keyword.span().to(cursor.lastSpan());

        if (getter == null) {
            // Свойство, которое нельзя прочитать, — приглашение к опечатке: 'obj.x = 1'
            // проходит, 'obj.x' молча даёт null. Записываемое без читаемого в языке
            // не заводится.
            diagnostics.error(name.span(), "у свойства '" + name.text() + "' нет 'def get()': "
                    + "свойство только для записи в языке не заводится");
            return;
        }
        add(members, new PropertyDecl(name.text(), name.span(), initial, getter, setter,
                PropertyStyle.BLOCK, span), name);
    }

    /** Разобранный аксессор вместе с тем, чем он оказался: {@code get} или {@code set}. */
    private record Accessor(String kind, PropertyDecl.Accessor accessor) {
    }

    /**
     * Один аксессор: {@code def get()}, {@code def set(value)} — с телом или без.
     * <p>
     * Без тела это требование трейта, ровно как у метода, и проверяется тем же
     * правилом: у класса тела нет — ошибка, у трейта — обязанность класса.
     */
    private Accessor accessor(Token property, boolean hasParent, boolean isClass) {
        Token start = cursor.peek();
        if (cursor.check(TokenType.SYNCHRONIZED)) {
            diagnostics.error(start.span(), "'synchronized' у аксессора не бывает: "
                    + "замок экземпляра берёт synchronized-метод");
            cursor.synchronize();
            return null;
        }
        if (!cursor.match(TokenType.DEF)) {
            diagnostics.error(start.span(), "в теле свойства '" + property.text()
                    + "' допустимы только 'def get()' и 'def set(value)', а здесь "
                    + describe(start));
            cursor.synchronize();
            return null;
        }
        if (!cursor.check(TokenType.WORD)
                || !GET.equals(cursor.peek().text()) && !SET.equals(cursor.peek().text())) {
            diagnostics.error(cursor.peek().span(), "у свойства '" + property.text()
                    + "' бывают только 'get' и 'set', найдено " + describe(cursor.peek()));
            cursor.synchronize();
            return null;
        }
        Token kind = cursor.advance();
        boolean isGetter = GET.equals(kind.text());

        state.allowSelf(true, hasParent);
        Params header = parameters("имени '" + kind.text() + "' свойства '"
                + property.text() + "'", true, true);
        checkAccessorParams(kind, isGetter, header, property);

        Stmt body = memberBody();
        if (body == null) {
            if (isClass) {
                diagnostics.error(kind.span(), "у '" + kind.text() + "' свойства '"
                        + property.text() + "' нет тела; требование без тела бывает только в трейте");
                return null;
            }
            return new Accessor(kind.text(),
                    new PropertyDecl.Accessor(null, start.span().to(kind.span())));
        }
        // Имя аксессора — 'Rect.area (get)': оно попадёт в кадр трассировки, и там
        // должно быть видно свойство, а не голое 'get' неизвестно от чего.
        String title = state.className() + "." + property.text() + " (" + kind.text() + ")";
        FunctionExpr function = new FunctionExpr(title, Set.of(), header.params(),
                null, null, body, bodyStyle(body), start.span().to(body.span()));
        return new Accessor(kind.text(),
                new PropertyDecl.Accessor(function, start.span().to(body.span())));
    }

    /**
     * Форма аксессора: {@code get} без параметров, {@code set} ровно с одним.
     * <p>
     * Ни значений по умолчанию, ни остатков: свойство читают и пишут обращением,
     * а у обращения аргументов нет — передавать их туда просто нечем.
     */
    private void checkAccessorParams(Token kind, boolean isGetter, Params header, Token property) {
        String where = "'" + kind.text() + "' свойства '" + property.text() + "'";
        if (header.rest() != null || header.namedRest() != null) {
            diagnostics.error(kind.span(), "у " + where + " не бывает остатка: "
                    + "свойство читают обращением, а у обращения аргументов нет");
            return;
        }
        int count = header.params().size();
        if (isGetter && count != 0) {
            diagnostics.error(kind.span(), "'get' свойства '" + property.text()
                    + "' не принимает параметров, а объявлено " + count);
            return;
        }
        if (!isGetter && count != 1) {
            diagnostics.error(kind.span(), "'set' свойства '" + property.text()
                    + "' принимает ровно один параметр — записываемое значение, "
                    + "а объявлено " + count);
            return;
        }
        if (!header.params().isEmpty() && header.params().get(0).hasDefault()) {
            diagnostics.error(kind.span(), "у параметра " + where
                    + " не бывает значения по умолчанию: он всегда приходит от записи");
        }
    }

    /** Короткая форма {@code => выражение} — тот же {@code def get()}, записанный одной строкой. */
    private FunctionExpr getterOf(Expr value, Token name) {
        String title = state.className() + "." + name.text() + " (get)";
        return new FunctionExpr(title, Set.of(), List.of(), null, null,
                new ReturnStmt(value, value.span()), BodyStyle.ARROW,
                name.span().to(value.span()));
    }

    private void add(Members members, PropertyDecl property, Token name) {
        if (members.taken(property.name())) {
            diagnostics.error(name.span(), duplicate(property.name()));
            return;
        }
        members.properties.add(property);
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

        /** Заголовок, которого не написали вовсе: {@code class Marker { ... }}. */
        static Params empty() {
            return new Params(List.of(), null, null);
        }
    }

    /**
     * Список параметров без остатков: {@code (a, b = 10)}.
     * <p>
     * Такой список остался только у заголовка трейта. Остаток там запрещён по существу,
     * а не из осторожности: заголовок трейта задаёт поля и требования к классу, а
     * конструктора, которому эти аргументы можно было бы перебросить, у трейта нет —
     * собирать остаток некому и незачем.
     */
    List<FunctionExpr.Param> parameters(String owner, boolean callSignature) {
        return parameters(owner, callSignature, false).params();
    }

    /**
     * Список параметров, возможно со значениями по умолчанию и остатками:
     * {@code (a, b = 10, *args, **named)}, а также с пропусками: {@code (_, event)}.
     * Запятая после последнего разрешена — как в массивах и аргументах.
     *
     * @param callSignature задаёт ли список число аргументов вызова или создания.
     *                      У заголовка трейта — нет: там параметр без значения это
     *                      не «обязательный аргумент», а требование к классу,
     *                      и порядок для него не значит ничего
     * @param allowRest     разрешены ли остаточные параметры. Разрешены везде, кроме
     *                      заголовка трейта: у него нет конструктора, а значит и того,
     *                      кому остаток можно было бы перебросить
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
            } else if (cursor.check(TokenType.HOLE)) {
                addHole(params, cursor.advance(), callSignature, rest, namedRest);
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
                    + " не разрешён: заголовок трейта задаёт поля, а конструктора,"
                    + " которому можно было бы перебросить остаток, у трейта нет");
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
            if (!existing.isHole() && existing.name().equals(name.text())) {
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
    /**
     * Параметр-дырка: {@code def onClick(_, event)}.
     * <p>
     * Позицию она занимает наравне с обычным параметром — иначе обработчик, чью
     * сигнатуру диктует не автор, дырку и не выразил бы, — а вот имени у неё нет,
     * и отсюда все три отличия от {@link #addParameter}: одноимённость не проверяется
     * (повторов дырок бывает сколько угодно), значение по умолчанию запрещено
     * (нечему умолчаться: значение всё равно выбрасывается), полем класса дырка
     * не становится ({@code resolve.ClassShape}).
     * <p>
     * В заголовке трейта дырка запрещена, и это не тот же запрет, что у остатка.
     * Там параметр — не позиция, а поле или требование к классу; дырка же и есть
     * «позиция без имени», то есть ровно то, чего в заголовке трейта не бывает.
     */
    private void addHole(List<FunctionExpr.Param> params, Token hole, boolean callSignature,
                         FunctionExpr.Rest rest, FunctionExpr.Rest namedRest) {
        if (cursor.match(TokenType.ASSIGN)) {
            // Выражение всё равно разбирается — по той же причине, что у остатка:
            // иначе список порвётся и к одной ошибке добавится вторая.
            parser.expression(0);
            diagnostics.error(hole.span(), "у пропуска '_' не может быть значения"
                    + " по умолчанию: значение дырки всё равно выбрасывается");
        }
        if (!callSignature) {
            diagnostics.error(hole.span(), "в заголовке трейта пропуск '_' не имеет смысла:"
                    + " параметр там — это поле или требование к классу, а у дырки нет имени");
            return;
        }
        if (rest != null || namedRest != null) {
            FunctionExpr.Rest after = rest != null ? rest : namedRest;
            String stars = rest != null ? "*" : "**";
            diagnostics.error(hole.span(), "пропуск '_' не может идти после остаточного"
                    + " параметра '" + stars + after.name() + "'");
            return;
        }
        if (!params.isEmpty() && params.get(params.size() - 1).hasDefault()) {
            diagnostics.error(hole.span(), "пропуск '_' без значения по умолчанию"
                    + " не может идти после параметра со значением по умолчанию");
        }
        params.add(FunctionExpr.Param.hole(hole.span()));
    }

    private void addParameter(List<FunctionExpr.Param> params, Token name, Expr defaultValue,
                              boolean callSignature, FunctionExpr.Rest rest,
                              FunctionExpr.Rest namedRest) {
        for (FunctionExpr.Param existing : params) {
            if (!existing.isHole() && existing.name().equals(name.text())) {
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
