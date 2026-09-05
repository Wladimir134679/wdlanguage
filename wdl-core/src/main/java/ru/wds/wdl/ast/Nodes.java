package ru.wds.wdl.ast;

import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.Annotations;
import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.ast.expr.ArrayExpr;
import ru.wds.wdl.ast.expr.BinaryExpr;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.CaseTail;
import ru.wds.wdl.ast.expr.Decorator;
import ru.wds.wdl.ast.expr.ErrorExpr;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.LiteralExpr;
import ru.wds.wdl.ast.expr.MatchCase;
import ru.wds.wdl.ast.expr.MatchExpr;
import ru.wds.wdl.ast.expr.NewExpr;
import ru.wds.wdl.ast.expr.ObjectExpr;
import ru.wds.wdl.ast.expr.TernaryExpr;
import ru.wds.wdl.ast.expr.TryExpr;
import ru.wds.wdl.ast.expr.UnaryExpr;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.BreakStmt;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.ConstDeclStmt;
import ru.wds.wdl.ast.stmt.ContinueStmt;
import ru.wds.wdl.ast.stmt.DecoratedStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
import ru.wds.wdl.ast.stmt.DeferStmt;
import ru.wds.wdl.ast.stmt.ErrorStmt;
import ru.wds.wdl.ast.stmt.ExprStmt;
import ru.wds.wdl.ast.stmt.ExtendStmt;
import ru.wds.wdl.ast.stmt.ForEachStmt;
import ru.wds.wdl.ast.stmt.ForStmt;
import ru.wds.wdl.ast.stmt.IfStmt;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.ast.stmt.ReturnStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.ThrowStmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.ast.stmt.TryStmt;
import ru.wds.wdl.ast.stmt.UnpackStmt;
import ru.wds.wdl.ast.stmt.UnpackTarget;
import ru.wds.wdl.ast.stmt.UseStmt;
import ru.wds.wdl.ast.stmt.WhileStmt;
import ru.wds.wdl.ast.stmt.YieldStmt;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Обход дерева: состав детей узла, проход сверху вниз и поиск по смещению.
 * <p>
 * <b>Одно место на весь проект, знающее, из чего состоит каждый узел.</b> До этого
 * обход писали заново все, кому он был нужен: свой был у дампера, свой —
 * у подсказки про необъявленное имя, и второй намеренно неполный. Инструментам
 * редактора обход нужен постоянно — «узел под курсором», «путь до корня», «все
 * употребления имени», «структура файла», «свёртка», — и шесть копий разошлись бы
 * на первом же новом виде узла. Здесь копия одна, а полноту разбора проверяет
 * компилятор: иерархии {@code sealed}, и {@code switch} без {@code default}
 * перестанет компилироваться, стоит завести новый вид.
 * <p>
 * <b>Дети идут слева направо, по началу интервала.</b> Порядок полей узла и порядок
 * записи в тексте — не одно и то же: тело класса лежит четырьмя списками (конструктор,
 * методы, фабрики, свойства), а написано вперемешку. Поэтому список детей
 * упорядочивается по интервалу; узлы без места ({@link Span#NONE}) уходят в конец,
 * чтобы не мешать поиску по смещению.
 * <p>
 * Дерево неизменяемо и о выполнении ничего не знает, поэтому всё здесь — чистые
 * функции без состояния, годные для любого потока.
 */
public final class Nodes {

    /** Узел без места — последний в порядке: искать по смещению его всё равно нельзя. */
    private static final Comparator<Node> BY_POSITION =
            Comparator.comparingInt(node -> node.span().isNone() ? Integer.MAX_VALUE : node.span().start());

    private Nodes() {
    }

    /**
     * Прямые дети узла в порядке записи в исходнике.
     * <p>
     * Ребёнком считается всё, у чего есть своё место в тексте: выражения, инструкции
     * и {@linkplain Fragment фрагменты}. Того, чего в тексте нет, — вида операции,
     * стиля записи, вычисленного значения литерала — здесь нет.
     */
    public static List<Node> children(Node node) {
        Objects.requireNonNull(node, "node");
        Children children = new Children();
        switch (node) {
            case Program program -> children.add(program.statements());
            case Expr expr -> collect(expr, children);
            case Stmt statement -> collect(statement, children);
            case Fragment fragment -> collect(fragment, children);
        }
        return children.sorted();
    }

    /**
     * Обходит поддерево сверху вниз, слева направо: сперва сам узел, потом дети.
     * <p>
     * Порядок именно такой, потому что в этом порядке читают текст: структура файла,
     * свёртка и подсветка объявлений строятся одним проходом без разворота.
     */
    public static void walk(Node root, Consumer<Node> visitor) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(visitor, "visitor");
        visitor.accept(root);
        for (Node child : children(root)) {
            walk(child, visitor);
        }
    }

    /**
     * Самый глубокий узел, чей интервал накрывает смещение, или {@code null},
     * если смещение вне поддерева.
     * <p>
     * Границы полуоткрыты: узел {@code [start, end)} курсор в позиции {@code end}
     * уже не накрывает — иначе два соседних узла отвечали бы на один и тот же вопрос.
     * Исключение — пустые узлы, которые парсер ставит на месте недописанного
     * ({@code obj.} и подобное): у них {@code start == end}, и накрывают они ровно
     * эту точку. Без исключения автодополнение не работало бы там, где нужнее всего.
     */
    public static Node deepestAt(Node root, int offset) {
        List<Node> path = pathAt(root, offset);
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    /**
     * Путь от корня до самого глубокого узла под смещением; пустой список, если
     * смещение вне поддерева.
     * <p>
     * Это и есть ответ на вопрос «где я нахожусь»: имя параметра внутри функции
     * внутри метода класса. Каждый следующий элемент — ребёнок предыдущего.
     */
    public static List<Node> pathAt(Node root, int offset) {
        Objects.requireNonNull(root, "root");
        List<Node> path = new ArrayList<>();
        Node current = root;
        if (!covers(current, offset)) {
            return List.of();
        }
        while (true) {
            path.add(current);
            Node next = null;
            for (Node child : children(current)) {
                if (covers(child, offset)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return List.copyOf(path);
            }
            current = next;
        }
    }

    /**
     * Путь до узла, который редактирует курсор в этой позиции.
     * <p>
     * Отличается от {@link #pathAt} тем, что курсор стоит <b>между</b> символами:
     * позиция сразу за концом узла — это всё ещё он. Спуск предпочитает узел,
     * который смещение накрывает; когда такого нет, берётся тот, что в этой позиции
     * заканчивается.
     * <p>
     * Без этого правила дополнение не работало бы в самом частом месте: у {@code obj.}
     * пустой узел-ошибка стоит ровно на границе своего родителя, а строгое правило
     * «конец не входит» до него не доводит — путь обрывался бы на блоке.
     */
    public static List<Node> pathAtCaret(Node root, int offset) {
        Objects.requireNonNull(root, "root");
        Span span = root.span();
        if (span.isNone() || offset < span.start() || offset > span.end()) {
            return List.of();
        }
        List<Node> path = new ArrayList<>();
        Node current = root;
        while (true) {
            path.add(current);
            Node next = null;
            for (Node child : children(current)) {
                if (covers(child, offset)) {
                    next = child;
                    break;
                }
                if (!child.span().isNone() && child.span().end() == offset) {
                    // Не прерываем перебор: узел, накрывающий смещение, важнее того,
                    // который в нём кончается, а стоять он может и правее.
                    next = child;
                }
            }
            if (next == null) {
                return List.copyOf(path);
            }
            current = next;
        }
    }

    /** Самый глубокий узел, который редактирует курсор, или {@code null}. */
    public static Node deepestAtCaret(Node root, int offset) {
        List<Node> path = pathAtCaret(root, offset);
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }

    /**
     * Объявление, имя которого стоит под смещением, или {@code null}.
     * <p>
     * Отвечает на «курсор в имени — в каком именно объявлении», и это разные вещи:
     * в {@code def total(price)} смещение внутри {@code total} даёт функцию,
     * а внутри {@code price} — параметр, хотя оба лежат в одном узле функции.
     */
    public static Node declarationNameAt(Node root, int offset) {
        for (Node node : pathAt(root, offset).reversed()) {
            Span name = nameSpanOf(node);
            if (name != null && covers(name, offset)) {
                return node;
            }
        }
        return null;
    }

    /**
     * Место имени этого объявления или {@code null}, если узел объявлением не является.
     * <p>
     * Одна таблица на все виды имён — то, чем пользуются и переход к объявлению,
     * и переименование, и подсветка употреблений.
     */
    public static Span nameSpanOf(Node node) {
        Objects.requireNonNull(node, "node");
        Span span = switch (node) {
            case FunctionExpr function -> function.nameSpan();
            case ConstDeclStmt constant -> constant.nameSpan();
            case ClassDeclStmt type -> type.nameSpan();
            case TraitDeclStmt type -> type.nameSpan();
            case ImportStmt module -> module.aliasSpan().isNone() ? module.pathSpan() : module.aliasSpan();
            case DefDeclStmt declaration -> declaration.function().nameSpan();
            case PropertyDecl property -> property.nameSpan();
            case TraitDeclStmt.Requirement requirement -> requirement.nameSpan();
            case FunctionExpr.Param param -> param.nameSpan();
            case FunctionExpr.Rest rest -> rest.span();
            case UseStmt.Binding binding -> binding.nameSpan();
            case TryStmt.Catch handler -> handler.nameSpan();
            default -> null;
        };
        return span == null || span.isNone() ? null : span;
    }

    /** Аннотации объявления, какой бы инструкцией оно ни было. */
    private static Annotations annotationsOf(Stmt declaration) {
        return switch (declaration) {
            case DefDeclStmt def -> def.function().annotations();
            case ClassDeclStmt type -> type.annotations();
            case TraitDeclStmt type -> type.annotations();
            default -> Annotations.NONE;
        };
    }

    private static boolean contains(Span outer, Span inner) {
        return !outer.isNone() && !inner.isNone()
                && outer.start() <= inner.start() && inner.end() <= outer.end();
    }

    /** Накрывает ли узел смещение; пустой узел накрывает ровно свою точку. */
    private static boolean covers(Node node, int offset) {
        return covers(node.span(), offset);
    }

    private static boolean covers(Span span, int offset) {
        if (span.isNone()) {
            return false;
        }
        return span.length() == 0
                ? offset == span.start()
                : offset >= span.start() && offset < span.end();
    }

    // --- состав детей --------------------------------------------------------

    private static void collect(Expr expr, Children children) {
        switch (expr) {
            case LiteralExpr ignored -> { }
            case VariableExpr ignored -> { }
            case ErrorExpr ignored -> { }
            case UnaryExpr unary -> children.add(unary.operand());
            case BinaryExpr binary -> children.add(binary.left()).add(binary.right());
            case TernaryExpr ternary -> children.add(ternary.condition())
                    .add(ternary.ifTrue()).add(ternary.ifFalse());
            case MatchExpr match -> children.add(match.subject())
                    .add(match.cases()).add(match.otherwise());
            case AccessExpr access -> children.add(access.target()).add(access.key());
            case CallExpr call -> children.add(call.callee()).add(call.arguments());
            case NewExpr creation -> children.add(creation.callee()).add(creation.arguments());
            case ArrayExpr array -> children.add(array.elements());
            case ObjectExpr object -> children.add(object.entries());
            case TryExpr attempt -> children.add(attempt.inner());
            case FunctionExpr function -> children.add(function.annotations(), function.span())
                    .add(function.params()).add(function.rest()).add(function.namedRest())
                    .add(function.body());
        }
    }

    private static void collect(Stmt statement, Children children) {
        switch (statement) {
            case BreakStmt ignored -> { }
            case ContinueStmt ignored -> { }
            case ErrorStmt ignored -> { }
            case ImportStmt ignored -> { }
            case ExprStmt expression -> children.add(expression.expr());
            case AssignStmt assignment -> children.add(assignment.target()).add(assignment.value());
            case UnpackStmt unpack -> children.add(unpack.targets()).add(unpack.sources());
            case BlockStmt block -> children.add(block.statements());
            case IfStmt conditional -> children.add(conditional.condition())
                    .add(conditional.thenBranch()).add(conditional.elseBranch());
            case WhileStmt loop -> children.add(loop.condition()).add(loop.body());
            case ForStmt loop -> children.add(loop.init()).add(loop.condition())
                    .add(loop.step()).add(loop.body());
            case ForEachStmt loop -> children.add(loop.names()).add(loop.iterable()).add(loop.body());
            case ConstDeclStmt constant -> children.add(constant.value());
            case DefDeclStmt declaration -> children.add(declaration.function());
            case ReturnStmt returned -> children.add(returned.value());
            case YieldStmt yielded -> children.add(yielded.value());
            case ThrowStmt thrown -> children.add(thrown.error());
            case DeferStmt deferred -> children.add(deferred.body());
            case UseStmt use -> children.add(use.resources()).add(use.body());
            case TryStmt attempt -> children.add(attempt.body())
                    .add(attempt.handlers()).add(attempt.finallyBlock());
            case DecoratedStmt decorated -> children.add(decorated.decorators())
                    .addOutside(annotationsOf(decorated.declaration()), decorated.declaration().span())
                    .add(decorated.declaration());
            case ExtendStmt extension -> children.add(extension.target())
                    .add(extension.methods()).add(extension.properties());
            case ClassDeclStmt type -> children.add(type.annotations(), type.span())
                    .add(type.params()).add(type.rest()).add(type.namedRest())
                    .add(type.parent()).add(type.traits()).add(type.constructor())
                    .add(type.methods()).add(type.factories()).add(type.properties());
            case TraitDeclStmt type -> children.add(type.annotations(), type.span())
                    .add(type.params()).add(type.methods())
                    .add(type.requirements()).add(type.properties());
        }
    }

    private static void collect(Fragment fragment, Children children) {
        switch (fragment) {
            case FunctionExpr.Rest ignored -> { }
            case TryStmt.TypeRef ignored -> { }
            case FunctionExpr.Param param -> children.add(param.annotations(), param.span()).add(param.defaultValue());
            case Argument argument -> children.add(argument.value());
            case ArrayExpr.Element element -> children.add(element.value());
            case ObjectExpr.Entry entry -> children.add(entry.key()).add(entry.value());
            case Decorator decorator -> children.add(decorator.callee()).add(decorator.arguments());
            case MatchCase branch -> children.add(branch.tails()).add(branch.guard())
                    .add(branch.value()).add(branch.body());
            case CaseTail tail -> children.add(tail.right());
            case ClassDeclStmt.Superclass parent -> children.add(parent.type()).add(parent.arguments());
            case ClassDeclStmt.TraitRef trait -> children.add(trait.type());
            case ClassDeclStmt.Factory factory -> children.add(factory.function());
            case TraitDeclStmt.Requirement requirement -> children.add(requirement.annotations(), requirement.span())
                    .add(requirement.params());
            case PropertyDecl property -> children.add(property.annotations(), property.span())
                    .add(property.initial()).add(property.getter()).add(property.setter());
            case PropertyDecl.Accessor accessor -> children.add(accessor.function());
            case TryStmt.Catch handler -> children.add(handler.types()).add(handler.body());
            case UseStmt.Binding binding -> children.add(binding.value());
            case UnpackTarget target -> children.add(target.target());
        }
    }

    /**
     * Сборщик списка детей: {@code null} и ненаписанные аннотации молча пропускает.
     * <p>
     * Проверка на {@code null} здесь одна на всех — иначе она стояла бы в каждой из
     * полусотни веток: необязательных полей в дереве много ({@code else}, тело цикла
     * {@code for}, значение по умолчанию, {@code finally}), и это нормальная форма
     * записи, а не ошибка.
     */
    private static final class Children {

        private final List<Node> items = new ArrayList<>(4);

        Children add(Node node) {
            if (node != null) {
                items.add(node);
            }
            return this;
        }

        Children add(Collection<? extends Node> nodes) {
            if (nodes != null) {
                for (Node node : nodes) {
                    add(node);
                }
            }
            return this;
        }

        /**
          * Записи аннотаций — обычные дети: {@code @{route: "/users"}} стоит в тексте.
          * <p>
          * С оговоркой про владение: блок, записанный <b>выше декоратора</b>, в интервал
          * объявления не входит (иначе тот перепрыгнул бы через декоратор и наехал
          * на него), и отдаёт такие записи {@code DecoratedStmt}. Поэтому владелец
          * берёт только то, что лежит внутри него.
          */
         Children add(Annotations annotations, Span owner) {
             if (annotations == null) {
                 return this;
             }
             for (Node entry : annotations.entries()) {
                 if (contains(owner, entry.span())) {
                     add(entry);
                 }
             }
             return this;
         }

         /** Обратная половина того же правила: то, что осталось снаружи владельца. */
         Children addOutside(Annotations annotations, Span owner) {
             if (annotations == null) {
                 return this;
             }
             for (Node entry : annotations.entries()) {
                 if (!contains(owner, entry.span())) {
                     add(entry);
                 }
             }
             return this;
         }

        List<Node> sorted() {
            items.sort(BY_POSITION);
            return List.copyOf(items);
        }
    }
}
