package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Возврат из функции: {@code return a + b;} или {@code return;}.
 * <p>
 * <b>Точка с запятой обязательна.</b> Это второе место в языке после заголовка
 * {@code for}, где {@code ;} не разделитель, а часть синтаксиса, и причина та же,
 * что у обязательных скобок вокруг условия {@code if}: перевод строки токена
 * не даёт, поэтому без терминатора {@code return} и следующая за ним строка
 * неразличимы. {@code return} с новой строки, за которым идёт вызов, — законное
 * возвращение результата вызова и законная отдельная инструкция одновременно;
 * никакое заглядывание вперёд этого не разводит, а тихо выбранное толкование —
 * ровно тот класс ошибок, которого язык избегает по построению.
 * <p>
 * Отсутствующее значение — это {@code null}, а не подставленный литерал {@code null}:
 * чего нет в тексте, того нет и в дереве. Интерпретатор читает его как «вернуть
 * ничего», а форматтер печатает то, что человек написал.
 *
 * @param value возвращаемое выражение или {@code null} у {@code return;}
 * @param span  место в исходнике вместе с ключевым словом и точкой с запятой
 */
public record ReturnStmt(Expr value, Span span) implements Stmt {

    public ReturnStmt {
        Objects.requireNonNull(span, "span");
    }

    public boolean hasValue() {
        return value != null;
    }

    @Override
    public String toString() {
        return value != null ? "return " + value + ";" : "return;";
    }
}
