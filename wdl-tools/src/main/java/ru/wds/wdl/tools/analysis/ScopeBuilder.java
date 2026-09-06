package ru.wds.wdl.tools.analysis;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.ConstDeclStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
import ru.wds.wdl.ast.stmt.ExtendStmt;
import ru.wds.wdl.ast.stmt.ForEachStmt;
import ru.wds.wdl.ast.stmt.ForStmt;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.ast.stmt.TryStmt;
import ru.wds.wdl.ast.stmt.UnpackStmt;
import ru.wds.wdl.ast.stmt.UnpackTarget;
import ru.wds.wdl.ast.stmt.UseStmt;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Один обход дерева, из которого получается дерево областей и список символов.
 * <p>
 * <b>Правила заведения имени сверены с {@code Interpreter}, а не восстановлены
 * по памяти.</b> Каждой ветке здесь соответствует место, где выполнение кладёт имя
 * в область: {@code visitDef}, {@code visitConst}, {@code visitUse}, связывание
 * параметров в {@code UserFunction}, {@code define} у перебора. Разойдись эти два
 * списка — редактор начал бы предлагать имена, которых в запуске нет, или прятать
 * те, что есть.
 * <p>
 * Одноразовый: экземпляр на файл.
 */
final class ScopeBuilder {

    /** Имя скрытого поля: существует только внутри аксессоров свойства с полем. */
    private static final String FIELD = "field";

    private final List<Symbol> symbols = new ArrayList<>();
    private final List<Reference> references = new ArrayList<>();
    private final List<Assigned> assignments = new ArrayList<>();
    private LexicalScope root;

    private ScopeBuilder() {
    }

    static Result build(Program program) {
        ScopeBuilder builder = new ScopeBuilder();
        builder.root = new LexicalScope(ScopeKind.FILE, program.span(), program, null);
        builder.visitAll(program.statements(), builder.root);
        builder.resolveAssignments();
        builder.root.seal();
        builder.symbols.sort(Comparator.comparingInt(symbol -> symbol.nameSpan().start()));
        return new Result(builder.root, List.copyOf(builder.symbols), List.copyOf(builder.references));
    }

    /**
     * Присваивание, про которое ещё не решено, заводит оно имя или пишет в готовое.
     * <p>
     * Отложены они все до конца обхода, и по двум причинам сразу. Первая: решение
     * зависит от объявлений, которые могут стоять ниже, — {@code def f()} видит
     * переменную, заведённую под ним, потому что вызывается позже. Вторая: порядок
     * решения обязан повторять порядок выполнения, а он не текстовый — верхний уровень
     * отрабатывает целиком раньше, чем тело любой функции.
     *
     * @param name  имя цели
     * @param place место имени
     * @param owner инструкция, которой имя заведено
     * @param scope область, где имя окажется, если оно новое
     * @param kind  чем оно будет: обычной переменной или переменной распаковки
     */
    private record Assigned(String name, Span place, Stmt owner, LexicalScope scope,
                            SymbolKind kind) {

        /** Под границей вызова: такие решаются после всего, что выполняется сразу. */
        boolean deferred() {
            return scope.callBoundary() != null;
        }
    }

    private void resolveAssignments() {
        assignments.sort(Comparator.comparing(Assigned::deferred)
                .thenComparingInt(assigned -> assigned.place().start()));
        for (Assigned assigned : assignments) {
            if (Visibility.resolve(assigned.name(), assigned.scope(), assigned.place().start()) != null) {
                continue;
            }
            declare(new Symbol(assigned.name(), assigned.kind(), assigned.place(),
                    assigned.owner().span(), assigned.owner()), assigned.scope());
        }
    }

    /** Что получилось: дерево областей, все символы и все употребления имён. */
    record Result(LexicalScope root, List<Symbol> symbols, List<Reference> references) {
    }

    // --- обход ---------------------------------------------------------------

    private void visitAll(List<? extends Node> nodes, LexicalScope scope) {
        for (Node node : nodes) {
            visit(node, scope);
        }
    }

