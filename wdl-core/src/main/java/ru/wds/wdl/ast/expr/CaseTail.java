package ru.wds.wdl.ast.expr;

import ru.wds.wdl.ast.Fragment;
import ru.wds.wdl.ast.op.BinaryOp;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Один образец ветки {@code case}: хвост бинарного выражения, подлежащее которого —
 * предмет {@code match}.
 * <p>
 * <b>Вся «система образцов» — это он.</b> {@code case > 90} значит «предмет > 90»,
 * {@code case is Circle} — «предмет is Circle», {@code case in banned} — «предмет
 * in banned», а голый образец {@code case 1} — «предмет == 1» и несёт
 * {@link BinaryOp#EQUAL}. Отдельной {@code sealed}-иерархии {@code Pattern}
 * с ветками «сравнение», «принадлежность», «диапазон», «тип» не появляется,
 * и это главный выигрыш от того, что {@code in}, {@code has} и {@code ..}
 * сделаны обычными операторами до {@code match}, а не внутри него: своя иерархия
 * означала бы вторую реализацию сравнений рядом с {@code runtime.Operations}
 * и неминуемое расхождение с {@code if}.
 * <p>
 * Проверка образца — это буквально {@code Operations.binary(op, предмет, right, span)}.
 *
 * @param op    операция, которой предмет сравнивается с правой частью
 * @param right правая часть; вычисляется лениво — только если до этой ветки дошла очередь
 * @param span  место в исходнике целиком
 */
public record CaseTail(BinaryOp op, Expr right, Span span) implements Fragment {

    public CaseTail {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(span, "span");
    }

    /** Голый образец: {@code case 1} — это «предмет == 1». */
    public boolean isBare() {
        return op == BinaryOp.EQUAL;
    }

    @Override
    public String toString() {
        return op.symbol() + " " + right;
    }
}
