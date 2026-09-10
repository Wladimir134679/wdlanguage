package ru.wds.wdl.stdlib.streams;

import ru.wds.wdl.ast.op.BinaryOp;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Overloading;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.StringJoiner;

/**
 * Терминальные операции: здесь и происходит вся работа конвейера.
 * <p>
 * До них ни один элемент не прочитан: {@code map} и {@code filter} только надстроили
 * звенья. Терминальная операция тянет — и тянет ровно столько, сколько ей нужно:
 * {@code first} один элемент, {@code find} до первого совпадения, {@code list} до конца.
 *
 * <h2>Закрывает та операция, что обходила</h2>
 * И закрывает из {@code finally}, чем бы обход ни кончился: иначе
 * {@code streams.lines(p).first()} оставил бы открытый файл — а именно ради этой
 * записи ленивый конвейер и заводят. Ошибка обработчика при этом наружу проходит:
 * закрыть своё на пути наружу можно, подавить причину — нет.
 *
 * <h2>Ответы у пустого потока — те же, что у пустого массива</h2>
 * {@code count} — ноль, {@code sum} — ноль, {@code all} — истина, {@code any} — ложь,
 * {@code min}, {@code max}, {@code first}, {@code find} — {@code null}. Второго набора
 * договорённостей про пустоту в языке нет и заводить его незачем.
 */
final class Terminals {

    private Terminals() {
    }

    /** Собрать всё в массив: {@code s.list()}. */
    static Value list(Source source) {
        try {
            return ArrayValue.of(Sources.drain(source));
        } finally {
            source.close();
        }
    }

    /**
     * Собрать в объект: {@code s.object(p => p.id, p => p.name)}.
     * <p>
     * Совпавший ключ побеждает последним — как и обычное присваивание {@code o[k] = v}.
     * Отдельного разговора про «а что если ключи совпали» здесь поэтому нет: правило
     * то же, что у объекта, и второго знать не надо.
     */
    static Value object(Source source, Callback byKey, Callback byValue) {
        try {
            MapValue result = new MapValue();
            for (Value item = source.next(); item != null; item = source.next()) {
                result.put(byKey.call(item), byValue.call(item));
            }
            return result;
        } finally {
            source.close();
        }
    }

    /**
     * Обойти, ничего не собирая.
     * <p>
     * Отдаёт число пройденных элементов, а не сам поток: поток пройден, и возвращать
     * его значило бы отдать значение, любое действие над которым — ошибка. У массива
     * {@code each} отдаёт сам массив по обратной причине: массив после обхода жив.
     */
    static Value each(Source source, Callback handler) {
        try {
            long seen = 0;
            for (Value item = source.next(); item != null; item = source.next()) {
                handler.call(item);
                seen++;
            }
            return IntValue.of(seen);
        } finally {
            source.close();
        }
    }

    /**
     * Свёртка. Начальное значение необязательно — без него первым становится первый
     * элемент, как у массива.
     *
     * @param seeded передано ли начальное значение; именно «передано ли», а не
     *               «не {@code null} ли оно»: {@code reduce(null, f)} — законное начало
     */
    static Value reduce(Source source, Value seed, boolean seeded, Callback combine, Span span) {
        try {
            Value accumulator = seed;
            if (!seeded) {
                accumulator = source.next();
                if (accumulator == null) {
                    throw new WdlRuntimeError(ErrorKind.VALUE, span, "reduce() у пустого потока"
                            + " без начального значения: сворачивать нечего —"
                            + " передайте его вторым аргументом");
                }
            }
            for (Value item = source.next(); item != null; item = source.next()) {
                accumulator = combine.call(accumulator, item);
            }
            return accumulator;
        } finally {
            source.close();
        }
    }

    /** Сколько элементов прошло. */
    static Value count(Source source) {
        try {
            long total = 0;
            while (source.next() != null) {
                total++;
            }
            return IntValue.of(total);
        } finally {
            source.close();
        }
    }

