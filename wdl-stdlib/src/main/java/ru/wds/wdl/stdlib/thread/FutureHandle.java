package ru.wds.wdl.stdlib.thread;

import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Обещание результата: то, что вернул {@code pool.submit}.
 * <p>
 * Устроено так же, как {@link ThreadHandle}, и по той же причине: {@code f.done}
 * и {@code f.error} меняются без участия читателя, поэтому <b>вычисляются на каждом
 * чтении</b>, а не лежат записанными. Записанное поле показывало бы состояние
 * на момент создания — то есть всегда «не готово».
 * <p>
 * {@code f.result} здесь намеренно нет: результат задачи спрашивается через
 * {@code f.get()}, потому что за ним, в отличие от {@code t.result} у потока, надо
 * <b>ждать</b>. Поле, которое молча отдаёт {@code null}, пока задача считает, — ловушка;
 * метод честно говорит, что это ожидание.
 */
final class FutureHandle extends NativeInstance {

    private static final String DONE = "done";
    private static final String CANCELLED = "cancelled";
    private static final String ERROR = "error";

    private final Future<Value> task;
    private final ThreadHandle.State shared;

    FutureHandle(ClassValue owner, Future<Value> task, ThreadHandle.State shared) {
        super(owner);
        this.task = Objects.requireNonNull(task, "task");
        this.shared = Objects.requireNonNull(shared, "shared");
    }

    Future<Value> task() {
        return task;
    }

    ThreadHandle.State shared() {
        return shared;
    }

    @Override
    public boolean has(Value key) {
        return computed(key) != null || super.has(key);
    }

    @Override
    public Value get(Value key) {
        Value computed = computed(key);
        return computed != null ? computed : super.get(key);
    }

    private Value computed(Value key) {
        if (!(key instanceof StringValue name)) {
            return null;
        }
        return switch (name.value()) {
            case DONE -> BoolValue.of(task.isDone());
            case CANCELLED -> BoolValue.of(task.isCancelled());
            case ERROR -> shared.errorValue();
            default -> null;
        };
    }
}
