package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Перебор: {@code for (товар in корзина) ...}.
 * <p>
 * Та же {@code for}, что и со счётчиком, — форму парсер различает по тому, что стоит
 * после открывающей скобки. Отдельного ключевого слова {@code foreach} нет: незачем
 * резервировать второе слово ради конструкции, которую и так ни с чем не спутать.
 * <p>
 * Переменная цикла — <b>своя на каждый проход</b>, а не одна общая: она заводится
 * в области видимости итерации. Сейчас разницы не видно, но как только появятся
 * функции, замыкание из тела цикла захватит значение своего прохода, а не последнее —
 * ровно те грабли, на которые JavaScript наступал до появления {@code let}.
 *
 * @param name     имя переменной цикла
 * @param nameSpan место имени в исходнике — чтобы ошибка указывала на него, а не на весь цикл
 * @param iterable выражение, дающее то, что перебираем
 * @param body     тело цикла
 * @param span     место в исходнике целиком
 */
public record ForEachStmt(String name, Span nameSpan, Expr iterable, Stmt body, Span span) implements Stmt {

    public ForEachStmt {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(nameSpan, "nameSpan");
        Objects.requireNonNull(iterable, "iterable");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "for (" + name + " in " + iterable + ") ...";
    }
}
