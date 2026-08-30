package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Выражение, выполняемое ради его действия: {@code println("привет")}.
 * <p>
 * Инструкцией может стать не всякое выражение. Строка {@code a + 1} сама по себе
 * ничего не делает — это почти наверняка опечатка, и парсер о ней скажет. В этой роли
 * разрешены вызов, создание и {@code match}: сам он ничего наружу не даёт, но его ветки
 * делают, и каждое стрелочное тело проверено той же меркой. Когда появятся {@code i++}
 * и другие операции с эффектом, список расширится ровно на них.
 *
 * @param expr выражение с эффектом
 * @param span место в исходнике
 */
public record ExprStmt(Expr expr, Span span) implements Stmt {

    public ExprStmt {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return expr.toString();
    }
}
