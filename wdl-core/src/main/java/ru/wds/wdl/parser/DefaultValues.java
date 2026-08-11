package ru.wds.wdl.parser;

import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.ArrayExpr;
import ru.wds.wdl.ast.expr.BinaryExpr;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.ErrorExpr;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.LiteralExpr;
import ru.wds.wdl.ast.expr.NewExpr;
import ru.wds.wdl.ast.expr.ObjectExpr;
import ru.wds.wdl.ast.expr.TernaryExpr;
import ru.wds.wdl.ast.expr.TryExpr;
import ru.wds.wdl.ast.expr.UnaryExpr;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.diagnostic.Diagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * Проверка значений по умолчанию: на что им разрешено ссылаться.
 * <p>
 * Разбором здесь ничего не делается — списки параметров уже готовы, — поэтому и
 * держится это отдельно от парсера: чистый обход выражения без единого взгляда
 * на поток токенов. Резолвер имён для проверки не нужен, достаточно посмотреть,
 * какие имена вообще встречаются в выражении по умолчанию.
 */
final class DefaultValues {

    private DefaultValues() {
    }

    /**
     * Значение по умолчанию видит параметры <b>слева</b> от себя и не видит остальных.
     * <p>
     * Считается оно при вызове, по порядку, поэтому {@code fun f(a, b = a * 2)} — законно
     * и полезно, а {@code fun f(a = b, b = 1)} к моменту вычисления {@code a} нашло бы
     * не параметр, а одноимённую переменную снаружи — и подставило бы её молча. Язык
     * такие подмены не допускает нигде, поэтому это ошибка разбора.
     */
    static void checkLookLeft(List<FunctionExpr.Param> params, Diagnostics diagnostics) {
        for (int i = 0; i < params.size(); i++) {
            FunctionExpr.Param param = params.get(i);
            if (!param.hasDefault()) {
                continue;
            }
            List<String> unbound = new ArrayList<>();
            for (int j = i; j < params.size(); j++) {
                unbound.add(params.get(j).name());
            }
            VariableExpr use = findUse(param.defaultValue(), unbound);
            if (use == null) {
                continue;
            }
            diagnostics.error(use.span(), "значение по умолчанию параметра '" + param.name() + "' "
                    + (use.name().equals(param.name())
                            ? "ссылается на сам параметр"
                            : "ссылается на параметр '" + use.name() + "', который связывается позже"));
        }
    }

    /**
     * Значение по умолчанию поля трейта вычисляется в области, где объявлен <b>трейт</b>,
     * и на каждом создании заново. Других полей оно поэтому не видит — ни левее, ни
     * правее: у трейта нет создания, в котором они связывались бы по порядку.
     */
    static void checkTraitFields(List<FunctionExpr.Param> params, Diagnostics diagnostics) {
        List<String> all = new ArrayList<>(params.size());
        params.forEach(param -> all.add(param.name()));
        for (FunctionExpr.Param param : params) {
            if (!param.hasDefault()) {
                continue;
            }
            VariableExpr use = findUse(param.defaultValue(), all);
            if (use != null) {
                diagnostics.error(use.span(), "значение по умолчанию поля '" + param.name()
                        + "' ссылается на поле '" + use.name() + "': значения полей трейта"
                        + " вычисляются в области объявления трейта и друг друга не видят");
            }
        }
    }

    /**
     * Первое обращение к одному из имён в выражении — или {@code null}, если их там нет.
     * <p>
     * В тело вложенной функции обход не заходит намеренно: {@code fun f(a = fun(b) => b, b = 1)}
     * — законно, {@code b} внутри лямбды своё собственное и к параметрам {@code f}
     * отношения не имеет.
     */
    private static VariableExpr findUse(Expr expr, List<String> names) {
        return switch (expr) {
            case VariableExpr variable -> names.contains(variable.name()) ? variable : null;
            case UnaryExpr unary -> findUse(unary.operand(), names);
            case BinaryExpr binary -> firstUse(names, binary.left(), binary.right());
            case TernaryExpr ternary ->
                    firstUse(names, ternary.condition(), ternary.ifTrue(), ternary.ifFalse());
            case AccessExpr access -> firstUse(names, access.target(), access.key());
            case CallExpr call -> {
                VariableExpr inCallee = findUse(call.callee(), names);
                yield inCallee != null ? inCallee : firstUse(names, call.arguments());
            }
            case NewExpr created -> {
                VariableExpr inCallee = findUse(created.callee(), names);
                yield inCallee != null ? inCallee : firstUse(names, created.arguments());
            }
            case ArrayExpr array -> firstUse(names, array.elements());
            case ObjectExpr object -> {
                for (ObjectExpr.Entry entry : object.entries()) {
                    VariableExpr use = firstUse(names, entry.key(), entry.value());
                    if (use != null) {
                        yield use;
                    }
                }
                yield null;
            }
            case TryExpr shortForm -> findUse(shortForm.inner(), names);
            case FunctionExpr ignored -> null;
            case LiteralExpr ignored -> null;
            case ErrorExpr ignored -> null;
        };
    }

    private static VariableExpr firstUse(List<String> names, Expr... exprs) {
        return firstUse(names, List.of(exprs));
    }

    private static VariableExpr firstUse(List<String> names, List<Expr> exprs) {
        for (Expr expr : exprs) {
            VariableExpr use = findUse(expr, names);
            if (use != null) {
                return use;
            }
        }
        return null;
    }
}
