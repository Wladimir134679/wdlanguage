package ru.wds.wdl.tools;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.stmt.*;
import ru.wds.wdl.ast.visitor.*;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Печать дерева с отступами — главный инструмент отладки грамматики.
 * <p>
 * Приоритеты операторов проверяются глазами именно так: если {@code 1 + 2 * 3}
 * напечаталось с умножением внутри сложения — таблица приоритетов верна.
 * <p>
 * Заодно это второй посетитель в проекте после интерпретатора, и написан он ровно
 * тем же способом: {@link ExprVisitor} с другим типом результата. Ни один узел
 * дерева ради него не менялся — в этом и смысл того, что диспетчеризация собрана
 * в одном месте, а узлы остаются данными.
 */
public final class AstDumper implements ExprVisitor<Void, Integer>, StmtVisitor<Void, Integer> {

    private final StringBuilder sb = new StringBuilder(256);
    private final boolean withSpans;

    private AstDumper(boolean withSpans) {
        this.withSpans = withSpans;
    }

    /** Дерево программы с указанием интервалов исходника у каждого узла. */
    public static String dump(Program program) {
        return dump(program, true);
    }

    public static String dump(Program program, boolean withSpans) {
        AstDumper dumper = new AstDumper(withSpans);
        dumper.line(0, "программа, инструкций: " + program.statements().size(), program.span());
        program.statements().forEach(statement -> dumper.visit(statement, 1));
        return dumper.sb.toString();
    }

    /** Дерево одного выражения — для REPL и тестов. */
    public static String dump(Expr expr) {
        return dump(expr, true);
    }

    public static String dump(Expr expr, boolean withSpans) {
        AstDumper dumper = new AstDumper(withSpans);
        dumper.visit(expr, 0);
        return dumper.sb.toString();
    }

    // --- инструкции ----------------------------------------------------------

    @Override
    public Void visitExprStmt(ExprStmt stmt, Integer depth) {
        return visit(stmt.expr(), depth);
    }

    @Override
    public Void visitAssign(AssignStmt stmt, Integer depth) {
        line(depth, "присваивание '" + stmt.op().symbol() + "'", stmt.span());
        visit(stmt.target(), depth + 1);
        return visit(stmt.value(), depth + 1);
    }

    /**
     * Распаковка: вид записи виден отдельной строкой, потому что {@code x, y = *v},
     * {@code x, y = **v} и {@code x, y = a, b} — три разных дерева при почти
     * одинаковом тексте.
     */
    @Override
    public Void visitUnpack(UnpackStmt stmt, Integer depth) {
        line(depth, "распаковка " + switch (stmt.style()) {
            case POSITIONAL -> "по позициям '*'";
            case NAMED -> "по именам '**'";
            case PAIRWISE -> "попарно";
        } + ", целей: " + stmt.targets().size(), stmt.span());
        stmt.targets().forEach(target -> line(depth + 1, "цель " + target, target.span()));
        stmt.sources().forEach(source -> visit(source.value(), depth + 1));
        return null;
    }

    @Override
    public Void visitBlock(BlockStmt stmt, Integer depth) {
        line(depth, "блок, инструкций: " + stmt.statements().size()
                + (stmt.hasDefer() ? ", с отложенным" : ""), stmt.span());
        stmt.statements().forEach(statement -> visit(statement, depth + 1));
        return null;
    }

    @Override
    public Void visitIf(IfStmt stmt, Integer depth) {
        line(depth, "ветвление 'if'", stmt.span());
        visit(stmt.condition(), depth + 1);
        line(depth + 1, "тогда", stmt.thenBranch().span());
        visit(stmt.thenBranch(), depth + 2);
        if (stmt.hasElse()) {
            line(depth + 1, "иначе", stmt.elseBranch().span());
            visit(stmt.elseBranch(), depth + 2);
        }
        return null;
    }

    @Override
    public Void visitWhile(WhileStmt stmt, Integer depth) {
        line(depth, "цикл 'while'", stmt.span());
        visit(stmt.condition(), depth + 1);
        return visit(stmt.body(), depth + 1);
    }

