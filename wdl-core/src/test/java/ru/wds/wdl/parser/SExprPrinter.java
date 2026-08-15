package ru.wds.wdl.parser;

import ru.wds.wdl.ast.expr.*;
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
            if (argument.isNamed()) {
                sb.append('(').append(argument.name()).append(": ")
                        .append(visit(argument.value(), context)).append(')');
            } else {
                sb.append(visit(argument.value(), context));
            }
        }
    }

    /** Отдельный аргумент — для тестов, которым нужен один элемент списка. */
    static String print(Argument argument) {
        return print(argument.value());
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
        sb.append("def ").append(expr.title());
        expr.params().forEach(param -> sb.append(' ').append(param.hasDefault()
                ? "(" + param.name() + " " + visit(param.defaultValue(), context) + ")"
                : param.name()));
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
