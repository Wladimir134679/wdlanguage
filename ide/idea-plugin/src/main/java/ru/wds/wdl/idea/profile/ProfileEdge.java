package ru.wds.wdl.idea.profile;

/**
 * Ребро графа вызовов: кто кого звал.
 * <p>
 * Концы — номера записей в {@link ProfileReport#sites()}, а не имена: дерево вызовов
 * собирается без сверки строк, и одноимённые функции из разных файлов не сливаются.
 *
 * @param caller     номер вызывающей записи
 * @param callee     номер вызываемой записи
 * @param calls      сколько раз этот переход случился
 * @param totalNanos сумма времени вызываемого по всем таким переходам
 */
public record ProfileEdge(int caller, int callee, long calls, long totalNanos) {
}
