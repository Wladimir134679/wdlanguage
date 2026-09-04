package ru.wds.wdl.parser;

import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.stmt.UnpackStmt;
import ru.wds.wdl.ast.stmt.UnpackTarget;
import ru.wds.wdl.ast.visitor.ExprVisitor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Печатает дерево скобочной записью: {@code 1 + 2 * 3} → {@code (+ 1 (* 2 3))}.
 * <p>
 * Форма дерева — главное, что проверяется в парсере, и сравнивать её удобнее всего
 * строкой: ожидаемый результат в тесте виден целиком, а не собирается из десятка
 * вложенных конструкторов.
 * <p>
 * Заодно это третий посетитель в проекте (после интерпретатора и дампа AST),
 * написанный без единой правки узлов, — ровно то свойство, ради которого
 * диспетчеризация собрана в {@link ExprVisitor}.
 */
final class SExprPrinter implements ExprVisitor<String, Void> {

    static String print(Expr expr) {
        return new SExprPrinter().visit(expr, null);
    }

    @Override
    public String visitLiteral(LiteralExpr expr, Void context) {
        return expr.value().toString();
    }

    @Override
    public String visitVariable(VariableExpr expr, Void context) {
        return expr.name();
    }

    @Override
    public String visitUnary(UnaryExpr expr, Void context) {
        return "(" + expr.op().symbol() + " " + visit(expr.operand(), context) + ")";
    }

    @Override
    public String visitBinary(BinaryExpr expr, Void context) {
        return "(" + expr.op().symbol() + " " + visit(expr.left(), context)
                + " " + visit(expr.right(), context) + ")";
    }

    @Override
    public String visitTernary(TernaryExpr expr, Void context) {
        return "(?: " + visit(expr.condition(), context) + " " + visit(expr.ifTrue(), context)
                + " " + visit(expr.ifFalse(), context) + ")";
    }

    /**
     * {@code match (x) { case 1, 2 => "мало" else => "много" }} →
     * {@code (match x (case (== 1) (== 2) => "мало") (else => "много"))}.
     * <p>
     * Голый образец печатается со своим {@code ==} — так видно, что «система образцов»
     * это обычные бинарные операции, а не отдельная грамматика.
     */
    @Override
    public String visitMatch(MatchExpr expr, Void context) {
        StringBuilder sb = new StringBuilder("(match ").append(visit(expr.subject(), context));
        for (MatchCase branch : expr.cases()) {
            sb.append(' ').append(matchCase(branch, "case", context));
        }
        if (expr.hasOtherwise()) {
            sb.append(' ').append(matchCase(expr.otherwise(), "else", context));
        }
        return sb.append(')').toString();
    }

    /** Тело-блок печатается как {@code {}}: его форму проверяет {@code AstDumper}. */
    private String matchCase(MatchCase branch, String head, Void context) {
        StringBuilder sb = new StringBuilder("(").append(head);
        for (CaseTail tail : branch.tails()) {
            sb.append(" (").append(tail.op().symbol()).append(' ')
                    .append(visit(tail.right(), context)).append(')');
        }
        if (branch.hasGuard()) {
            sb.append(" (if ").append(visit(branch.guard(), context)).append(')');
        }
        sb.append(branch.isValue() ? " => " + visit(branch.value(), context) : " {}");
        return sb.append(')').toString();
    }

    /** Стиль записи намеренно не печатается: {@code a.b} и {@code a["b"]} должны совпасть. */
    @Override
    public String visitAccess(AccessExpr expr, Void context) {
        return "(get " + visit(expr.target(), context) + " " + visit(expr.key(), context) + ")";
    }

    @Override
    public String visitCall(CallExpr expr, Void context) {
        StringBuilder sb = new StringBuilder("(call ").append(visit(expr.callee(), context));
        arguments(sb, expr.arguments(), context);
        return sb.append(')').toString();
    }

    /** {@code new Point(1, 2)} → {@code (new Point 1 2)}: видно, что это не вызов. */
    @Override
    public String visitNew(NewExpr expr, Void context) {
        StringBuilder sb = new StringBuilder("(new ").append(visit(expr.callee(), context));
        arguments(sb, expr.arguments(), context);
        return sb.append(')').toString();
    }

