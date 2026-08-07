package ru.wds.wdl.ast;

/**
 * Инструкция — узел, который выполняют ради действия, а не ради значения.
 * <p>
 * Выражения и инструкции разделены типами, а не соглашением. В прошлой реализации
 * {@code Statement} был пустым маркером поверх {@code Node}, и парсер выяснял вид узла
 * через {@code instanceof} уже после разбора — то есть ошибка «здесь нельзя писать
 * такое» обнаруживалась в рантайме парсера, а не при компиляции самого парсера.
 * <p>
 * Практическое следствие разделения: присваивание — инструкция. Значит,
 * {@code if (x = 5)} не разберётся вообще, а не «сработает как задумано автором,
 * который имел в виду {@code ==}».
 */
public sealed interface Stmt extends Node permits ExprStmt, AssignStmt, ErrorStmt {

    /** Принимает посетителя; диспетчеризация — в {@link StmtVisitor#visit(Stmt, Object)}. */
    default <R, C> R accept(StmtVisitor<R, C> visitor, C context) {
        return visitor.visit(this, context);
    }
}