    @Override
    public Void visitFor(ForStmt stmt, Integer depth) {
        line(depth, "цикл 'for'", stmt.span());
        part(depth + 1, "начало", stmt.init());
        if (stmt.condition() != null) {
            line(depth + 1, "условие", stmt.condition().span());
            visit(stmt.condition(), depth + 2);
        } else {
            line(depth + 1, "условие: нет, цикл вечный", stmt.span());
        }
        part(depth + 1, "шаг", stmt.step());
        line(depth + 1, "тело", stmt.body().span());
        return visit(stmt.body(), depth + 2);
    }

    @Override
    public Void visitForEach(ForEachStmt stmt, Integer depth) {
        line(depth, "перебор 'for ... in', " + (stmt.withKey()
                ? "ключ " + stmt.key() + ", значение " + stmt.value()
                : "переменная " + stmt.value()), stmt.span());
        visit(stmt.iterable(), depth + 1);
        return visit(stmt.body(), depth + 1);
    }

    @Override
    public Void visitBreak(BreakStmt stmt, Integer depth) {
        return line(depth, "break", stmt.span());
    }

    @Override
    public Void visitContinue(ContinueStmt stmt, Integer depth) {
        return line(depth, "continue", stmt.span());
    }

    @Override
    public Void visitImport(ImportStmt stmt, Integer depth) {
        return line(depth, "импорт \"" + stmt.path() + "\""
                + (stmt.hasAlias() ? " как " + stmt.alias() : " развёрнутый"), stmt.span());
    }

    @Override
    public Void visitConstDecl(ConstDeclStmt stmt, Integer depth) {
        line(depth, "объявление константы " + stmt.name(), stmt.span());
        return visit(stmt.value(), depth + 1);
    }

    @Override
    public Void visitDefDecl(DefDeclStmt stmt, Integer depth) {
        line(depth, "объявление функции " + stmt.name(), stmt.span());
        return visit(stmt.function(), depth + 1);
    }

    /**
     * Класс печатается заголовком в одну строку, а его части — вложенными.
     * Порядок вложенного тот же, в каком собираются плоские таблицы: родитель,
     * трейты, своё, — чтобы по дампу можно было проверить, кто кого перекрывает.
     */
    @Override
    public Void visitExtend(ExtendStmt stmt, Integer depth) {
        line(depth, "расширение " + stmt.label(), stmt.span());
        stmt.methods().forEach(method -> visit(method, depth + 1));
        properties(stmt.properties(), depth + 1);
        return null;
    }

    @Override
    public Void visitClassDecl(ClassDeclStmt stmt, Integer depth) {
        line(depth, "объявление класса " + stmt.name()
                + "(" + header(stmt.params(), stmt.rest(), stmt.namedRest()) + ")", stmt.span());
        defaults(stmt.params(), depth + 1);
        if (stmt.hasParent()) {
            ClassDeclStmt.Superclass parent = stmt.parent();
            line(depth + 1, "родитель " + parent.title() + ", аргументов: "
                    + parent.arguments().size(), parent.span());
            arguments(parent.arguments(), depth + 2);
        }
        for (ClassDeclStmt.TraitRef trait : stmt.traits()) {
            line(depth + 1, "трейт " + trait.title(), trait.span());
        }
        if (stmt.hasConstructor()) {
            line(depth + 1, "конструктор", stmt.constructor().span());
            visit(stmt.constructor().body(), depth + 2);
        }
        stmt.methods().forEach(method -> visit(method, depth + 1));
        properties(stmt.properties(), depth + 1);
        for (ClassDeclStmt.Factory factory : stmt.factories()) {
            line(depth + 1, "фабрика " + factory.function().title(), factory.span());
            visit(factory.function(), depth + 2);
        }
        return null;
    }

    /**
     * Декораторы печатаются <b>в порядке записи</b>, сверху вниз, а применяются снизу
     * вверх. Дамп показывает текст, а не выполнение: перевёрнутый список сбивал бы
     * с толку того, кто сверяет дерево с исходником.
     */
    @Override
    public Void visitDecorated(DecoratedStmt stmt, Integer depth) {
        line(depth, "декорированное объявление, декораторов: " + stmt.decorators().size(),
                stmt.span());
        for (Decorator decorator : stmt.decorators()) {
            line(depth + 1, "декоратор", decorator.span());
            visit(decorator.callee(), depth + 2);
            arguments(decorator.arguments(), depth + 2);
        }
        visit(stmt.declaration(), depth + 1);
        return null;
    }

