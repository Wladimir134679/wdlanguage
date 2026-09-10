package ru.wds.wdl.stdlib.streams;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.runtime.FatalError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.stdlib.Files;
import ru.wds.wdl.stdlib.thread.Threads;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Начала конвейеров: откуда берутся элементы.
 *
 * <h2>Шаг отмечает источник, и только он</h2>
 * Каждый выданный элемент — это шаг работы, и о нём сообщается запуску
 * ({@link CallContext#step(Span)}). Без этого {@code streams.repeat(1).count()}
 * не проходил бы ни одной точки проверки движка, а значит не останавливался бы
 * ни по числу шагов, ни по времени: цикл здесь целиком внутри Java, и вызовов
 * функций скрипта в нём может не быть вовсе.
 * <p>
 * Стадии ({@link Stages}) шаг не отмечают: своего шага у них нет, элемент им дал
 * источник, а обработчики, которые они зовут, считаются сами — вызов функции скрипта
 * проходит через точку проверки в теле функции. Считай стадии шагами, один и тот же
 * обход стоил бы тем дороже, чем длиннее конвейер, — а работу он делает ровно ту же.
 *
 * <h2>Контекст берётся у того, кто источник создал</h2>
 * И этого достаточно: {@code step} ведёт в запуск, счётчики там потоковые, а запуск
 * у контекста один от начала до конца. То есть конвейер, собранный в главном потоке
 * и пройденный внутри {@code th.spawn}, считает свои шаги там, где действительно
 * работает.
 */
final class Sources {

    private Sources() {
    }

    /** Пустой источник: ноль элементов, закрывать нечего. */
    static Source empty() {
        return () -> null;
    }

    /**
     * Готовый список — снимок, снятый на входе.
     * <p>
     * Именно снимок, а не живой массив: поток, собранный сейчас и пройденный через
     * десять строк, иначе отвечал бы на вопрос «какие данные», заданный в другой
     * момент. Ленивость и без того делает порядок работы менее очевидным, чем
     * у массива, и добавлять к ней ещё и подвижный вход незачем. То же решение
     * уже принято для жадных членов массива.
     */
    static Source of(List<Value> snapshot, CallContext context, Span span) {
        return new Source() {

            private int at;

            @Override
            public Value next() {
                if (at >= snapshot.size()) {
                    return null;
                }
                context.step(span);
                return snapshot.get(at++);
            }
        };
    }

    /**
     * Диапазон целых, границы включительно, шаг единица — ровно как в {@code for (i in a..b)}.
     * <p>
     * Пустой диапазон ({@code 5..1}) даёт ноль элементов: это и есть ответ
     * для {@code streams.range(0, n - 1)} при {@code n == 0}.
     */
    static Source range(long from, long to, CallContext context, Span span) {
        return new Source() {

            private long at = from;
            private boolean done = from > to;

            @Override
            public Value next() {
                if (done) {
                    return null;
                }
                context.step(span);
                long current = at;
                // Инкремент на верхней границе типа завернул бы счётчик и сделал
                // источник вечным — тот же случай, что у перебора диапазона.
                if (at == to) {
                    done = true;
                } else {
                    at++;
                }
                return IntValue.of(current);
            }
        };
    }

    /**
     * Бесконечная последовательность: {@code seed}, затем {@code f(предыдущий)}.
     * <p>
     * Конца у неё нет, и это нормально — конец ставит {@code limit}, {@code takeWhile}
     * или короткое замыкание терминальной операции. А от «конца нет вообще» страхует
     * не источник, а лимиты запуска: шаг здесь отмечается на каждом элементе.
     */
    static Source iterate(Value seed, Callback step, CallContext context, Span span) {
        return new Source() {

            private Value current;
            private boolean started;

            @Override
            public Value next() {
                context.step(span);
                if (!started) {
                    started = true;
                    current = seed;
                } else {
                    current = step.call(current);
                }
                return current;
            }
        };
    }

    /** Одно значение без конца: {@code streams.repeat("-").limit(20).join("")}. */
    static Source repeat(Value value, CallContext context, Span span) {
        return () -> {
            context.step(span);
            return value;
        };
    }

    /**
     * Склейка: сначала первый до конца, потом второй.
     * <p>
     * Своего шага здесь нет — элементы выдают склеиваемые источники, они же его
     * и отмечают. Исчерпанное звено закрывается сразу, не дожидаясь конца всей
     * склейки: файл, дочитанный до конца, держать до последнего элемента второго
     * файла незачем.
     */
    static Source concat(List<Source> parts) {
        return new Source() {

            private int at;

            @Override
            public Value next() {
                while (at < parts.size()) {
                    Value value = parts.get(at).next();
                    if (value != null) {
                        return value;
                    }
                    parts.get(at++).close();
                }
                return null;
            }

            @Override
            public void close() {
                Closing.all(parts);
            }
        };
    }

    /**
     * Строки файла, читаемые по мере надобности.
     * <p>
     * То, ради чего ленивый конвейер и заводят: {@code streams.lines(p).find(pred)}
     * читает файл до первой подходящей строки и закрывает его, а не поднимает
     * в память весь — в отличие от жадного {@code io.lines(p)}, который отдаёт массив.
     * <p>
     * Дескриптор закрывается каскадом ({@link Source#close()}) и снимается с учёта
     * через {@code released}: реестр модуля держит открытые источники, чтобы забытые
     * скриптом закрылись вместе с запуском.
     */
    static Source lines(BufferedReader reader, Runnable released, CallContext context, Span span) {
        return new Source() {

            private boolean closed;

            @Override
            public Value next() {
                if (closed) {
                    return null;
                }
                context.step(span);
                String line = Files.io(span, reader::readLine);
                if (line == null) {
                    // Дочитали — закрываем сразу: ждать терминальной операции незачем,
                    // а забытый дескриптор держит файл до конца запуска.
                    close();
                    return null;
                }
                return StringValue.of(line);
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                released.run();
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // Закрытие — последнее, что делает уже отработавший конвейер,
                    // и падать на нём незачем: читать всё равно больше нечего.
                }
            }
        };
    }

    /**
     * Канал {@code th.channel} как источник: продюсер пишет, конвейер читает.
     * <p>
     * Это и есть библиотечный ответ на «а где генераторы»: поток, пишущий в канал,
     * плюс {@code streams.of(channel)} — и ни одной правки в языке. Кончается источник
     * там же, где кончается цикл {@code while ((item = ch.take()) != null)}: канал
     * закрыт и пуст.
     */
    static Source channel(Threads.Taking channel, CallContext context, Span span) {
        return () -> {
            context.step(span);
            try {
                Value value = channel.take();
                return value == null || value == NullValue.NULL ? null : value;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw FatalError.interrupted(span);
            }
        };
    }

    /** Всё, что источник ещё отдаст, списком, — основание барьеров и терминальных операций. */
    static List<Value> drain(Source source) {
        List<Value> items = new ArrayList<>();
        for (Value value = source.next(); value != null; value = source.next()) {
            items.add(value);
        }
        return items;
    }
}
