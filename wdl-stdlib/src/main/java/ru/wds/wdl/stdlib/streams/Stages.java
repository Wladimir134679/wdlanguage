package ru.wds.wdl.stdlib.streams;

import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Overloading;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Промежуточные звенья: те, что отдают поток и работы пока не делают.
 * <p>
 * Каждое из них — декоратор: держит предыдущее звено, зовёт его {@link Source#next()}
 * и закрывает его в своём {@link Source#close()}. Отсюда и главное свойство конвейера:
 * {@code lines(p).map(f).filter(g).first()} читает файл до первой подходящей строки,
 * а не до конца — потому что {@code first} спросил один элемент, и каждое звено
 * спросило один у предыдущего.
 *
 * <h2>Шага у стадии нет</h2>
 * {@link CallContext#step(Span)} зовёт только источник ({@link Sources}). Стадия
 * элемент не создаёт, она его пропускает через себя, а обработчик, который она зовёт,
 * считается сам — вызов функции скрипта проходит точку проверки в теле функции.
 *
 * <h2>Барьер — это честно названное свойство, а не дефект</h2>
 * {@link #sorted} обязан прочитать вход целиком: пока не увиден последний элемент,
 * неизвестен первый. На бесконечном источнике он поэтому не кончится никогда,
 * и написано это здесь, рядом с ним, а не в разделе «ограничения» в конце документа.
 * {@code distinct}, в отличие от него, барьером <b>не является</b>: ему довольно
 * помнить уже выданное, и {@code iterate(...).distinct().limit(5)} работает.
 */
final class Stages {

    private Stages() {
    }

    /** Преобразование каждого элемента. */
    static Source map(Source up, Callback transform) {
        return new Decorator(up) {

            @Override
            public Value next() {
                Value value = up.next();
                return value == null ? null : transform.call(value);
            }
        };
    }

    /** Отбор: пропускается то, на чём условие истинно — та же истинность, что у {@code if}. */
    static Source filter(Source up, Callback predicate) {
        return new Decorator(up) {

            @Override
            public Value next() {
                for (Value value = up.next(); value != null; value = up.next()) {
                    if (predicate.call(value).isTruthy()) {
                        return value;
                    }
                }
                return null;
            }
        };
    }

    /**
     * Подглядывание: элемент проходит насквозь, обработчик зовётся по дороге.
     * <p>
     * Ленивость видна здесь лучше всего: {@code s.peek(println).limit(2).list()}
     * напечатает <b>две</b> строки, а не все — обработчик зовётся тогда, когда
     * элемент кому-то понадобился.
     */
    static Source peek(Source up, Callback watcher) {
        return new Decorator(up) {

            @Override
            public Value next() {
                Value value = up.next();
                if (value != null) {
                    watcher.call(value);
                }
                return value;
            }
        };
    }

    /**
     * Разворачивание: обработчик отдаёт массив или другой поток, элементы идут подряд.
     * <p>
     * Вложенное звено закрывается, как только исчерпано: {@code flatMap} поверх
     * сотни файлов не должен держать сотню открытых дескрипторов.
     */
    static Source flatMap(Source up, Callback transform, Span span) {
        return new Decorator(up) {

            private Source inner;

            @Override
            public Value next() {
                for (;;) {
                    if (inner != null) {
                        Value value = inner.next();
                        if (value != null) {
                            return value;
                        }
                        inner.close();
                        inner = null;
                    }
                    Value outer = up.next();
                    if (outer == null) {
                        return null;
                    }
                    inner = nested(transform.call(outer), span);
                }
            }

            @Override
            public void close() {
                if (inner != null) {
                    inner.close();
                    inner = null;
                }
                up.close();
            }
        };
    }

    /** Первые {@code count} элементов; дальше вход не спрашивается вовсе. */
    static Source limit(Source up, long count) {
        return new Decorator(up) {

            private long left = count;

            @Override
            public Value next() {
                if (left <= 0) {
                    return null;
                }
                Value value = up.next();
                if (value == null) {
                    left = 0;
                    return null;
                }
                left--;
                return value;
            }
        };
    }

    /** Пропуск первых {@code count} элементов. */
    static Source skip(Source up, long count) {
        return new Decorator(up) {

            private long left = count;

            @Override
            public Value next() {
                while (left > 0) {
                    if (up.next() == null) {
                        left = 0;
                        return null;
                    }
                    left--;
                }
                return up.next();
            }
        };
    }

    /**
     * Пока условие истинно.
     * <p>
     * Первое же ложное кончает поток и вход больше не спрашивается: этим
     * {@code takeWhile} и отличается от {@code filter}, который пропускает
     * несовпавшее и идёт дальше.
     */
    static Source takeWhile(Source up, Callback predicate) {
        return new Decorator(up) {

            private boolean done;

            @Override
            public Value next() {
                if (done) {
                    return null;
                }
                Value value = up.next();
                if (value == null || !predicate.call(value).isTruthy()) {
                    done = true;
                    return null;
                }
                return value;
            }
        };
    }

    /** Пропуск, пока условие истинно; после первого ложного условие больше не спрашивается. */
    static Source dropWhile(Source up, Callback predicate) {
        return new Decorator(up) {

            private boolean dropping = true;

            @Override
            public Value next() {
                if (!dropping) {
                    return up.next();
                }
                for (Value value = up.next(); value != null; value = up.next()) {
                    if (!predicate.call(value).isTruthy()) {
                        dropping = false;
                        return value;
                    }
                }
                dropping = false;
                return null;
            }
        };
    }

    /**
     * Попарная склейка двух потоков: {@code [a, b]} на каждый шаг.
     * <p>
     * То самое, ради чего модель здесь тянущая, а не толкающая: в push-модели
     * {@code zip} не выражается — оттого его в Java до сих пор и нет. Кончается
     * там, где кончился <b>любой</b> из двух: пары без второй половины не бывает.
     */
    static Source zip(Source left, Source right) {
        return new Source() {

            @Override
            public Value next() {
                Value first = left.next();
                if (first == null) {
                    return null;
                }
                Value second = right.next();
                return second == null ? null : ArrayValue.of(first, second);
            }

            @Override
            public void close() {
                Closing.all(List.of(left, right));
            }
        };
    }

    /**
     * Куски по {@code size} элементов; последний кусок бывает короче.
     * <p>
     * Короче, а не выброшен: «последние три записи из тысячи» — это данные,
     * а не остаток от деления.
     */
    static Source chunked(Source up, int size) {
        return new Decorator(up) {

            @Override
            public Value next() {
                List<Value> chunk = new ArrayList<>(size);
                for (Value value = up.next(); value != null; value = up.next()) {
                    chunk.add(value);
                    if (chunk.size() == size) {
                        return ArrayValue.of(chunk);
                    }
                }
                return chunk.isEmpty() ? null : ArrayValue.of(chunk);
            }
        };
    }

    /**
     * Скользящее окно из {@code size} элементов, шаг — один.
     * <p>
     * Неполного окна не бывает: у входа короче окна ответ — ни одного, а не
     * укороченное. {@code windowed} спрашивают, чтобы сравнить соседей
     * ({@code w[1] - w[0]}), и окно не того размера сломало бы именно это.
     */
    static Source windowed(Source up, int size) {
        return new Decorator(up) {

            private final Deque<Value> window = new ArrayDeque<>(size);
            private boolean done;

            @Override
            public Value next() {
                if (done) {
                    return null;
                }
                while (window.size() < size) {
                    Value value = up.next();
                    if (value == null) {
                        done = true;
                        return null;
                    }
                    window.addLast(value);
                }
                ArrayValue snapshot = ArrayValue.of(new ArrayList<>(window));
                window.removeFirst();
                return snapshot;
            }
        };
    }

    /**
     * Без повторов — и <b>без барьера</b>: помнится выданное, вход читается по мере
     * надобности, поэтому {@code iterate(...).distinct().limit(5)} кончается.
     * <p>
     * Сравнение — то же, что стоит за {@code a == b}, вместе с {@code def `==`}
     * у класса. Отсюда и список вместо хеш-множества: хеш у значения свой, а равенство
     * скрипт вправе переопределить, и разойдись они — {@code distinct} начал бы врать
     * ровно на тех значениях, ради которых оператор и переопределяли. Плата за это —
     * сравнение с каждым уже выданным; на потоке, где различного мало, это ничего
     * не стоит, а на потоке, где различного много, память под них уходит всё равно.
     */
    static Source distinct(Source up, CallContext context, Span span) {
        return new Decorator(up) {

            private final List<Value> seen = new ArrayList<>();

            @Override
            public Value next() {
                for (Value value = up.next(); value != null; value = up.next()) {
                    if (!known(value)) {
                        seen.add(value);
                        return value;
                    }
                }
                return null;
            }

            private boolean known(Value candidate) {
                for (Value value : seen) {
                    if (Overloading.equal(value, candidate, span, context)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * Упорядочивание — <b>барьер</b>: вход читается целиком при первом же обращении,
     * и на бесконечном источнике эта стадия не кончится никогда.
     * <p>
     * Порядок берётся у той же цепочки, что стоит за {@code a < b}: ядро, потом член
     * {@code `<=>`} у класса. Заведи стадия своё сравнение, она разошлась бы
     * с оператором на первом же потоке экземпляров — то же правило, по которому
     * у {@code a.sorted} своего сравнения нет.
     *
     * @param key чем сравнивать элементы вместо них самих ({@code sorted(p => p.price)}),
     *            или {@code null} — сравнивать сами элементы
     */
    static Source sorted(Source up, Callback key, CallContext context, Span span) {
        return new Decorator(up) {

            private List<Value> ordered;
            private int at;

            @Override
            public Value next() {
                if (ordered == null) {
                    ordered = collect();
                }
                return at < ordered.size() ? ordered.get(at++) : null;
            }

            private List<Value> collect() {
                List<Value> items = Sources.drain(up);
                if (key == null) {
                    items.sort((left, right) -> Overloading.order(left, right, span, context));
                    return items;
                }
                // Ключ считается один раз на элемент, а не на каждое сравнение:
                // 'sorted(p => heavy(p))' иначе звал бы heavy порядка n·log(n) раз.
                List<Value[]> keyed = new ArrayList<>(items.size());
                for (Value item : items) {
                    keyed.add(new Value[] {key.call(item), item});
                }
                keyed.sort((left, right) -> Overloading.order(left[0], right[0], span, context));
                List<Value> result = new ArrayList<>(keyed.size());
                for (Value[] pair : keyed) {
                    result.add(pair[1]);
                }
                return result;
            }
        };
    }

    /**
     * Во что разворачивается элемент у {@code flatMap}.
     * <p>
     * Массив и поток, и ничего больше: строка и объект тоже перебираемы, но
     * «развернуть строку» почти всегда значит опечатку, а не замысел, — и молча
     * рассыпать её на буквы хуже, чем сказать об этом.
     */
    private static Source nested(Value value, Span span) {
        if (value instanceof ArrayValue array) {
            return listSource(array.items());
        }
        Source pipeline = Pipeline.sourceIn(value, span);
        if (pipeline != null) {
            return pipeline;
        }
        throw new WdlRuntimeError(ErrorKind.TYPE, span, "flatMap(): преобразование обязано"
                + " вернуть массив или поток, а вернуло " + value.type().title()
                + " (" + value.display() + ")");
    }

    /** Готовый список звеном: шага здесь нет — его отметил источник, давший элемент. */
    private static Source listSource(List<Value> items) {
        return new Source() {

            private int at;

            @Override
            public Value next() {
                return at < items.size() ? items.get(at++) : null;
            }
        };
    }

    /**
     * Общее у всех стадий: закрытие идёт вверх по конвейеру.
     * <p>
     * Отдельным классом, а не строкой в каждой стадии, потому что забыть её — значит
     * оставить открытый файл, и заметить это по коду стадии нельзя: она про элементы,
     * а не про ресурсы.
     */
    private abstract static class Decorator implements Source {

        private final Source up;

        Decorator(Source up) {
            this.up = up;
        }

        @Override
        public void close() {
            up.close();
        }
    }
}