    @Override
    public Void visitTraitDecl(TraitDeclStmt stmt, Integer depth) {
        line(depth, "объявление трейта " + stmt.name() + "(" + header(stmt.params()) + ")", stmt.span());
        defaults(stmt.params(), depth + 1);
        for (FunctionExpr.Param param : stmt.params()) {
            if (!param.hasDefault()) {
                line(depth + 1, "требуется поле '" + param.name() + "'", param.span());
            }
        }
        stmt.methods().forEach(method -> visit(method, depth + 1));
        for (TraitDeclStmt.Requirement requirement : stmt.requirements()) {
            line(depth + 1, (requirement.mirror() ? "требуется зеркальный оператор `" : "требуется метод ")
                    + requirement.name() + (requirement.mirror() ? "`" : "")
                    + "(" + header(requirement.params()) + ")", requirement.span());
        }
        properties(stmt.properties(), depth + 1);
        return null;
    }

    /**
     * Свойства класса или трейта.
     * <p>
     * Аксессор без тела печатается как требование — тем же словом, что требуемый метод:
     * для читателя дампа это одно и то же обещание, только про чтение или запись.
     */
    private void properties(List<PropertyDecl> properties, int depth) {
        for (PropertyDecl property : properties) {
            line(depth, "свойство " + property.name()
                    + (property.hasBackingField() ? " со скрытым полем" : ""), property.span());
            if (property.hasBackingField()) {
                line(depth + 1, "начальное значение", property.initial().span());
                visit(property.initial(), depth + 2);
            }
            accessor("get", property.getter(), depth + 1);
            accessor("set", property.setter(), depth + 1);
        }
    }

    private void accessor(String kind, PropertyDecl.Accessor accessor, int depth) {
        if (accessor == null) {
            return;
        }
        if (accessor.isRequirement()) {
            line(depth, "требуется " + kind, accessor.span());
            return;
        }
        line(depth, kind, accessor.span());
        visit(accessor.function().body(), depth + 1);
    }

    private static String header(List<FunctionExpr.Param> params) {
        return header(params, null, null);
    }

    /** То же с остатками: {@code (a, b = ..., *args, **named)}. */
    private static String header(List<FunctionExpr.Param> params, FunctionExpr.Rest rest,
                                 FunctionExpr.Rest namedRest) {
        List<String> parts = new ArrayList<>(params.size() + 2);
        params.forEach(param -> parts.add(param.hasDefault() ? param.name() + " = ..." : param.name()));
        if (rest != null) {
            parts.add("*" + rest.name());
        }
        if (namedRest != null) {
            parts.add("**" + namedRest.name());
        }
        return String.join(", ", parts);
    }

    /** Значения по умолчанию идут отдельными поддеревьями: это выражения, и их форма важна. */
    private void defaults(List<FunctionExpr.Param> params, int depth) {
        for (FunctionExpr.Param param : params) {
            if (param.hasDefault()) {
                line(depth, "по умолчанию '" + param.name() + "'", param.span());
                visit(param.defaultValue(), depth + 1);
            }
        }
    }

    @Override
    public Void visitReturn(ReturnStmt stmt, Integer depth) {
        line(depth, stmt.hasValue() ? "return" : "return без значения", stmt.span());
        return stmt.hasValue() ? visit(stmt.value(), depth + 1) : null;
    }

    @Override
    public Void visitYield(YieldStmt stmt, Integer depth) {
        line(depth, "yield", stmt.span());
        return visit(stmt.value(), depth + 1);
    }

    @Override
    public Void visitThrow(ThrowStmt stmt, Integer depth) {
        line(depth, "throw", stmt.span());
        return visit(stmt.error(), depth + 1);
    }

    /**
     * Порядок вложенного тот же, что при выполнении: тело, обработчики сверху вниз,
     * {@code finally}. Типы обработчика печатаются в его заголовке — по ним и видно,
     * который сработает первым.
     */
    @Override
    public Void visitTry(TryStmt stmt, Integer depth) {
        line(depth, "try, обработчиков: " + stmt.handlers().size(), stmt.span());
        line(depth + 1, "тело", stmt.body().span());
        visit(stmt.body(), depth + 2);
        for (TryStmt.Catch handler : stmt.handlers()) {
            line(depth + 1, "catch " + handler.name() + (handler.catchesEverything()
                    ? " (любая ошибка)"
                    : " is " + handler.types().stream()
                            .map(TryStmt.TypeRef::title)
                            .collect(Collectors.joining(", "))), handler.span());
            visit(handler.body(), depth + 2);
        }
        if (stmt.hasFinally()) {
            line(depth + 1, "finally", stmt.finallyBlock().span());
            visit(stmt.finallyBlock(), depth + 2);
        }
        return null;
    }

