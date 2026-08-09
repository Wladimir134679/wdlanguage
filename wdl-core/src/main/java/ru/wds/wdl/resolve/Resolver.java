package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.stmt.*;
import ru.wds.wdl.ast.visitor.ExprVisitor;
import ru.wds.wdl.ast.visitor.StmtVisitor;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Резолвер: связывает объявления классов и трейтов и проверяет то, что известно
 * до выполнения.
 * <p>
 * Стадия между парсером и интерпретатором. Она нужна ровно из-за одного обещания
 * языка: невыполненное требование трейта — ошибка <b>при объявлении класса</b>,
 * а не при вызове и не при создании экземпляра. Это единственное, чего не даёт
 * утиная типизация, и ради этого трейты и заведены — ошибка появляется там,
 * где сделана, а не через час работы скрипта в чужом приложении.
 * <p>
 * Здесь же ловятся круг в наследовании и неверное число аргументов родителю:
 * и то и другое написано в тексте и никакого выполнения не требует.
 * <p>
 * <b>Порядок сборки.</b> Сначала все трейты — они ни от чего не зависят, поэтому
 * порядок их объявления не значит ничего. Потом классы, обходом в глубину по имени
 * родителя: цепочка предков оказывается готовой раньше, чем понадобится, а круг
 * ловится самой структурой обхода, без отдельной проверки.
 * <p>
 * <b>Области имён.</b> Объявления верхнего уровня видны друг другу целиком —
 * наследоваться можно от класса, объявленного ниже по тексту. Объявление внутри
 * функции или блока видно от своей точки и ниже, как и объявление функции:
 * поднимать его в корень значило бы протаскивать имя наружу.
 * <p>
 * Ошибки копятся в тот же {@link Diagnostics}, что у лексера и парсера, и выглядят
 * одинаково. Результат возвращается всегда — как и дерево от парсера.
 */
public final class Resolver {

    private final Diagnostics diagnostics;
    private final Map<Stmt, Shape> shapes = new IdentityHashMap<>();
    /** Объявления, форма которых строится прямо сейчас, — по ним и виден круг. */
    private final Set<Stmt> building = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<String> chain = new ArrayDeque<>();
    private final Walk walk = new Walk();

