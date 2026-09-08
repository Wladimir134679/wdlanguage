package ru.wds.wdl.debug;

import java.util.List;
import java.util.Objects;

/**
 * Поток встал: кто, почему и где.
 * <p>
 * Кадры лежат прямо в событии, а не достаются вторым запросом, и это не удобство:
 * между «поток встал» и «покажи кадры» он мог бы уже уйти — например, если хозяин
 * сессии успел возобновить его сам. Снимок, сделанный в момент остановки, отвечает
 * на вопрос, который в этот момент и задан.
 * <p>
 * Первый кадр списка — самый глубокий, тот, в котором поток стоит; последний —
 * верхний уровень файла. Порядок тот же, что у трассировки ошибки, и по той же
 * причине: читают такие списки сверху вниз, начиная с места события.
 *
 * @param thread кто встал
 * @param reason почему
 * @param frames кадры от текущего к верхнему уровню
 */
public record SuspendedEvent(ThreadInfo thread, StopReason reason, List<DebugFrame> frames) {

    public SuspendedEvent {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(reason, "reason");
        frames = List.copyOf(frames);
    }

    /** Кадр, в котором поток стоит, или {@code null}, если кадров нет вовсе. */
    public DebugFrame top() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    @Override
    public String toString() {
        return thread.name() + ": " + reason.title() + (top() == null ? "" : " в " + top());
    }
}