    @Override
    public Void visitDefer(DeferStmt stmt, Integer depth) {
        line(depth, "defer", stmt.span());
        return visit(stmt.body(), depth + 1);
    }

    /** Ресурсы печатаются в порядке захвата — закрываются они в обратном. */
    @Override
    public Void visitUse(UseStmt stmt, Integer depth) {
        line(depth, "use, ресурсов: " + stmt.resources().size(), stmt.span());
        for (UseStmt.Binding resource : stmt.resources()) {
            line(depth + 1, "ресурс " + resource.name(), resource.span());
            visit(resource.value(), depth + 2);
        }
        line(depth + 1, "тело", stmt.body().span());
        return visit(stmt.body(), depth + 2);
    }

    @Override
    public Void visitErrorStmt(ErrorStmt stmt, Integer depth) {
        return line(depth, "<неразобранная инструкция>", stmt.span());
    }

    /** Необязательная часть цикла {@code for}: печатается только если она есть. */
    private void part(int depth, String title, Stmt stmt) {
        if (stmt == null) {
            return;
        }
        line(depth, title, stmt.span());
        visit(stmt, depth + 1);
    }

    // --- выражения -----------------------------------------------------------

    @Override
    public Void visitLiteral(LiteralExpr expr, Integer depth) {
        return line(depth, "литерал " + expr.value(), expr);
    }

    @Override
    public Void visitVariable(VariableExpr expr, Integer depth) {
        return line(depth, "имя " + expr.name(), expr);
    }

    @Override
    public Void visitUnary(UnaryExpr expr, Integer depth) {
        line(depth, "унарная '" + expr.op().symbol() + "'", expr);
        return visit(expr.operand(), depth + 1);
    }

    @Override
    public Void visitBinary(BinaryExpr expr, Integer depth) {
        line(depth, "бинарная '" + expr.op().symbol() + "'", expr);
        visit(expr.left(), depth + 1);
        return visit(expr.right(), depth + 1);
    }

    @Override
    public Void visitTernary(TernaryExpr expr, Integer depth) {
        line(depth, "условное '?:'", expr);
        visit(expr.condition(), depth + 1);
        visit(expr.ifTrue(), depth + 1);
        return visit(expr.ifFalse(), depth + 1);
    }

    /**
     * Ветвление по предмету. Форма ветки видна по тому, какое тело у неё заполнено:
     * «значение» — стрелка, «блок» — фигурные скобки.
     */
    @Override
    public Void visitMatch(MatchExpr expr, Integer depth) {
        line(depth, expr.asValue() ? "ветвление 'match' (значение)" : "ветвление 'match'", expr);
        line(depth + 1, "предмет", expr.subject().span());
        visit(expr.subject(), depth + 2);
        for (MatchCase branch : expr.cases()) {
            matchCase(branch, "ветка 'case'", depth + 1);
        }
        if (expr.hasOtherwise()) {
            matchCase(expr.otherwise(), "ветка 'else'", depth + 1);
        }
        return null;
    }

    private void matchCase(MatchCase branch, String title, int depth) {
        line(depth, title, branch.span());
        for (CaseTail tail : branch.tails()) {
            line(depth + 1, "образец '" + tail.op().symbol() + "'", tail.span());
            visit(tail.right(), depth + 2);
        }
        if (branch.hasGuard()) {
            line(depth + 1, "условие 'if'", branch.guard().span());
            visit(branch.guard(), depth + 2);
        }
        if (branch.isValue()) {
            line(depth + 1, "значение '=>'", branch.value().span());
            visit(branch.value(), depth + 2);
        } else {
            // Отдаёт ли блок значение, видно по 'yield' внутри, а не по самой ветке:
            // он бывает под условием.
            line(depth + 1, "тело", branch.body().span());
            visit(branch.body(), depth + 2);
        }
    }

