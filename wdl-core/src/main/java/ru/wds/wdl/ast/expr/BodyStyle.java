package ru.wds.wdl.ast.expr;

/**
 * Как тело функции было записано в исходнике.
 * <p>
 * Смысла у тела от этого не прибавляется: {@code def f(a) => a + 1} и
 * {@code def f(a) return a + 1;} дают одно и то же дерево — {@link ru.wds.wdl.ast.stmt.ReturnStmt}
 * внутри {@link FunctionExpr}. Форма записи сохраняется ради тех, кому важен исходный
 * текст: форматтер обязан вернуть стрелку туда, где была стрелка, а не переписать
 * её на {@code return}. Тот же мотив, что у {@link AccessStyle}.
 * <p>
 * Блок и одиночная инструкция отдельными значениями не разводятся: их и так видно
 * по дереву — {@code body instanceof BlockStmt}. Перечисление хранит ровно то, что
 * из дерева иначе не восстановить.
 */
public enum BodyStyle {

    /** {@code def f(a) { return a; }} или {@code def f(a) return a;} — тело-инструкция. */
    STATEMENT,
    /** {@code def f(a) => a} — тело-выражение: стрелка вместо {@code return}. */
    ARROW
}
