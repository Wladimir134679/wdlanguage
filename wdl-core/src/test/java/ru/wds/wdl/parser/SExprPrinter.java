package ru.wds.wdl.parser;

import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.ast.visitor.ExprVisitor;

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
        expr.arguments().forEach(argument -> sb.append(' ').append(visit(argument, context)));
        return sb.append(')').toString();
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
     * что относится к самой функции, — имя и параметры.
     */
    @Override
    public String visitFunction(FunctionExpr expr, Void context) {
        StringBuilder sb = new StringBuilder("(fun ").append(expr.title());
        expr.params().forEach(param -> sb.append(' ').append(param.name()));
        return sb.append(')').toString();
    }

    @Override
    public String visitError(ErrorExpr expr, Void context) {
        return "<ошибка>";
    }
}
