package ru.wds.wdl.stdlib.bytes;

import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Octets;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.types.BytesValue;

import java.util.Arrays;

/**
 * Место, где байты собирают: растущий или фиксированный накопитель за классом
 * {@code bin.Writer}.
 * <p>
 * Это вторая половина разделения, ради которого {@code bytes} сделан неизменяемым:
 * <b>{@code bytes} — это данные, {@code Writer} — это то, где данные собирают.</b>
 * Ровно как {@code string} и {@code StringBuilder}, и по той же причине — у готовых
 * данных есть ответы на все вопросы типа, а у накопителя с курсором их нет
 * ({@code ==} двух буферов? печать растущего — содержимое, ёмкость или позиция?),
 * поэтому он класс, а не тип.
 *
 * <h2>Почему здесь замки, а у {@code bytes} их нет</h2>
 * Накопитель изменяем и живёт дольше вызова, а инвариант ядра требует, чтобы такое
 * состояние было потокобезопасным. Замок здесь стоит дёшево ровно потому, что
 * запирается не байт, а <b>вызов члена</b>: {@code w.putInt32(x)} — это уже вход
 * в интерпретатор, кадр и проверка лимитов, на фоне которых монитор не виден.
 * Именно этого не вышло бы у изменяемого {@code bytes}, где запирать пришлось бы
 * {@code b[i]}.
 *
 * <h2>Фиксированный и растущий — один класс, разное обещание</h2>
 * {@code bin.writer()} растёт, {@code bin.fixed(n)} — нет и отказывает при переполнении.
 * Разными классами это делать не за что: наружу у них одни и те же имена, разница
 * видна в одной строке отказа, а второй класс принёс бы второй {@code is}
 * и второе место в документации.
 */
final class ByteSink {

    /** Начальная ёмкость растущего накопителя: пакет протокола обычно короче. */
    private static final int INITIAL = 64;

    private byte[] data;
    private int size;
    private boolean little;
    private final boolean growing;

    private ByteSink(byte[] storage, boolean growing) {
        this.data = storage;
        this.growing = growing;
    }

    /** Растущий накопитель. */
    static ByteSink growing() {
        return new ByteSink(new byte[INITIAL], true);
    }

    /** Накопитель ровно на столько байтов и ни байтом больше. */
    static ByteSink fixed(int capacity) {
        return new ByteSink(new byte[capacity], false);
    }

    synchronized int size() {
        return size;
    }

    synchronized int capacity() {
        return growing ? Integer.MAX_VALUE : data.length;
    }

    synchronized boolean littleEndian() {
        return little;
    }

    synchronized void order(boolean littleEndian) {
        this.little = littleEndian;
    }

    /** Снимок написанного; писать в накопитель после него можно дальше. */
    synchronized BytesValue snapshot() {
        return BytesValue.of(data, 0, size);
    }

    /** Забыть написанное, оставив ёмкость: буфер переиспользуют в цикле пакетов. */
    synchronized void clear() {
        size = 0;
    }

    synchronized void put(byte octet, CallContext context, Span span) {
        room(1, context, span);
        data[size++] = octet;
    }

    synchronized void put(byte[] more, CallContext context, Span span) {
        room(more.length, context, span);
        System.arraycopy(more, 0, data, size, more.length);
        size += more.length;
    }

    /** Число заданного вида, в порядке байтов этого накопителя. */
    synchronized void put(Octets.Kind kind, NumberValue value, String what,
                          CallContext context, Span span) {
        room(kind.size(), context, span);
        kind.write(data, size, value, little, what, span);
        size += kind.size();
    }

    /**
     * Освобождает место под ещё {@code more} байтов.
     * <p>
     * Рост — удвоением, и каждый рост отмечается у запуска как выделение
     * ({@code Limits.maxBufferBytes}): иначе цикл {@code w.put(x)} обошёл бы предел
     * выделения, набрав гигабайт по байту. Отметка стоит один раз на удвоение,
     * а не на каждый байт, — то есть логарифм от объёма.
     */
    private void room(int more, CallContext context, Span span) {
        long needed = (long) size + more;
        if (needed <= data.length) {
            return;
        }
        if (!growing) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "буфер полон: в нём "
                    + data.length + " байт, занято " + size + ", а просят ещё " + more
                    + ". Такой буфер заведён как непрерывный ('bin.fixed'); растущий — "
                    + "это 'bin.writer()'");
        }
        if (needed > Integer.MAX_VALUE - 8) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span,
                    "буфер больше не растёт: в нём уже " + size + " байт");
        }
        long grown = Math.max(needed, Math.min((long) data.length * 2, Integer.MAX_VALUE - 8));
        context.allocating(grown, "bin.writer()", span);
        data = Arrays.copyOf(data, (int) grown);
    }
}
