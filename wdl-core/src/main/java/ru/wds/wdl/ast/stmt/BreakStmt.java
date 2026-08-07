package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Выход из цикла: {@code break}.
 * <p>
 * Что этот {@code break} прерывает, в дереве не записано — прерывается ближайший
 * объемлющий цикл, и находит его выполнение, а не разбор. Зато сам факт «мы внутри
 * цикла» проверяет парсер: {@code break} снаружи цикла — ошибка разбора, а не сюрприз
 * во время выполнения той единственной ветки, куда до релиза никто не заглянул.
 *
 * @param span место в исходнике
 */
public record BreakStmt(Span span) implements Stmt {

    public BreakStmt {
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "break";
    }
}
