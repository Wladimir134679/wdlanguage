package ru.wds.wdl.tools.analysis;

import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Употребление имени — там, где к нему обращаются, а не там, где его завели.
 * <p>
 * Только простое имя ({@link VariableExpr}). Обращение по ключу — {@code point.x} —
 * употреблением имени не считается: слева там значение, и к какому объявлению ведёт
 * ключ, без типа не узнать. Это следующий этап; врать здесь нельзя, потому что
 * на употреблениях стоит переименование.
 *
 * @param name имя, как оно написано
 * @param span место употребления
 * @param node узел, из которого оно взято
 */
public record Reference(String name, Span span, VariableExpr node) {

    public Reference {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(node, "node");
    }
}
