package ru.wds.wdl.ast;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Разобранный скрипт целиком: список инструкций.
 * <p>
 * Отдельный корневой узел, а не просто {@code List<Stmt>}: у программы есть свой
 * интервал в исходнике, и ей найдётся что хранить дальше — список импортов,
 * объявленные функции, результат резолвера имён. Возвращать из парсера голый список
 * значит однажды переписывать все подписи.
 * <p>
 * Программа — не инструкция и не выражение: вложить её никуда нельзя. Блок
 * {@code { ... }} появится отдельным видом инструкции, когда появятся ветвления.
 *
 * @param statements инструкции в порядке записи
 * @param span       место в исходнике: весь разобранный текст
 */
public record Program(List<Stmt> statements, Span span) implements Node {

    public Program {
        statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
        Objects.requireNonNull(span, "span");
    }

    public boolean isEmpty() {
        return statements.isEmpty();
    }

    @Override
    public String toString() {
        return "программа из " + statements.size() + " инструкций";
    }
}
