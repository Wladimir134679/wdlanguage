package ru.wds.wdl.stdlib.bytes;

import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Octets;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BytesValue;

/**
 * Чтение готовых байтов по порядку: позиция за классом {@code bin.Reader}.
 * <p>
 * Нужен ровно затем, зачем нужен курсор вообще: разбор формата — это
 * «прочитать четыре байта, потом два, потом строку такой длины», и считать смещение
 * руками между вызовами {@code data.int32(at)} значит один раз ошибиться и искать
 * это полдня. Сами байты при этом никуда не деваются и остаются неизменными —
 * курсор держит только позицию.
 * <p>
 * <b>Класс, а не тип</b> — по тому же признаку, что и {@link ByteSink}: у позиции
 * в потоке нет ответов на вопросы типа. Что такое {@code ==} двух курсоров по одним
 * байтам, но в разных местах? Что печатает {@code println} курсора?
 * <p>
 * Замки — тоже как у накопителя, и по той же причине: изменяемое состояние запуска
 * обязано быть потокобезопасным, а запирается вызов члена, а не байт.
 */
final class ByteCursor {

    private final BytesValue data;
    private int at;
    private boolean little;

    ByteCursor(BytesValue data) {
        this.data = data;
    }

    BytesValue data() {
        return data;
    }

    synchronized int position() {
        return at;
    }

    synchronized int remaining() {
        return data.size() - at;
    }

    synchronized void order(boolean littleEndian) {
        this.little = littleEndian;
    }

    synchronized boolean littleEndian() {
        return little;
    }

    /**
     * Ставит курсор на эту позицию.
     * <p>
     * Строго в границах, включая сам конец: {@code r.seek(data.size)} — это
     * «в конец», законное место, с которого читать уже нечего.
     */
    synchronized void seek(int position, Span span) {
        if (position < 0 || position > data.size()) {
            throw new WdlRuntimeError(ErrorKind.INDEX, span, "bin.Reader.seek(): позиция "
                    + position + " вне байтов размером " + data.size());
        }
        at = position;
    }

    /** Число заданного вида; курсор сдвигается на его размер. */
    synchronized Value take(Octets.Kind kind, Span span) {
        int from = need(kind.size(), kind.id(), span);
        return kind.read(data, from, little);
    }

    /** Кусок байтов; курсор сдвигается на его длину. */
    synchronized BytesValue take(int count, String what, Span span) {
        int from = need(count, what, span);
        return data.slice(from, from + count);
    }

    /** Пропустить столько байтов, не читая. */
    synchronized void skip(int count, Span span) {
        need(count, "skip()", span);
    }

    /**
     * Сдвигает курсор, проверив, что запрошенное помещается, и отдаёт прежнюю позицию.
     * <p>
     * Отказ, а не «сколько было»: конец данных посреди разбора формата — это почти
     * всегда обрезанный файл или сдвиг на байт выше по коду, и промолчать значило бы
     * отдать наверх правдоподобный мусор. Прочитать «сколько есть» можно осознанно —
     * через {@code remaining}.
     */
    private int need(int count, String what, Span span) {
        if (count < 0) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "bin.Reader." + what
                    + ": длина не может быть отрицательной: " + count);
        }
        if (at + count > data.size()) {
            throw new WdlRuntimeError(ErrorKind.INDEX, span, "bin.Reader." + what
                    + ": нужно " + count + " байт, а от позиции " + at + " осталось "
                    + (data.size() - at));
        }
        int from = at;
        at += count;
        return from;
    }
}