    private void visit(Node node, LexicalScope scope) {
        switch (node) {
            case BlockStmt block -> visitAll(block.statements(),
                    child(ScopeKind.BLOCK, block, scope));

            // Счётчик живёт в области цикла: 'for (i = 0; ...)' не оставляет i снаружи.
            case ForStmt loop -> {
                LexicalScope inner = child(ScopeKind.LOOP, loop, scope);
                visitIfPresent(loop.init(), inner);
                visitIfPresent(loop.condition(), inner);
                visitIfPresent(loop.step(), inner);
                visitIfPresent(loop.body(), inner);
            }

            // Перебираемое считается снаружи, имена прохода живут внутри.
            case ForEachStmt loop -> {
                visitIfPresent(loop.iterable(), scope);
                LexicalScope step = child(ScopeKind.ITERATION, loop, scope);
                for (UnpackTarget name : loop.names()) {
                    declareTarget(name, SymbolKind.LOOP_VARIABLE, loop, step);
                }
                visitIfPresent(loop.body(), step);
            }

            // Захват ресурса виден следующему захвату: значения считаются уже внутри.
            case UseStmt use -> {
                LexicalScope inner = child(ScopeKind.USE, use, scope);
                for (UseStmt.Binding binding : use.resources()) {
                    visitIfPresent(binding.value(), inner);
                    declare(new Symbol(binding.name(), SymbolKind.RESOURCE, binding.nameSpan(),
                            binding.span(), binding), inner);
                }
                visitIfPresent(use.body(), inner);
            }

            case TryStmt attempt -> {
                visitIfPresent(attempt.body(), scope);
                for (TryStmt.Catch handler : attempt.handlers()) {
                    LexicalScope inner = child(ScopeKind.CATCH, handler, scope);
                    declare(new Symbol(handler.name(), SymbolKind.CATCH_VARIABLE,
                            handler.nameSpan(), handler.span(), handler), inner);
                    visitIfPresent(handler.body(), inner);
                }
                visitIfPresent(attempt.finallyBlock(), scope);
            }

            case DefDeclStmt declaration -> {
                declare(new Symbol(declaration.function().name(), SymbolKind.FUNCTION,
                        declaration.function().nameSpan(), declaration.span(),
                        declaration.function()), scope);
                visit(declaration.function(), scope);
            }

            case ConstDeclStmt constant -> {
                declare(new Symbol(constant.name(), SymbolKind.CONSTANT, constant.nameSpan(),
                        constant.span(), constant), scope);
                visitIfPresent(constant.value(), scope);
            }

            // Имя заводит только 'import ... as': развёрнутый импорт кладёт в область
            // имена самого модуля, а какие они — знает лишь тот файл, который здесь
            // не разбирается. Про это врать нельзя, поэтому имён отсюда не берём.
            case ImportStmt module -> {
                if (module.hasAlias()) {
                    declare(new Symbol(module.alias(), SymbolKind.MODULE, module.aliasSpan(),
                            module.span(), module), scope);
                }
            }

            case ClassDeclStmt type -> {
                declare(new Symbol(type.name(), SymbolKind.CLASS, type.nameSpan(),
                        type.span(), type), scope);
                LexicalScope body = child(ScopeKind.CLASS_BODY, type, scope);
                declareParams(type.params(), type.rest(), type.namedRest(), body);
                visitAll(type.params(), body);
                visitIfPresent(type.parent() == null ? null : type.parent().type(), scope);
                if (type.parent() != null) {
                    type.parent().arguments().forEach(argument -> visit(argument.value(), body));
                }
                type.traits().forEach(trait -> visit(trait.type(), scope));
                declareMember(type.constructor(), SymbolKind.METHOD, body);
                type.methods().forEach(method -> declareMember(method, SymbolKind.METHOD, body));
                type.factories().forEach(factory -> declare(new Symbol(factory.name(),
                        SymbolKind.FACTORY, factory.function().nameSpan(), factory.span(),
                        factory.function()), body));
                type.properties().forEach(property -> declareProperty(property, body));

                visitIfPresent(type.constructor(), body);
                type.methods().forEach(method -> visit(method, body));
                type.factories().forEach(factory -> visit(factory.function(), body));
                type.properties().forEach(property -> visitProperty(property, body));
            }

            case TraitDeclStmt type -> {
                declare(new Symbol(type.name(), SymbolKind.TRAIT, type.nameSpan(),
                        type.span(), type), scope);
                LexicalScope body = child(ScopeKind.TRAIT_BODY, type, scope);
                declareParams(type.params(), null, null, body);
                visitAll(type.params(), body);
                type.methods().forEach(method -> declareMember(method, SymbolKind.METHOD, body));
                type.requirements().forEach(requirement -> declare(new Symbol(requirement.name(),
                        SymbolKind.REQUIREMENT, requirement.nameSpan(), requirement.span(),
                        requirement), body));
                type.properties().forEach(property -> declareProperty(property, body));

                type.methods().forEach(method -> visit(method, body));
                type.properties().forEach(property -> visitProperty(property, body));
            }

            // Расширение имени не заводит: оно добавляет члены тому, что уже есть.
            case ExtendStmt extension -> {
                visitIfPresent(extension.target(), scope);
                LexicalScope body = child(ScopeKind.EXTENSION_BODY, extension, scope);
                extension.methods().forEach(method -> declareMember(method, SymbolKind.METHOD, body));
                extension.properties().forEach(property -> declareProperty(property, body));
                extension.methods().forEach(method -> visit(method, body));
                extension.properties().forEach(property -> visitProperty(property, body));
            }

            case FunctionExpr function -> {
                LexicalScope body = child(ScopeKind.FUNCTION_BODY, function, scope);
                declareParams(function.params(), function.rest(), function.namedRest(), body);
                // Значение по умолчанию считается при каждом вызове и в области вызова:
                // оно видит параметры, объявленные левее.
                visitAll(function.params(), body);
                visitIfPresent(function.body(), body);
            }

            // Присваивание заводит имя, только если найти его негде: точно так же
            // решает Interpreter, получив Assignment.ABSENT.
            case AssignStmt assignment -> {
                visitIfPresent(assignment.value(), scope);
                if (assignment.target() instanceof VariableExpr variable) {
                    assignments.add(new Assigned(variable.name(), variable.span(), assignment,
                            scope, SymbolKind.VARIABLE));
                }
                declareOwnField(assignment, scope);
                visitIfPresent(assignment.target(), scope);
            }

            case UnpackStmt unpack -> {
                unpack.sources().forEach(source -> visit(source.value(), scope));
                for (UnpackTarget target : unpack.targets()) {
                    if (target.writes() && target.target() instanceof VariableExpr variable) {
                        assignments.add(new Assigned(variable.name(), variable.span(), unpack,
                                scope, SymbolKind.VARIABLE));
                    }
                    visitIfPresent(target.target(), scope);
                }
            }

            case VariableExpr variable -> references.add(new Reference(variable.name(),
                    variable.span(), variable));

            default -> visitChildren(node, scope);
        }
    }