    /**
     * Сумма тем же {@code +}, который написан в тексте скрипта: числа складываются,
     * строки склеиваются, класс с {@code def `+`} — своим сложением.
     * <p>
     * Отдельного сложения «только для чисел» здесь нет по той же причине, по какой
     * у сортировки нет своего сравнения: разойдясь с оператором, операция начала бы
     * врать на первом же потоке экземпляров.
     */
    static Value sum(Source source, CallContext context, Span span) {
        try {
            Value total = source.next();
            if (total == null) {
                return IntValue.ZERO;
            }
            for (Value item = source.next(); item != null; item = source.next()) {
                total = Overloading.binary(BinaryOp.ADD, total, item, span, context);
            }
            return total;
        } finally {
            source.close();
        }
    }

    /**
     * Наименьший или наибольший — тем же порядком, что стоит за {@code a < b}.
     *
     * @param direction {@code -1} для наименьшего, {@code 1} для наибольшего
     */
    static Value extreme(Source source, int direction, CallContext context, Span span) {
        try {
            Value best = source.next();
            if (best == null) {
                return NullValue.NULL;
            }
            for (Value item = source.next(); item != null; item = source.next()) {
                if (Integer.signum(Overloading.order(item, best, span, context)) == direction) {
                    best = item;
                }
            }
            return best;
        } finally {
            source.close();
        }
    }

    /**
     * Первый элемент — и ни одного лишнего.
     * <p>
     * Ради этой строки конвейер и ленивый: {@code streams.lines(log).map(parse).first()}
     * разбирает одну строку, а не весь файл.
     */
    static Value first(Source source) {
        try {
            Value value = source.next();
            return value == null ? NullValue.NULL : value;
        } finally {
            source.close();
        }
    }

    /** Первый подходящий или {@code null}; дальше вход не читается. */
    static Value find(Source source, Callback predicate) {
        try {
            for (Value item = source.next(); item != null; item = source.next()) {
                if (predicate.call(item).isTruthy()) {
                    return item;
                }
            }
            return NullValue.NULL;
        } finally {
            source.close();
        }
    }

    /**
     * Есть ли хоть один подходящий; на первом же совпадении обход кончается.
     *
     * @param predicate условие или {@code null} — тогда спрашивается истинность самого
     *                  элемента, тот же вопрос, что задаёт {@code if (item)}
     */
    static Value any(Source source, Callback predicate) {
        try {
            for (Value item = source.next(); item != null; item = source.next()) {
                if (holds(predicate, item)) {
                    return BoolValue.TRUE;
                }
            }
            return BoolValue.FALSE;
        } finally {
            source.close();
        }
    }

    /** Все ли подходят; на первом же несовпадении обход кончается. У пустого — истина. */
    static Value all(Source source, Callback predicate) {
        try {
            for (Value item = source.next(); item != null; item = source.next()) {
                if (!holds(predicate, item)) {
                    return BoolValue.FALSE;
                }
            }
            return BoolValue.TRUE;
        } finally {
            source.close();
        }
    }

    /** Ни одного подходящего. У пустого — истина. */
    static Value none(Source source, Callback predicate) {
        try {
            for (Value item = source.next(); item != null; item = source.next()) {
                if (holds(predicate, item)) {
                    return BoolValue.FALSE;
                }
            }
            return BoolValue.TRUE;
        } finally {
            source.close();
        }
    }

    /** Склейка в строку тем же {@code display()}, каким печатает {@code println}. */
    static Value join(Source source, String separator) {
        try {
            StringJoiner joiner = new StringJoiner(separator);
            for (Value item = source.next(); item != null; item = source.next()) {
                joiner.add(item.display());
            }
            return StringValue.of(joiner.toString());
        } finally {
            source.close();
        }
    }

    /** Истинность элемента: по условию, если оно есть, и по нему самому, если нет. */
    private static boolean holds(Callback predicate, Value item) {
        return predicate == null ? item.isTruthy() : predicate.call(item).isTruthy();
    }
}
