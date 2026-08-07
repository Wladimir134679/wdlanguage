package ru.wds.wdl.ast;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Заглушка на месте инструкции, которую не удалось разобрать, — то же, что
 * {@link ErrorExpr}, но на уровне инструкций.
 * <p>
 * Нужна, чтобы одна испорченная строка не выкидывала из программы все остальные:
 * парсер отмечает место, пропускает строку и разбирает файл дальше, а инструменты
 * получают дерево, в котором видно и что уцелело, и где именно дырка.
 *
 * @param span место в исходнике
 */
public record ErrorStmt(Span span) implements Stmt {

    public ErrorStmt {
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "<неразобранная инструкция>";
    }
}