    private void visitChildren(Node node, LexicalScope scope) {
        for (Node child : Nodes.children(node)) {
            visit(child, scope);
        }
    }

    private void visitIfPresent(Node node, LexicalScope scope) {
        if (node != null) {
            visit(node, scope);
        }
    }

    // --- объявления ----------------------------------------------------------

    private LexicalScope child(ScopeKind kind, Node owner, LexicalScope parent) {
        return new LexicalScope(kind, owner.span(), owner, parent);
    }

    private void declare(Symbol symbol, LexicalScope scope) {
        if (symbol.name() == null || symbol.nameSpan().isNone()) {
            // Анонимная функция и параметр-дырка имени не заводят: у первой его нет
            // в тексте, у второй нет вовсе.
            return;
        }
        scope.add(symbol);
        symbols.add(symbol);
    }

    private void declareParams(List<FunctionExpr.Param> params, FunctionExpr.Rest rest,
                               FunctionExpr.Rest namedRest, LexicalScope scope) {
        for (FunctionExpr.Param param : params) {
            if (!param.isHole()) {
                declare(new Symbol(param.name(), SymbolKind.PARAMETER, param.nameSpan(),
                        param.span(), param), scope);
            }
        }
        if (rest != null) {
            declare(new Symbol(rest.name(), SymbolKind.REST, rest.span(), rest.span(), rest), scope);
        }
        if (namedRest != null) {
            declare(new Symbol(namedRest.name(), SymbolKind.REST, namedRest.span(),
                    namedRest.span(), namedRest), scope);
        }
    }

    private void declareMember(FunctionExpr member, SymbolKind kind, LexicalScope scope) {
        if (member != null) {
            declare(new Symbol(member.name(), kind, member.nameSpan(), member.span(), member), scope);
        }
    }

    private void declareProperty(PropertyDecl property, LexicalScope scope) {
        declare(new Symbol(property.name(), SymbolKind.PROPERTY, property.nameSpan(),
                property.span(), property), scope);
    }

    /**
     * Тело аксессора — своя область: {@code field} существует только там и только
     * у свойства со скрытым полем.
     */
    private void visitProperty(PropertyDecl property, LexicalScope scope) {
        visitIfPresent(property.initial(), scope);
        visitAccessor(property, property.getter(), scope);
        visitAccessor(property, property.setter(), scope);
    }

    private void visitAccessor(PropertyDecl property, PropertyDecl.Accessor accessor,
                               LexicalScope scope) {
        if (accessor == null || accessor.function() == null) {
            return;
        }
        FunctionExpr function = accessor.function();
        LexicalScope body = child(ScopeKind.ACCESSOR, function, scope);
        if (property.hasBackingField()) {
            declare(new Symbol(FIELD, SymbolKind.FIELD, property.nameSpan(), property.span(),
                    property), body);
        }
        declareParams(function.params(), function.rest(), function.namedRest(), body);
        visitAll(function.params(), body);
        visitIfPresent(function.body(), body);
    }

    /**
     * Поле, заведённое через получателя: {@code this.area = width * height}.
     * <p>
     * В языке это единственный способ добавить экземпляру поле, которого нет
     * в заголовке класса, — и добавляет он его классу, а не области вызова.
     * Поэтому имя кладётся в тело типа, а не туда, где написано присваивание:
     * иначе метод, читающий {@code area} рядом, его бы не нашёл.
     */
    private void declareOwnField(AssignStmt assignment, LexicalScope scope) {
        if (!(assignment.target() instanceof AccessExpr access)
                || !(access.target() instanceof VariableExpr receiver)
                || !"this".equals(receiver.name())) {
            return;
        }
        String name = access.literalKey();
        LexicalScope type = scope.enclosingType();
        if (name == null || type == null || type.declared(name) != null) {
            return;
        }
        declare(new Symbol(name, SymbolKind.PARAMETER, access.key().span(), assignment.span(),
                assignment), type);
    }

    private void declareTarget(UnpackTarget target, SymbolKind kind, Stmt owner,
                               LexicalScope scope) {
        if (target.writes() && target.target() instanceof VariableExpr variable) {
            declare(new Symbol(variable.name(), kind, variable.span(), target.span(), owner), scope);
        }
    }

}
