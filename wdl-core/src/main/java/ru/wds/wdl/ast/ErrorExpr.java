package ru.wds.wdl.ast;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Заглушка на месте выражения, которое не удалось разобрать.
 * <p>
 * Парсер обязан вернуть дерево всегда — даже для файла с ошибками. Иначе после
 * первой же опечатки нечего показать ни редактору, ни форматтеру, ни второму
 * сообщению об ошибке: разбор просто обрывается. Сама ошибка уже описана
 * в {@link ru.wds.wdl.diagnostic.Diagnostics}, здесь остаётся только дырка нужной
 * формы и её место в тексте.
 * <p>
 * До интерпретатора такой узел доходить не должен: перед выполнением проверяется
 * {@link ru.wds.wdl.diagnostic.Diagnostics#hasErrors()}.
 *
 * @param span место в исходнике
 */
public record ErrorExpr(Span span) implements Expr {

    public ErrorExpr {
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "<ошибка>";
    }
}
