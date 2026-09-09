package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Строка с подстановкой: {@code 'итого: ${price * count} руб.'}.
 * <p>
 * <b>Отдельный узел, а не цепочка сложений.</b> Разложить запись в {@code +} нельзя
 * не из-за красоты дерева: {@code '${a}${b}'} на двух числах дало бы сложение, потому
 * что конкатенацию включает строка хотя бы в одном операнде. Здесь же результат —
 * строка всегда, независимо от того, что попало в подстановку.
 * <p>
 * <b>Части однородны.</b> Текстовые куски лежат такими же {@link LiteralExpr}, как
 * если бы их написали отдельной строкой, а подстановки — обычными выражениями. Тому,
 * кто обходит дерево, не нужно знать, что здесь чередование: список детей — это просто
 * список. Восстановить исходную запись всё равно можно по интервалам — они у текста
 * и у подстановки разные.
 * <p>
 * Пустых кусков в списке нет: между двумя соседними подстановками текста не было,
 * и хранить пустую строку значило бы отвечать при выполнении за то, чего не писали.
 *
 * @param parts части в порядке записи
 * @param span  место в исходнике вместе с кавычками
 */
public record InterpolationExpr(List<Expr> parts, Span span) implements Expr {

    public InterpolationExpr {
        parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "str(", ")");
        for (Expr part : parts) {
            joiner.add(part.toString());
        }
        return joiner.toString();
    }
}