    @Override
    public Void visitAccess(AccessExpr expr, Integer depth) {
        String style = expr.style() == AccessStyle.DOT ? "точка" : "скобки";
        line(depth, "обращение (" + style + ")", expr);
        visit(expr.target(), depth + 1);
        return visit(expr.key(), depth + 1);
    }

    /**
     * Аргументы вызова, создания и заголовка родителя — одинаково во всех трёх местах.
     * <p>
     * У именованного аргумента печатается строка с именем, а выражение уходит на уровень
     * ниже: имя — часть записи вызова, а не часть выражения, и в дампе это должно быть
     * видно так же, как в исходнике.
     */
    private void arguments(List<Argument> arguments, int depth) {
        for (Argument argument : arguments) {
            switch (argument.kind()) {
                case POSITIONAL -> visit(argument.value(), depth);
                case NAMED -> {
                    line(depth, "аргумент '" + argument.name() + "'", argument.span());
                    visit(argument.value(), depth + 1);
                }
                case SPREAD -> {
                    line(depth, "раскрытие массива '*'", argument.span());
                    visit(argument.value(), depth + 1);
                }
                case NAMED_SPREAD -> {
                    line(depth, "раскрытие объекта '**'", argument.span());
                    visit(argument.value(), depth + 1);
                }
            }
        }
    }

    @Override
    public Void visitCall(CallExpr expr, Integer depth) {
        line(depth, "вызов, аргументов: " + expr.arguments().size(), expr);
        visit(expr.callee(), depth + 1);
        arguments(expr.arguments(), depth + 1);
        return null;
    }

    @Override
    public Void visitNew(NewExpr expr, Integer depth) {
        line(depth, "создание, аргументов: " + expr.arguments().size(), expr);
        visit(expr.callee(), depth + 1);
        arguments(expr.arguments(), depth + 1);
        return null;
    }

    @Override
    public Void visitArray(ArrayExpr expr, Integer depth) {
        line(depth, "массив, элементов: " + expr.elements().size(), expr);
        expr.elements().forEach(element -> visit(element, depth + 1));
        return null;
    }

    @Override
    public Void visitObject(ObjectExpr expr, Integer depth) {
        line(depth, "объект, пар: " + expr.entries().size(), expr);
        for (ObjectExpr.Entry entry : expr.entries()) {
            visit(entry.key(), depth + 1);
            visit(entry.value(), depth + 2);
        }
        return null;
    }

    /**
     * Функция печатается со списком параметров в одну строку, а тело — вложенным:
     * так видно и то, что относится к самой функции, и форму её тела.
     * <p>
     * Значение по умолчанию в заголовке помечается многоточием, а само идёт отдельным
     * поддеревом: это выражение, и его форма — приоритеты операторов, вложенные вызовы —
     * ровно то, ради чего дамп и смотрят.
     */
    @Override
    public Void visitFunction(FunctionExpr expr, Integer depth) {
        String arrow = expr.style() == BodyStyle.ARROW ? ", тело-выражение '=>'" : "";
        // Модификатор идёт в ту же строку, что имя и параметры: он относится к самой
        // функции, а не к её телу, — и в дампе это должно быть видно сразу.
        String modifiers = expr.modifiers().stream()
                .map(Modifier::text)
                .collect(Collectors.joining(" ", "", " "));
        line(depth, "функция " + modifiers.stripLeading() + expr.writtenName()
                + "(" + header(expr.params(), expr.rest(), expr.namedRest()) + ")" + arrow, expr);
        defaults(expr.params(), depth + 1);
        return visit(expr.body(), depth + 1);
    }

    @Override
    public Void visitTryExpr(TryExpr expr, Integer depth) {
        line(depth, "короткая форма '" + expr.style().text() + "'", expr);
        return visit(expr.inner(), depth + 1);
    }

    @Override
    public Void visitError(ErrorExpr expr, Integer depth) {
        return line(depth, "<неразобранное выражение>", expr);
    }

    private Void line(int depth, String text, Expr expr) {
        return line(depth, text, expr.span());
    }

    private Void line(int depth, String text, Span span) {
        sb.append("  ".repeat(depth)).append(text);
        if (withSpans) {
            sb.append("  [").append(span).append(']');
        }
        sb.append('\n');
        return null;
    }
}
