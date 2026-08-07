package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Цикл со счётчиком: {@code for (i = 0; i < 10; i += 1) ...}.
 * <p>
 * Все три части необязательны, и пропущенная часть — это {@code null}, а не подставленная
 * заглушка. {@code for (;;) ...} — вечный цикл, и в дереве у него нет ни синтетического
 * литерала {@code true}, ни пустой инструкции: чего в тексте нет, того нет и в дереве.
 * Инструментам это важнее, чем интерпретатору, — форматтер обязан напечатать ровно то,
 * что человек написал.
 * <p>
 * Инициализатор и шаг — {@link Stmt}, потому что в языке присваивание не выражение.
 * Заодно это ровно та проверка, которая и нужна: в шаге допустимы присваивание и вызов,
 * то есть только то, что имеет эффект.
 * <p>
 * Область видимости у цикла своя: имя, заведённое в инициализаторе, снаружи не видно
 * и не мешает следующему циклу с таким же {@code i}.
 *
 * @param init      инициализатор или {@code null}
 * @param condition условие продолжения или {@code null} — тогда цикл вечный
 * @param step      шаг, выполняемый после каждого прохода, или {@code null}
 * @param body      тело цикла
 * @param span      место в исходнике целиком
 */
public record ForStmt(Stmt init, Expr condition, Stmt step, Stmt body, Span span) implements Stmt {

    public ForStmt {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(span, "span");
        // init, condition и step могут отсутствовать — см. описание выше.
    }

    @Override
    public String toString() {
        return "for (" + text(init) + "; " + text(condition) + "; " + text(step) + ") ...";
    }

    private static String text(Object part) {
        return part == null ? "" : part.toString();
    }
}
