package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Короткая форма обработки ошибки: {@code try? выражение} и {@code try! выражение}.
 * <p>
 * В отличие от инструкции {@code try}, это <b>выражение</b> — оно даёт значение
 * и стоит там, где ждут значение: {@code n = try? parseNumber(text)}.
 * <p>
 * Обе формы ловят только то, что вообще ловится: {@code FatalError} проходит сквозь
 * них наружу, как и сквозь {@code catch}.
 * <p>
 * Записываются слитно, и парсер этого требует. Двусмысленности с тернарным оператором
 * нет — после {@code try} левого операнда для {@code ?} не существует, — но одинаковая
 * запись двух разных вещей сбивает читателя, а он здесь главный.
 *
 * @param inner выражение, ошибку которого перехватывают
 */
public record TryExpr(Expr inner, TryStyle style, Span span) implements Expr {

    public TryExpr {
        Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(span, "span");
    }
}
