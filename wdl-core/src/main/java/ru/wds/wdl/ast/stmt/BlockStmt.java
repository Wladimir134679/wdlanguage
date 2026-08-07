package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Блок: инструкции в фигурных скобках.
 * <p>
 * Блок — не просто группировка для парсера, а <b>единственное, что создаёт область
 * видимости</b>. Отсюда прямое следствие, которое надо помнить, читая скрипт:
 * {@code if (x) { счёт = 1 }} заводит {@code счёт} только внутри блока, а
 * {@code if (x) счёт = 1} — там, где стоит сам {@code if}. Правило одно и то же
 * («имя заводится там, где ему присвоили впервые»), просто в первом случае «там» —
 * это блок.
 * <p>
 * Не путать с литералом объекта: {@code {a: 1}} — тоже фигурные скобки, но выражение.
 * Разводятся они местом, а не заглядыванием вперёд: в начале инструкции {@code &#123;}
 * всегда блок, в позиции выражения — всегда объект.
 *
 * @param statements инструкции в порядке записи
 * @param span       место в исходнике вместе со скобками
 */
public record BlockStmt(List<Stmt> statements, Span span) implements Stmt {

    public BlockStmt {
        statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
        Objects.requireNonNull(span, "span");
    }

    public boolean isEmpty() {
        return statements.isEmpty();
    }

    @Override
    public String toString() {
        return "{ инструкций: " + statements.size() + " }";
    }
}
