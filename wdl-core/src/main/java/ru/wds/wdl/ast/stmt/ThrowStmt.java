package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Бросок ошибки: {@code throw new ParseError(text)}.
 * <p>
 * Инструкция, а не выражение, — как {@code return}: у неё нет значения, и записать
 * {@code x = throw ...} нельзя в принципе.
 * <p>
 * <b>Бросить можно только экземпляр {@code Exception} или его наследника</b>, и это
 * проверяет выполнение. Lua и JavaScript разрешают {@code throw 5}, и цена видна
 * в каждом обработчике: прежде чем спросить у ошибки хоть что-нибудь, надо проверить,
 * что это вообще объект. Ограничение стоит одной проверки в интерпретаторе и убирает
 * эту заботу из всех обработчиков навсегда.
 * <p>
 * Точка с запятой после {@code throw} не обязательна, в отличие от {@code return}:
 * аргумент здесь есть всегда, и двусмысленности «есть значение или нет» не возникает.
 *
 * @param error выражение, дающее саму ошибку
 */
public record ThrowStmt(Expr error, Span span) implements Stmt {

    public ThrowStmt {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(span, "span");
    }
}
