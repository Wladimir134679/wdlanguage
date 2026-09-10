package ru.wds.wdl.stdlib.streams;

import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.InstanceObjectValue;

/**
 * Состояние потока: конвейер плюс ответ на вопрос, проходили ли его уже.
 * <p>
 * Лежит в {@link NativeInstance#state()} — то самое место для того, что значением
 * языка не выражается.
 *
 * <h2>Поток одноразовый</h2>
 * И промежуточная операция расходует его наравне с терминальной: {@code s.map(f)}
 * не копирует конвейер, а надстраивает его, и после этого у {@code s} собственного
 * входа больше нет. Второе обращение — <b>ошибка скрипта</b>, а не тихий пустой
 * результат: пустой ответ выглядел бы как «данных не было», и искать причину автор
 * пошёл бы в источник, а не в лишнюю строку.
 * <p>
 * Отсюда же и то, что {@link #close()} на пройденном потоке ничего не делает:
 * источником владеет тот, кто его забрал, и он же его закрывает. Иначе
 * {@code use (s = streams.lines(p)) { s.filter(f).list() }} закрывал бы файл дважды —
 * один раз терминальной операцией, второй на выходе из {@code use}.
 */
final class Pipeline {

    /** Конвейер или {@code null}, если его уже забрали. */
    private Source source;

    Pipeline(Source source) {
        this.source = source;
    }

    /**
     * Забирает конвейер: дальше поток пройден.
     * <p>
     * Не {@code synchronized} и не атомарное: поток — значение с ленивым обходом,
     * и делить его между потоками нельзя <b>в принципе</b>, независимо от этого поля.
     * Обещание языка про атомарность одного обращения такого разделения и не покрывает:
     * обход — это много обращений подряд. Кто хочет конкурентности внутри одного
     * конвейера, берёт {@code mapConcurrent}.
     */
    Source take(Span span) {
        Source taken = source;
        if (taken == null) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "поток уже пройден:"
                    + " он читается один раз. Если результат нужен дважды, соберите его:"
                    + " 'items = s.list()'");
        }
        source = null;
        return taken;
    }

    /** Пройден ли поток — то есть забрал ли его кто-то другой. */
    boolean spent() {
        return source == null;
    }

    /**
     * Закрывает конвейер, если он ещё здесь.
     *
     * @return закрывать было что
     */
    boolean close() {
        Source own = source;
        if (own == null) {
            return false;
        }
        source = null;
        own.close();
        return true;
    }

    /**
     * Конвейер за значением-потоком или {@code null}, если это не поток.
     * <p>
     * Забирает его тем же {@link #take}: поток, отданный в {@code flatMap}
     * или в {@code zip}, расходуется ровно так же, как отданный в {@code list}.
     */
    static Source sourceIn(Value value, Span span) {
        if (value instanceof InstanceObjectValue instance
                && instance.identity() instanceof NativeInstance self
                && self.state() instanceof Pipeline pipeline) {
            return pipeline.take(span);
        }
        return null;
    }
}
