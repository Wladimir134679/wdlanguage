package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Decorator;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Объявление, обвешанное декораторами:
 * <pre>
 * &#64;[timer]("ms")
 * &#64;[log]("info")
 * def command(a, b = 10) { ... }
 * </pre>
 * <p>
 * <b>Обёртка вокруг объявления, а не поле в нём.</b> Список декораторов мог бы лежать
 * в {@link DefDeclStmt}, {@link ClassDeclStmt} и {@link TraitDeclStmt} — это три
 * {@code record} в {@code sealed}-иерархии, то есть три правки, задевающие каждого
 * посетителя. Один новый узел дешевле и честнее: декораторы не часть объявления,
 * а действие над тем, что объявление породило.
 * <p>
 * <b>Применяются снизу вверх.</b> Ближайший к {@code def} получает саму цель,
 * следующий — то, что вернул предыдущий, и так до верхнего; под именем оказывается
 * результат верхнего. Отсюда правило записи, которое стоит держать в голове:
 * не оборачивающие декораторы ставятся ближе к {@code def}, чем оборачивающие, —
 * иначе наблюдатель увидит не цель, а чужую анонимную обёртку, и будет прав.
 * <p>
 * <b>Имя заводит эта инструкция, а не вложенная.</b> Вложенное объявление здесь только
 * строит значение; {@code define} происходит один раз и уже декорированным значением.
 * Поэтому {@link #declaration()} и не выполняется посетителем как обычная инструкция.
 *
 * @param decorators  в порядке записи сверху вниз; применяются в обратном
 * @param declaration {@link DefDeclStmt}, {@link ClassDeclStmt} или {@link TraitDeclStmt};
 *                    {@link ErrorStmt}, если объявление не разобралось
 * @param span        от первого {@code @} до конца объявления
 */
public record DecoratedStmt(List<Decorator> decorators, Stmt declaration, Span span) implements Stmt {

    public DecoratedStmt {
        decorators = List.copyOf(Objects.requireNonNull(decorators, "decorators"));
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(span, "span");
        if (decorators.isEmpty()) {
            throw new IllegalArgumentException("декорированное объявление без декораторов: " + span);
        }
    }

    /** Имя, которое заведёт объявление, или {@code null}, если оно не разобралось. */
    public String name() {
        return switch (declaration) {
            case DefDeclStmt declared -> declared.name();
            case ClassDeclStmt declared -> declared.name();
            case TraitDeclStmt declared -> declared.name();
            default -> null;
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        decorators.forEach(decorator -> sb.append(decorator).append(' '));
        return sb.append(declaration).toString();
    }
}
