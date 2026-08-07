package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Обращение к имени: {@code счётчик}, {@code println}, {@code Точка}.
 * <p>
 * Узел хранит только имя. Куда это имя разрешится — в локальную переменную,
 * в аргумент функции или в глобальную — решает окружение во время выполнения,
 * а позже решит отдельный проход-резолвер, который проставит готовые номера слотов.
 * Дерево от этой замены не изменится: в нём по-прежнему будет просто имя.
 * <p>
 * Отдельного узла для функции нет и не будет: пространство имён в языке одно,
 * функция — обычное значение в обычной переменной.
 *
 * @param name имя
 * @param span место в исходнике
 */
public record VariableExpr(String name, Span span) implements Expr {

    public VariableExpr {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return name;
    }
}
