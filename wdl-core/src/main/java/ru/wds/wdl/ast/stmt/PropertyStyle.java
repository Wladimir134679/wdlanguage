package ru.wds.wdl.ast.stmt;

/**
 * Как записано свойство: короткой стрелкой или блоком аксессоров.
 * <p>
 * Ровно та же роль, что у {@link ru.wds.wdl.ast.expr.AccessStyle} и
 * {@link ru.wds.wdl.ast.expr.BodyStyle}: дальше по конвейеру две формы неразличимы,
 * а помнить написанное нужно форматтеру и текстам диагностики. Разворачивать
 * {@code property area => ...} в блок с одним {@code def get()} и терять форму
 * записи было бы той же ошибкой, что разворачивать {@code a.x} в {@code a["x"]}
 * без пометки.
 */
public enum PropertyStyle {

    /** {@code property area => this.w * this.h} — только чтение, тело выражением. */
    ARROW,

    /** {@code property size { def get() ... def set(value) ... }}. */
    BLOCK
}