    private Resolver(Diagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static Resolution resolve(Program program, Diagnostics diagnostics) {
        Resolver resolver = new Resolver(diagnostics);
        Types root = new Types(null);
        // Верхний уровень объявляется целиком до разбора зависимостей — отсюда
        // и свобода порядка: 'class Circle : Shape' может стоять выше самого Shape.
        program.statements().forEach(statement -> resolver.declare(statement, root));
        resolver.statements(program.statements(), root);
        return new Resolution(resolver.shapes);
    }

    // --- объявления ----------------------------------------------------------

    /** Обход списка инструкций одной области: объявить, потом разобрать. */
    private void statements(List<Stmt> list, Types scope) {
        for (Stmt statement : list) {
            declare(statement, scope);
            shapeOf(statement, scope);
            walk.visit(statement, scope);
        }
    }

    private void declare(Stmt statement, Types scope) {
        String name = typeName(statement);
        if (name == null) {
            return;
        }
        Declared existing = scope.own(name);
        if (existing != null && existing.declaration() != statement) {
            diagnostics.error(nameSpan(statement),
                    "'" + name + "' в этой области уже объявлен");
            return;
        }
        scope.declare(name, new Declared(statement, scope));
    }

    private void shapeOf(Stmt statement, Types scope) {
        if (statement instanceof TraitDeclStmt trait) {
            traitShape(trait);
        } else if (statement instanceof ClassDeclStmt klass) {
            classShape(klass, scope);
        }
    }

    private TraitShape traitShape(TraitDeclStmt declaration) {
        if (shapes.get(declaration) instanceof TraitShape shape) {
            return shape;
        }
        TraitShape shape = new TraitShape(declaration);
        shapes.put(declaration, shape);
        return shape;
    }

    private ClassShape classShape(ClassDeclStmt declaration, Types scope) {
        if (shapes.get(declaration) instanceof ClassShape shape) {
            return shape;
        }
        if (!building.add(declaration)) {
            diagnostics.error(declaration.nameSpan(), "циклическое наследование: "
                    + String.join(" → ", chain) + " → " + declaration.name());
            return null;
        }
        chain.addLast(declaration.name());
        try {
            ClassShape shape = new ClassShape(declaration,
                    parentShape(declaration, scope), traitShapes(declaration, scope));
            shapes.put(declaration, shape);
            checkParentArguments(shape);
            checkRequirements(shape);
            return shape;
        } finally {
            chain.removeLast();
            building.remove(declaration);
        }
    }

    private ClassShape parentShape(ClassDeclStmt declaration, Types scope) {
        ClassDeclStmt.Superclass parent = declaration.parent();
        if (parent == null) {
            return null;
        }
        Declared declared = scope.find(parent.name());
        if (declared == null) {
            diagnostics.error(parent.span(), "неизвестный класс '" + parent.name()
                    + "': наследоваться можно только от класса, объявленного в этом же скрипте");
            return null;
        }
        if (declared.declaration() instanceof TraitDeclStmt) {
            diagnostics.error(parent.span(), "'" + parent.name() + "' — трейт, а не класс: "
                    + "трейт подмешивается через 'with', наследуются от класса");
            return null;
        }
        return classShape((ClassDeclStmt) declared.declaration(), declared.scope());
    }

    private List<TraitShape> traitShapes(ClassDeclStmt declaration, Types scope) {
        List<TraitShape> traits = new ArrayList<>(declaration.traits().size());
        for (ClassDeclStmt.TraitRef reference : declaration.traits()) {
            Declared declared = scope.find(reference.name());
            if (declared == null) {
                diagnostics.error(reference.span(), "неизвестный трейт '" + reference.name() + "'");
                continue;
            }
            if (declared.declaration() instanceof ClassDeclStmt) {
                diagnostics.error(reference.span(), "'" + reference.name() + "' — класс, а не трейт: "
                        + "подмешать можно только трейт, у класса есть конструктор");
                continue;
            }
            traits.add(traitShape((TraitDeclStmt) declared.declaration()));
        }
        return traits;
    }

    // --- проверки ------------------------------------------------------------

    /**
     * Число аргументов родителю известно из текста, поэтому и проверяется здесь,
     * а не при каждом создании экземпляра.
     */
    private void checkParentArguments(ClassShape shape) {
        ClassShape parent = shape.parent();
        ClassDeclStmt.Superclass reference = shape.declaration().parent();
        if (parent == null || reference == null) {
            return;
        }
        int given = reference.arguments().size();
        if (!parent.arity().accepts(given)) {
            diagnostics.error(reference.span(), "класс '" + shape.name() + "' передаёт родителю '"
                    + parent.name() + "' " + given + " аргументов, а '" + parent.name()
                    + "' принимает " + parent.arity().describeArguments());
        }
    }

    /**
     * Требования трейтов.
     * <p>
     * Требование считается выполненным, если нужное поле или метод есть у самого
     * класса, у его предка или у другого трейта: смотрим в уже собранные плоские
     * таблицы, поэтому порядок {@code with} на результат не влияет.
     * <p>
     * Поле закрывает только поле, метод — только метод: у поля нечего проверять
     * на число аргументов, а перекрытие метода полем — уже другая история,
     * и это работа линтера.
     */
    private void checkRequirements(ClassShape shape) {
        for (TraitShape trait : shape.traits()) {
            for (FunctionExpr.Param required : trait.requiredFields()) {
                if (!shape.fields().containsKey(required.name())) {
                    diagnostics.error(shape.declaration().nameSpan(), unmet(shape, trait)
                            + "нет поля '" + required.name() + "'. Объявите его в заголовке класса");
                }
            }
            for (TraitDeclStmt.Requirement required : trait.requiredMethods()) {
                MethodSlot provided = shape.methods().get(required.name());
                if (provided == null) {
                    diagnostics.error(shape.declaration().nameSpan(), unmet(shape, trait)
                            + "нет метода '" + required.name() + "'");
                    continue;
                }
                Arity expected = ClassShape.arityOf(required.params());
                Arity actual = ClassShape.arityOf(provided.declaration().params());
                if (!actual.accepts(expected.min()) || !actual.accepts(expected.max())) {
                    diagnostics.error(shape.declaration().nameSpan(), unmet(shape, trait)
                            + "метод '" + required.name() + "' должен принимать "
                            + expected.describeArguments() + ", а принимает "
                            + actual.describeArguments());
                }
            }
        }
    }

    private static String unmet(ClassShape shape, TraitShape trait) {
        return "класс '" + shape.name() + "' не выполняет требование трейта '"
                + trait.name() + "': ";
    }

    private static String typeName(Stmt statement) {
        return switch (statement) {
            case ClassDeclStmt klass -> klass.name();
            case TraitDeclStmt trait -> trait.name();
            default -> null;
        };
    }

    private static Span nameSpan(Stmt statement) {
        return switch (statement) {
            case ClassDeclStmt klass -> klass.nameSpan();
            case TraitDeclStmt trait -> trait.nameSpan();
            default -> statement.span();
        };
    }

    // --- обход дерева --------------------------------------------------------

    /**
     * Механический обход: резолверу нужно добраться до объявлений, спрятанных
     * в телах функций, блоках и ветках. Компилятор перечислит все виды узлов сам,
     * поэтому забыть тут нечего.
     * <p>
     * Отдельным классом, а не методами самого резолвера, чтобы область имён типов
     * не протекала в подпись публичного API: обход — внутреннее дело стадии.
     */
    private final class Walk implements StmtVisitor<Void, Types>, ExprVisitor<Void, Types> {

        @Override
        public Void visitClassDecl(ClassDeclStmt stmt, Types scope) {
            stmt.params().forEach(param -> defaultValue(param, scope));
            if (stmt.parent() != null) {
                stmt.parent().arguments().forEach(argument -> visit(argument, scope));
            }
            if (stmt.hasConstructor()) {
                visit(stmt.constructor(), scope);
            }
            stmt.methods().forEach(method -> visit(method, scope));
            stmt.factories().forEach(factory -> visit(factory.function(), scope));
            return null;
        }

        @Override
        public Void visitTraitDecl(TraitDeclStmt stmt, Types scope) {
            stmt.params().forEach(param -> defaultValue(param, scope));
            stmt.methods().forEach(method -> visit(method, scope));
            return null;
        }

        @Override
        public Void visitBlock(BlockStmt stmt, Types scope) {
            statements(stmt.statements(), new Types(scope));
            return null;
        }

        /**
         * Значение константы обходится не для порядка: {@code const make = fun() { class Point(x) ... }}
         * — законный скрипт, и форму этого класса собирает именно обход.
         */
        @Override
        public Void visitConstDecl(ConstDeclStmt stmt, Types scope) {
            return visit(stmt.value(), scope);
        }

        @Override
        public Void visitFunDecl(FunDeclStmt stmt, Types scope) {
            return visit(stmt.function(), scope);
        }

        @Override
        public Void visitFunction(FunctionExpr expr, Types scope) {
            expr.params().forEach(param -> defaultValue(param, scope));
            return visit(expr.body(), new Types(scope));
        }

        @Override
        public Void visitExprStmt(ExprStmt stmt, Types scope) {
            return visit(stmt.expr(), scope);
        }

        @Override
        public Void visitAssign(AssignStmt stmt, Types scope) {
            visit(stmt.target(), scope);
            return visit(stmt.value(), scope);
        }

        @Override
        public Void visitIf(IfStmt stmt, Types scope) {
            visit(stmt.condition(), scope);
            visit(stmt.thenBranch(), scope);
            return stmt.hasElse() ? visit(stmt.elseBranch(), scope) : null;
        }

        @Override
        public Void visitWhile(WhileStmt stmt, Types scope) {
            visit(stmt.condition(), scope);
            return visit(stmt.body(), scope);
        }

        @Override
        public Void visitFor(ForStmt stmt, Types scope) {
            Types loop = new Types(scope);
            if (stmt.init() != null) {
                visit(stmt.init(), loop);
            }
            if (stmt.condition() != null) {
                visit(stmt.condition(), loop);
            }
            if (stmt.step() != null) {
                visit(stmt.step(), loop);
            }
            return visit(stmt.body(), loop);
        }

        @Override
        public Void visitForEach(ForEachStmt stmt, Types scope) {
            visit(stmt.iterable(), scope);
            return visit(stmt.body(), new Types(scope));
        }

        @Override
        public Void visitReturn(ReturnStmt stmt, Types scope) {
            return stmt.hasValue() ? visit(stmt.value(), scope) : null;
        }

        @Override
        public Void visitBreak(BreakStmt stmt, Types scope) {
            return null;
        }

        @Override
        public Void visitContinue(ContinueStmt stmt, Types scope) {
            return null;
        }

        @Override
        public Void visitErrorStmt(ErrorStmt stmt, Types scope) {
            return null;
        }

        @Override
        public Void visitUnary(UnaryExpr expr, Types scope) {
            return visit(expr.operand(), scope);
        }

        @Override
        public Void visitBinary(BinaryExpr expr, Types scope) {
            visit(expr.left(), scope);
            return visit(expr.right(), scope);
        }

        @Override
        public Void visitTernary(TernaryExpr expr, Types scope) {
            visit(expr.condition(), scope);
            visit(expr.ifTrue(), scope);
            return visit(expr.ifFalse(), scope);
        }

        @Override
        public Void visitAccess(AccessExpr expr, Types scope) {
            visit(expr.target(), scope);
            return visit(expr.key(), scope);
        }

        @Override
        public Void visitCall(CallExpr expr, Types scope) {
            visit(expr.callee(), scope);
            expr.arguments().forEach(argument -> visit(argument, scope));
            return null;
        }

        @Override
        public Void visitNew(NewExpr expr, Types scope) {
            visit(expr.callee(), scope);
            expr.arguments().forEach(argument -> visit(argument, scope));
            return null;
        }

        @Override
        public Void visitArray(ArrayExpr expr, Types scope) {
            expr.elements().forEach(element -> visit(element, scope));
            return null;
        }

        @Override
        public Void visitObject(ObjectExpr expr, Types scope) {
            for (ObjectExpr.Entry entry : expr.entries()) {
                visit(entry.key(), scope);
                visit(entry.value(), scope);
            }
            return null;
        }

        @Override
        public Void visitLiteral(LiteralExpr expr, Types scope) {
            return null;
        }

        @Override
        public Void visitVariable(VariableExpr expr, Types scope) {
            return null;
        }

        @Override
        public Void visitError(ErrorExpr expr, Types scope) {
            return null;
        }

        private void defaultValue(FunctionExpr.Param param, Types scope) {
            if (param.hasDefault()) {
                visit(param.defaultValue(), scope);
            }
        }
    }

    // --- области имён типов --------------------------------------------------

    /** Объявление и область, в которой оно записано: строить его нужно там же. */
    record Declared(Stmt declaration, Types scope) {
    }

    /**
     * Область имён классов и трейтов.
     * <p>
     * Отдельная от области значений и существует только на время разбора: имена
     * классов резолвер обязан связать <b>до</b> первой инструкции, а значения
     * появляются только при выполнении.
     */
    static final class Types {

        private final Types parent;
        private final Map<String, Declared> declarations = new HashMap<>();

        Types(Types parent) {
            this.parent = parent;
        }

        void declare(String name, Declared declared) {
            declarations.put(name, declared);
        }

        /** Объявление в этой области, не заглядывая наружу. */
        Declared own(String name) {
            return declarations.get(name);
        }

        Declared find(String name) {
            for (Types scope = this; scope != null; scope = scope.parent) {
                Declared declared = scope.declarations.get(name);
                if (declared != null) {
                    return declared;
                }
            }
            return null;
        }
    }
}
