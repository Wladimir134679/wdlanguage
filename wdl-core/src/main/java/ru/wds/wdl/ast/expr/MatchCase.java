package ru.wds.wdl.ast.expr;

import ru.wds.wdl.ast.Fragment;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Одна ветка {@code match}: образцы, необязательное условие и тело.
 * <p>
 * Образцов может быть несколько — {@code case 1, 2, 3}: это и есть группировка,
 * которую в Си делает провал через пустую метку. <b>Провала в языке нет</b>,
 * и группировку надо было чем-то заменить; перечисление читается прямее, чем
 * «пустая ветка означает продолжить в следующую».
 * <p>
 * Условие ({@code guard}) — это {@code case > 90 if !throttled}. Ветка вправе состоять
 * из одного условия ({@code case if throttled =>}): предмет при этом по-прежнему один
 * и назван в одном месте. Если условиями оказались <i>все</i> ветки, автору был нужен
 * {@code if} — но это замечание линтера, а не запрет парсера.
 * <p>
 * Тело — либо выражение ({@code =>}), либо блок ({@code &#123; ... &#125;}), и ровно
 * одно из двух. Стрелка значит здесь то же, что в {@code def f(a) => a + 1}: «дальше
 * значение».
 * <p>
 * Блок годится в обеих позициях, и разница в том, чем он заканчивается. Инструкцией —
 * просто делает. Выражением — обязан отдать значение через
 * {@link ru.wds.wdl.ast.stmt.YieldStmt yield}, и то, что хотя бы один {@code yield}
 * в ветке написан, проверяет парсер; что он <i>выполнился</i> — выполнение, потому
 * что {@code yield} бывает под условием.
 *
 * @param tails  образцы; пусты у ветки {@code else} и у ветки из одного условия
 * @param guard  дополнительное условие или {@code null}
 * @param value  тело-выражение или {@code null}
 * @param body   тело-блок или {@code null}
 * @param span   место в исходнике целиком
 */
public record MatchCase(List<CaseTail> tails, Expr guard, Expr value, Stmt body, Span span)
        implements Fragment {

    public MatchCase {
        tails = List.copyOf(Objects.requireNonNull(tails, "tails"));
        Objects.requireNonNull(span, "span");
        if ((value == null) == (body == null)) {
            throw new IllegalArgumentException("у ветки ровно одно тело: выражение или блок");
        }
        // «Ни образцов, ни условия» здесь не запрещено, и это не пропуск: ровно так
        // выглядит ветка 'else', которая лежит в MatchExpr.otherwise. У ветки 'case'
        // такого быть не должно, но сказать об этом внятно может только парсер —
        // он один знает, что слово 'case' написано и что за ним ничего не стоит.
    }

    /** Ветка без образцов и условия — то есть {@code else}. */
    public boolean isOtherwise() {
        return tails.isEmpty() && guard == null;
    }

    /** Даёт ли ветка значение — то есть написана ли она стрелкой. */
    public boolean isValue() {
        return value != null;
    }

    public boolean hasGuard() {
        return guard != null;
    }

    @Override
    public String toString() {
        String head = isOtherwise() ? "else" : "case " + tails + (hasGuard() ? " if ..." : "");
        return head + (isValue() ? " => ..." : " { ... }");
    }
}
