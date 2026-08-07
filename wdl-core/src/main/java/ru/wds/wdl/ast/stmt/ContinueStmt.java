package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Переход к следующему проходу цикла: {@code continue}.
 * <p>
 * Брат {@link BreakStmt} и устроен так же: ближайший цикл ищется при выполнении,
 * а нахождение внутри цикла проверяется при разборе.
 * <p>
 * В цикле со счётчиком {@code continue} не пропускает шаг: {@code for (i = 0; i < 3; i += 1)}
 * с {@code continue} в теле остаётся конечным. Пропуск шага — классический способ
 * получить вечный цикл на ровном месте, и делать так язык не будет.
 *
 * @param span место в исходнике
 */
public record ContinueStmt(Span span) implements Stmt {

    public ContinueStmt {
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "continue";
    }
}
