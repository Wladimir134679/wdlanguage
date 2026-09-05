package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.Fragment;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Работа с ресурсом: {@code use (conn = new Connection(host, port)) { ... }}.
 * <p>
 * Что делает {@code use}: вычисляет выражение и связывает имя в своей области —
 * как {@code for (x in ...)}; проверяет, что значение подмешивает {@code Closeable};
 * выполняет тело; зовёт {@code close()} на любом выходе, включая ошибку и остановку
 * выполнения.
 * <p>
 * Ресурсов может быть несколько — через запятую; закрываются они в обратном порядке,
 * потому что правый мог быть взят из левого. <b>Если бросил сам захват</b>, ресурсы,
 * захваченные левее, закрываются, а тело не выполняется вовсе: то же правило
 * «отложено только после успеха», что у {@link DeferStmt}, просто записанное
 * конструкцией.
 * <p>
 * Тело — всегда блок: у {@code use} есть заголовок со списком, и одиночная инструкция
 * после него читалась бы хуже, чем стоит сэкономленная пара скобок.
 */
public record UseStmt(List<Binding> resources, BlockStmt body, Span span) implements Stmt {

    public UseStmt {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(span, "span");
        resources = List.copyOf(resources);
    }

    /**
     * Один ресурс: имя и выражение, которое его даёт.
     * <p>
     * Имя живёт только в теле — как переменная цикла и как имя обработчика
     * в {@code catch}.
     */
    public record Binding(String name, Span nameSpan, Expr value, Span span) implements Fragment {

        public Binding {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }
    }
}
