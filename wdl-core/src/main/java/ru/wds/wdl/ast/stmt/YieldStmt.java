package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Значение ветки {@code case}: {@code yield price * count}.
 * <p>
 * <b>Слово, а не «последнее выражение блока — результат».</b> Неявный результат
 * читатель обязан вычислить сам — пробежать блок до конца и вспомнить правило, — и
 * ошибку в таком чтении не видно: добавили строку после результата, и ветка молча
 * стала отдавать другое. {@code yield} стоит ровно там, где значение отдают.
 * <p>
 * <b>Значение обязательно, поэтому точка с запятой не нужна</b> — в отличие
 * от {@link ReturnStmt}. Там {@code ;} обязателен потому, что значение можно
 * опустить, и {@code return} с новой строкой неотличим от {@code return выражение};
 * здесь опустить нечего, и следующая инструкция отделяется однозначно. Тот же довод,
 * что у {@link ThrowStmt} и у стрелочного тела функции.
 * <p>
 * Где {@code yield} допустим, знает парсер: только в теле ветки {@code case}
 * у {@code match} в позиции выражения, и не через границу функции. Это та же линия,
 * что у {@code break} вне цикла, — ошибка разбора, а не выполнения.
 *
 * @param value отдаваемое выражение; никогда не {@code null}
 * @param span  место в исходнике вместе с ключевым словом
 */
public record YieldStmt(Expr value, Span span) implements Stmt {

    public YieldStmt {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return "yield " + value;
    }
}
