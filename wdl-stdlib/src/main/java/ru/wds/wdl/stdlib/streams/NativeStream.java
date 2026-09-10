package ru.wds.wdl.stdlib.streams;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.Callback;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BoolValue;

/**
 * Класс {@code Stream}: ленивый конвейер как значение языка.
 * <p>
 * Для интерпретатора это обычный экземпляр нативного класса: {@code s is streams.Stream}
 * работает, {@code use} работает, {@code println} печатает как экземпляр. Нового типа
 * значения ради потока не заводится — у ленивого значения честно нет ответов
 * на вопросы, которые тип обязан иметь: что печатает {@code println(s)} (обойти
 * источник ради печати — побочный эффект в самом безобидном месте), чему равно
 * {@code len(s)}, что пишет JSON, чему равно {@code s == s}.
 *
 * <h2>Только методы, ни одного свойства</h2>
 * Граница из {@code docs/members.md} — «устареет ли ответ». У потока устареет любой:
 * {@code s.count} свойством соврало бы дважды — оно потребляет поток и зависит
 * от источника, который живёт снаружи. Поэтому {@code s.count()}, а не {@code s.count},
 * притом что у массива именно {@code a.size}.
 *
 * <h2>Промежуточная операция расходует поток наравне с терминальной</h2>
 * {@code s.map(f)} не копирует конвейер, а надстраивает его: у {@code s} собственного
 * входа после этого нет. См. {@link Pipeline}.
 *
 * <h2>Ресурс закрывается каскадом</h2>
 * Класс подмешивает {@code Closeable} из прелюдии, поэтому {@code use (s = ...)}
 * и {@code defer s.close()} работают без единой правки в языке: {@code use} смотрит
 * на трейт, а не на тип. Закрытие идёт вверх по звеньям до источника.
 */
final class NativeStream {

    /** Имя трейта, который прелюдия кладёт в корневую область. */
    private static final String CLOSEABLE = "Closeable";

    private NativeStream() {
    }

    /**
     * Класс потока для этого запуска.
     * <p>
     * На запуск, а не статическим полем, по той же причине, что у потоков {@code sys.io}:
     * класс обещает трейт {@code Closeable}, а тот объявлен прелюдией и принадлежит
     * запуску.
     */
    static NativeClass build(Environment scope) {
        NativeClass.Builder builder = NativeClass.named("Stream")
                .doc("ленивый конвейер: работа происходит в терминальной операции")

                // --- промежуточные: отдают поток, работы не делают -------------------

                .method("map", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.map(take(self, span), args.callback(0, "преобразование"))))

