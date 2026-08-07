package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Ветвление: {@code if (условие) ... else ...}.
 * <p>
 * Отдельного узла для {@code else if} нет и не нужно: цепочка
 * {@code if (a) ... else if (b) ... else ...} — это {@code IfStmt}, у которого
 * в {@link #elseBranch()} лежит другой {@code IfStmt}. Одно правило разбора вместо
 * особого случая, и любой обход дерева получает поддержку цепочек бесплатно.
 * <p>
 * Ветви объявлены как {@link Stmt}, а не {@link BlockStmt}: тело может быть и одной
 * инструкцией без скобок. Разницу между {@code if (x) счёт = 1} и
 * {@code if (x) { счёт = 1 }} видно не здесь, а при выполнении — область видимости
 * создаёт блок, см. {@link BlockStmt}.
 * <p>
 * Условие — {@link Expr}, и присваивание в него не вписать по построению: {@code x = 5}
 * в языке инструкция, а не выражение. Классическая опечатка {@code if (x = 5)} просто
 * не разбирается.
 *
 * @param condition  условие; ветвь выбирается по его истинности
 * @param thenBranch что выполнить, если условие истинно
 * @param elseBranch иначе-ветвь или {@code null}, если её нет
 * @param span       место в исходнике целиком
 */
public record IfStmt(Expr condition, Stmt thenBranch, Stmt elseBranch, Span span) implements Stmt {

    public IfStmt {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(thenBranch, "thenBranch");
        Objects.requireNonNull(span, "span");
        // elseBranch намеренно может быть null: «иначе-ветви нет» — это не пустой блок,
        // и инструментам (форматтер, печать дерева) разницу видеть нужно.
    }

    public boolean hasElse() {
        return elseBranch != null;
    }

    @Override
    public String toString() {
        return "if (" + condition + ")" + (hasElse() ? " ... else ..." : " ...");
    }
}
