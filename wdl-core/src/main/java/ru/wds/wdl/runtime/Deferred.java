package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.stmt.Stmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Отложенные действия одной области видимости — то, что выполнится на выходе из неё.
 * <p>
 * Изменяемый список, и это не оговорка к правилу «контекст неизменяем»: у области
 * есть время жизни, и список принадлежит ей, а не контексту. Контекст держит на него
 * ссылку ровно так же, как держит реестр модулей, — общий для всех вложенных контекстов
 * одной области.
 * <p>
 * Заводится только там, где отложенное действительно есть: {@code BlockStmt.hasDefer()}
 * знает об этом с разбора, а блоков без {@code defer} в скрипте подавляющее большинство.
 * <p>
 * Действие помнит и своё тело, и контекст, в котором его записали: {@code defer}
 * выполняется в той же области, где написан, и видит те же имена — иначе
 * {@code f = open(...); defer f.close()} не нашла бы {@code f}.
 */
final class Deferred {

    /** Тело и область, в которой его записали. */
    record Action(Stmt body, ExecutionContext context) {
    }

    private final List<Action> actions = new ArrayList<>(2);

    void add(Stmt body, ExecutionContext context) {
        actions.add(new Action(body, context));
    }

    boolean isEmpty() {
        return actions.isEmpty();
    }

    /**
     * Действия в порядке выполнения — <b>обратном</b> порядку записи.
     * <p>
     * Иначе нельзя было бы освобождать ресурсы, зависящие друг от друга: соединение
     * закрывается раньше пула, из которого взято, а не после него.
     */
    List<Action> inRunOrder() {
        List<Action> order = new ArrayList<>(actions);
        Collections.reverse(order);
        return order;
    }
}