                .method("filter", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.filter(take(self, span), args.callback(0, "условие"))))

                .method("flatMap", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.flatMap(take(self, span),
                                args.callback(0, "преобразование"), span)))

                .method("peek", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.peek(take(self, span), args.callback(0, "обработчик"))))

                .method("limit", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.limit(take(self, span),
                                count(args, 0, "сколько взять"))))

                .method("skip", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.skip(take(self, span),
                                count(args, 0, "сколько пропустить"))))

                .method("takeWhile", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.takeWhile(take(self, span), args.callback(0, "условие"))))

                .method("dropWhile", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.dropWhile(take(self, span), args.callback(0, "условие"))))

                // Другой поток расходуется наравне со своим: пара без второй половины
                // не бывает, и оставлять зазипованный поток «наполовину пройденным»
                // значило бы отдать значение, любое действие над которым — ошибка.
                .method("zip", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.zip(take(self, span), other(args, 0, span))))

                .method("chunked", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.chunked(take(self, span),
                                window(args, 0, "размер куска"))))

                .method("windowed", Arity.exactly(1), (self, context, args, span) ->
                        derive(self, Stages.windowed(take(self, span),
                                window(args, 0, "размер окна"))))

                .method("distinct", Arity.exactly(0), (self, context, args, span) ->
                        derive(self, Stages.distinct(take(self, span), context, span)))

                // Барьер: читает вход целиком при первом обращении и на бесконечном
                // источнике не кончается никогда. Необязательный аргумент — ключ
                // сравнения: 'sorted(p => p.price)' сравнивает цены, а не сами объекты.
                .method("sorted", Arity.between(0, 1), (self, context, args, span) ->
                        derive(self, Stages.sorted(take(self, span),
                                args.has(0) ? args.callback(0, "ключ") : null, context, span)))

                .method("mapConcurrent", Arity.exactly(2), (self, context, args, span) ->
                        derive(self, Concurrent.mapConcurrent(take(self, span),
                                window(args, 0, "сколько задач сразу"),
                                args.callback(1, "преобразование"), context, span)))

                // --- терминальные: здесь и происходит работа -------------------------

                .method("list", Arity.exactly(0), (self, context, args, span) ->
                        Terminals.list(take(self, span)))

                .method("object", Arity.exactly(2), (self, context, args, span) ->
                        Terminals.object(take(self, span), args.callback(0, "ключ"),
                                args.callback(1, "значение")))

                .method("each", Arity.exactly(1), (self, context, args, span) ->
                        Terminals.each(take(self, span), args.callback(0, "обработчик")))

                // Порядок аргументов тот же, что у массива: сначала свёртка, потом
                // необязательное начальное значение. У Java наоборот, и соблазн повторить
                // за ней был, но 'a.reduce(f, 0)' в этом же языке уже написан — два
                // одноимённых члена с противоположным порядком аргументов это ловушка,
                // а не выразительность.
                .method("reduce", Arity.between(1, 2), (self, context, args, span) ->
                        Terminals.reduce(take(self, span), args.has(1) ? args.at(1) : null,
                                args.has(1), args.callback(0, "свёртка"), span))

                .method("count", Arity.exactly(0), (self, context, args, span) ->
                        Terminals.count(take(self, span)))

                .method("sum", Arity.exactly(0), (self, context, args, span) ->
                        Terminals.sum(take(self, span), context, span))

                .method("min", Arity.exactly(0), (self, context, args, span) ->
                        Terminals.extreme(take(self, span), -1, context, span))

                .method("max", Arity.exactly(0), (self, context, args, span) ->
                        Terminals.extreme(take(self, span), 1, context, span))

                .method("first", Arity.exactly(0), (self, context, args, span) ->
                        Terminals.first(take(self, span)))

                .method("find", Arity.exactly(1), (self, context, args, span) ->
                        Terminals.find(take(self, span), args.callback(0, "условие")))

                // Условие необязательно: без него спрашивается истинность самого
                // элемента — тот же вопрос, что задаёт 'if (item)', и то же правило,
                // что у массива.
                .method("any", Arity.between(0, 1), (self, context, args, span) ->
                        Terminals.any(take(self, span), predicate(args)))

                .method("all", Arity.between(0, 1), (self, context, args, span) ->
                        Terminals.all(take(self, span), predicate(args)))

                .method("none", Arity.between(0, 1), (self, context, args, span) ->
                        Terminals.none(take(self, span), predicate(args)))

                .method("join", Arity.between(0, 1), (self, context, args, span) ->
                        Terminals.join(take(self, span),
                                args.has(0) ? args.string(0, "разделитель") : ""))

                // Закрытие идемпотентно и на пройденном потоке — не ошибка: источником
                // владеет тот, кто его забрал. Иначе 'use (s = ...) { s.filter(f).list() }'
                // закрывал бы файл дважды. Отвечает, было ли что закрывать, — как
                // io.remove отвечает, было ли что удалять.
                .method("close", Arity.exactly(0), (self, context, args, span) ->
                        BoolValue.of(pipeline(self, span).close()));

        // Трейт берётся из области, а не из статики: Closeable объявлен прелюдией
        // и принадлежит запуску — тот же приём, что у дескрипторов sys.io и у пула.
        if (scope.lookup(CLOSEABLE) instanceof TraitValue trait) {
            builder.with(trait);
        }
        return builder.build();
    }

    /** Значение-поток над готовым конвейером. */
    static Value wrap(NativeClass streamClass, Source source) {
        NativeInstance instance = new NativeInstance(streamClass);
        instance.state(new Pipeline(source));
        return instance;
    }

    /**
     * Новый поток того же класса — того же самого объекта класса, что у получателя.
     * <p>
     * Класс спрашивается у самого экземпляра, а не замыкается при сборке: он один
     * на запуск, и брать его оттуда, где он заведомо тот, дешевле и вернее, чем
     * протаскивать его через все двадцать методов.
     */
    private static Value derive(NativeInstance self, Source stage) {
        return wrap((NativeClass) self.owner(), stage);
    }

    private static Source take(NativeInstance self, Span span) {
        return pipeline(self, span).take(span);
    }

    private static Pipeline pipeline(NativeInstance self, Span span) {
        Pipeline state = self.state(Pipeline.class);
        if (state == null) {
            throw new WdlRuntimeError(span, "за этим значением нет конвейера:"
                    + " поток отдают функции модуля sys.streams");
        }
        return state;
    }

    /** Необязательное условие {@code any}, {@code all} и {@code none}. */
    private static Callback predicate(Args args) {
        return args.has(0) ? args.callback(0, "условие") : null;
    }

    /** Неотрицательное число элементов: {@code limit}, {@code skip}. */
    private static long count(Args args, int index, String role) {
        long value = args.integer(index, role);
        if (value < 0) {
            throw args.bad(index, role, "ожидалось неотрицательное число");
        }
        return value;
    }

    /**
     * Положительный размер: {@code chunked}, {@code windowed}, {@code mapConcurrent}.
     * <p>
     * Ноль здесь не «ничего не делать», а бесконечный цикл или деление на ноль
     * по смыслу, — поэтому отказ, а не молчаливое согласие.
     */
    private static int window(Args args, int index, String role) {
        long value = args.integer(index, role);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw args.bad(index, role, "ожидалось положительное число");
        }
        return (int) value;
    }

    /** Второй поток для {@code zip} — и расходуется он наравне со своим. */
    private static Source other(Args args, int index, Span span) {
        Source source = Pipeline.sourceIn(args.at(index), span);
        if (source == null) {
            throw new WdlRuntimeError(ErrorKind.TYPE, span,
                    Args.because("zip(): второй поток", "ожидался поток", args.at(index)));
        }
        return source;
    }
}