    /**
     * Аргументы: позиционный печатается выражением, именованный — парой,
     * как и пара объекта. {@code greet("мир", punct: "?")} →
     * {@code (call greet "мир" (punct: "?"))}.
     */
    private void arguments(StringBuilder sb, List<Argument> arguments, Void context) {
        for (Argument argument : arguments) {
            sb.append(' ');
            switch (argument.kind()) {
                case POSITIONAL -> sb.append(visit(argument.value(), context));
                case NAMED -> sb.append('(').append(argument.name()).append(": ")
                        .append(visit(argument.value(), context)).append(')');
                case SPREAD -> sb.append("(* ").append(visit(argument.value(), context)).append(')');
                case NAMED_SPREAD -> sb.append("(** ")
                        .append(visit(argument.value(), context)).append(')');
            }
        }
    }

    /** Отдельный аргумент — для тестов, которым нужен один элемент списка. */
    static String print(Argument argument) {
        return print(argument.value());
    }

    /**
     * Распаковка: {@code x, y = *point} → {@code (unpack * (x y) ((* point)))}.
     * <p>
     * Вид записи стоит вторым словом, потому что в тексте эти три формы различаются
     * одной звёздочкой, а деревья у них разные: сравнивать их надо так, чтобы разница
     * бросалась в глаза и в ожидании теста.
     */
    static String print(UnpackStmt stmt) {
        SExprPrinter printer = new SExprPrinter();
        StringBuilder sb = new StringBuilder("(unpack ").append(switch (stmt.style()) {
            case POSITIONAL -> "*";
            case NAMED -> "**";
            case PAIRWISE -> "пары";
        }).append(" (");
        for (int i = 0; i < stmt.targets().size(); i++) {
            sb.append(i == 0 ? "" : " ").append(printer.target(stmt.targets().get(i)));
        }
        sb.append(") (");
        for (int i = 0; i < stmt.sources().size(); i++) {
            sb.append(i == 0 ? "" : " ").append(printer.source(stmt.sources().get(i)));
        }
        return sb.append("))").toString();
    }

    private String target(UnpackTarget target) {
        return switch (target.kind()) {
            case VALUE -> visit(target.target(), null);
            case HOLE -> "_";
            case REST -> "(* " + (target.writes() ? visit(target.target(), null) : "_") + ")";
            case REST_NAMED -> "(** " + (target.writes() ? visit(target.target(), null) : "_") + ")";
        };
    }

    private String source(Argument source) {
        StringBuilder sb = new StringBuilder();
        arguments(sb, List.of(source), null);
        return sb.toString().strip();
    }

    @Override
    public String visitArray(ArrayExpr expr, Void context) {
        String elements = expr.elements().stream()
                .map(element -> visit(element, context))
                .collect(Collectors.joining(" "));
        return elements.isEmpty() ? "(array)" : "(array " + elements + ")";
    }

    @Override
    public String visitObject(ObjectExpr expr, Void context) {
        String entries = expr.entries().stream()
                .map(entry -> "(" + visit(entry.key(), context) + " " + visit(entry.value(), context) + ")")
                .collect(Collectors.joining(" "));
        return entries.isEmpty() ? "(object)" : "(object " + entries + ")";
    }

    /**
     * Тело не печатается: форму тела проверяет {@code AstDumper}, а здесь важно то,
     * что относится к самой функции, — имя и параметры. Параметр со значением
     * по умолчанию печатается парой: {@code def f(a, b = 10)} → {@code (def f a (b 10))}.
     */
    @Override
    public String visitFunction(FunctionExpr expr, Void context) {
        // Модификатор — часть заголовка, поэтому он и в форме дерева виден отдельным
        // словом: (synchronized-def bump). Иначе тест на 'synchronized' проверял бы
        // не форму, а поведение — то есть не то, за что отвечает парсер.
        StringBuilder sb = new StringBuilder("(");
        expr.modifiers().forEach(modifier -> sb.append(modifier.text()).append('-'));
        sb.append("def ").append(expr.writtenName());
        expr.params().forEach(param -> sb.append(' ').append(param.hasDefault()
                ? "(" + param.name() + " " + visit(param.defaultValue(), context) + ")"
                : param.name()));
        // Остаток печатается своей формой: без неё '(def f a)' у 'def f(a)' и 'def f(*a)'
        // совпали бы, а это разные заголовки.
        if (expr.rest() != null) {
            sb.append(" (* ").append(expr.rest().name()).append(')');
        }
        if (expr.namedRest() != null) {
            sb.append(" (** ").append(expr.namedRest().name()).append(')');
        }
        return sb.append(')').toString();
    }

    /** {@code try? f()} → {@code (try? (call f))}: форма записи здесь и есть смысл. */
    @Override
    public String visitTryExpr(TryExpr expr, Void context) {
        return "(" + expr.style().text() + " " + visit(expr.inner(), context) + ")";
    }

    @Override
    public String visitError(ErrorExpr expr, Void context) {
        return "<ошибка>";
    }
}
