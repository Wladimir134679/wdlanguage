package ru.wds.wdl.stdlib.thread;

import ru.wds.wdl.embed.NativeInstance;
import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Значение потока в скрипте: то, что вернул {@code th.spawn}.
 * <p>
 * Обычный экземпляр нативного класса — с одной особенностью: три его поля
 * <b>вычисляются на каждом чтении</b>, а не лежат записанными.
 *
 * <h2>Почему вычисляются</h2>
 * {@code t.alive}, {@code t.result} и {@code t.error} описывают то, что меняется
 * без участия читателя: поток работал, потом кончился. Записанное поле показывало бы
 * состояние на момент создания — то есть всегда «работает, результата нет», — и было бы
 * не полем, а ловушкой. Метод ({@code t.alive()}) решал бы это, но читается хуже
 * и заставлял бы помнить, что одно свойство спрашивается со скобками, а другое без.
 * <p>
 * Поэтому чтение перехватывается здесь, а {@code name} и {@code id} остаются обычными
 * полями: они у потока не меняются.
 *
 * <h2>Почему состояние отдельным объектом</h2>
 * Тело потока пишет результат, а само значение потока создаётся <b>после</b> старта:
 * {@code ScriptThreads.start} отдаёт уже запущенный поток. Общее между ними —
 * {@link State}, и оно создаётся первым. Иначе телу пришлось бы ждать публикации
 * своего же значения — лишний барьер там, где хватает одной ссылки.
 */
final class ThreadHandle extends NativeInstance {

    private static final String ALIVE = "alive";
    private static final String RESULT = "result";
    private static final String ERROR = "error";

    /**
     * То, что тело потока и его значение делят между собой.
     * <p>
     * Ошибка хранится строкой, а не значением-исключением: экземпляр ошибки принадлежит
     * запуску, а прочитать {@code t.error} могут из другого потока в любой момент —
     * текст же остаётся верным всегда и печатается там, где его спросили.
     */
    static final class State {

        private final AtomicReference<Value> result = new AtomicReference<>(NullValue.NULL);
        private final AtomicReference<Value> error = new AtomicReference<>(NullValue.NULL);
        /**
         * Ждёт ли кто-нибудь этот поток.
         * <p>
         * По нему решается, печатать ли ошибку потока в вывод запуска: у сджойненного
         * потока ошибку заберут через {@code t.error}, и вторая копия в выводе — шум.
         * У несджойненного не заберёт никто, и молчание означало бы потерянную ошибку —
         * ровно то, чем болели колбэки сокета с их {@code printStackTrace}.
         */
        private final AtomicBoolean awaited = new AtomicBoolean();

        void succeeded(Value value) {
            result.set(value == null ? NullValue.NULL : value);
        }

        void failed(String message) {
            error.set(StringValue.of(message));
        }

        boolean isAwaited() {
            return awaited.get();
        }

        void markAwaited() {
            awaited.set(true);
        }

        /** Ошибка строкой или {@code null}-значение, если её не было. */
        Value errorValue() {
            return error.get();
        }
    }

    private final Thread thread;
    /**
     * Общее с телом потока. Названо {@code shared}, а не {@code state}: слот
     * {@link NativeInstance#state()} уже занят смыслом «Java-объект этого экземпляра»,
     * и два разных состояния под одним именем читались бы как одно.
     */
    private final State shared;

    ThreadHandle(ClassValue owner, Thread thread, State shared) {
        super(owner);
        this.thread = Objects.requireNonNull(thread, "thread");
        this.shared = Objects.requireNonNull(shared, "shared");
    }

    Thread thread() {
        return thread;
    }

    State shared() {
        return shared;
    }

    /** Результат функции потока: {@code null}-значение, пока его нет. */
    Value resultValue() {
        return shared.result.get();
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

    /** Значение вычисляемого поля или {@code null}, если поле обычное. */
    private Value computed(Value key) {
        if (!(key instanceof StringValue name)) {
            return null;
        }
        return switch (name.value()) {
            case ALIVE -> BoolValue.of(thread.isAlive());
            case RESULT -> shared.result.get();
            case ERROR -> shared.error.get();
            default -> null;
        };
    }
}
