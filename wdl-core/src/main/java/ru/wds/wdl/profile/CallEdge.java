package ru.wds.wdl.profile;

import java.time.Duration;

import java.util.Objects;

/**
 * Ребро графа вызовов: кто кого звал, сколько раз и сколько на это ушло.
 * <p>
 * Граф, а не дерево, и это осознанно: дерево вызовов растёт вместе с запуском —
 * миллион итераций дал бы миллион узлов, — а рёбер ровно столько, сколько в коде
 * пар «вызывающий — вызываемый». Приложению этого хватает на оба вопроса, которые
 * задают профилю: «кого зовёт эта функция» и «кто виноват в её времени» — второй
 * читается по тем же рёбрам с другой стороны.
 * <p>
 * У вызова с верхнего уровня файла вызывающим стоит запись вида
 * {@link CallKind#SCRIPT} — корень у графа есть всегда, поэтому {@code caller}
 * не бывает {@code null}.
 *
 * @param caller     кто звал
 * @param callee     кого звали
 * @param calls      сколько раз
 * @param totalNanos сколько времени эти вызовы заняли вместе со своими вложенными
 */
public record CallEdge(CallSite caller, CallSite callee, long calls, long totalNanos) {

    public CallEdge {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(callee, "callee");
    }

    public Duration total() {
        return Duration.ofNanos(totalNanos);
    }
}
