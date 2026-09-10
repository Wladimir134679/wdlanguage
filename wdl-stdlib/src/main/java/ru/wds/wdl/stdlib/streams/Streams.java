package ru.wds.wdl.stdlib.streams;

import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.stdlib.Files;
import ru.wds.wdl.stdlib.thread.Threads;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.MemberRegistry;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.ValueType;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.RangeValue;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Модуль {@code sys.streams}: ленивые конвейеры обработки данных.
 *
 * <pre>{@code
 * import sys.streams as streams
 *
 * use (s = streams.lines("access.log")) {
 *     top = s.filter(l => l.contains(" 500 "))
 *            .map(l => l.split(" ")[0])
 *            .limit(100)
 *            .list()
 * }
 *
 * [1, 2, 3].stream().map(x => x * 2).list()          // член массива ставит этот модуль
 * }</pre>
 *
 * <h2>Зачем, если у массива уже есть {@code map} и {@code filter}</h2>
 * Есть, и они закрывают подавляющее большинство скриптов. Конвейер заведён
 * <b>не вместо них</b> — иначе в языке появилась бы вторая запись для того же самого.
 * Его ниша — четыре случая, которых массив не даёт и дать не может:
 * <ol>
 *   <li><b>короткое замыкание сквозь стадии</b>: {@code a.map(heavy).find(p)} считает
 *       {@code heavy} для всех элементов, а конвейер — до первого совпадения;</li>
 *   <li><b>источник, которого нет в памяти</b>: строки большого файла, {@code th.channel},
 *       бесконечная последовательность;</li>
 *   <li><b>отсутствие промежуточных копий</b>: {@code a.filter(f).map(g)} строит два
 *       массива, конвейер — ни одного;</li>
 *   <li><b>конкурентная обработка</b> ({@code mapConcurrent}): у массива её нет
 *       и быть не должно.</li>
 * </ol>
 * Отсюда граница: <b>массив отвечает на «преобразуй эти данные», поток — на «протяни
 * данные через конвейер, не собирая их»</b>. Скрипту на тысячу элементов конвейер
 * не нужен.
 *
 * <h2>Открытые источники держит реестр</h2>
 * {@code streams.lines(path)} открывает файл, и закрыть его обязан либо {@code use},
 * либо терминальная операция. Забытый закрывается вместе с запуском — тем же приёмом,
 * каким {@code sys.gui} закрывает недозакрытые окна: реестр в замыкании библиотеки
 * плюс {@code onClose}. Реестр конкурентный, потому что конвейер вправе быть собран
 * в одном потоке скрипта и пройден в другом.
 */
public final class Streams {

    private Streams() {
    }

    /** Один путь — контракт {@code lines}, тот же, что у функций {@code sys.io}. */
    private static final Signature PATH = Signature.of(Param.required("path"));

    /** Начало и конец — обе границы включительно, как у {@code a..b}. */
    private static final Signature BOUNDS =
            Signature.of(Param.required("from"), Param.required("to"));

    /** Начальное значение и шаг: {@code streams.iterate(1, x => x * 2)}. */
    private static final Signature SEED_STEP =
            Signature.of(Param.required("seed"), Param.required("step"));

    /** Библиотека модуля: класс, семь функций-источников и один член массива. */
    public static Library library() {
        // Живые источники этого запуска. Держатся здесь, а не статикой: два движка
        // в одном процессе не делят ни классы, ни открытые файлы.
        Set<Source> open = ConcurrentHashMap.newKeySet();
        return Module.named("sys/streams")
                .doc("ленивые конвейеры: обработать данные, не собирая их в память")

                // Класс собирается на запуск, а не статическим полем: он обещает трейт
                // Closeable, а тот объявлен прелюдией и принадлежит запуску.
                .type("Stream", NativeStream::build)

                // Функции замыкаются на собранный класс, поэтому заводятся куском кода,
                // а не звеньями .function(...): построитель отдаёт область, класс берётся
                // из неё. Описания идут прямо на функции — построитель про них не знает.
                .install(scope -> install(scope, open))

                // Забытое скриптом отпускается вместе с запуском. Ошибка одного источника
                // не отменяет остальных — то же правило, что у Module.close().
                .onClose(() -> {
                    List<Source> forgotten = new ArrayList<>(open);
                    open.clear();
                    Closing.all(forgotten);
                })

                .build();
    }

    private static void install(Environment scope, Set<Source> open) {
        NativeClass streamClass = Module.typeIn(scope, "Stream");

        scope.define("of", BuiltinFunction.of("of", Arity.exactly(1),
                        (context, arguments, span) ->
                                NativeStream.wrap(streamClass, from(arguments, context, span)))
                .documented("поток по массиву, диапазону или каналу th.channel"));

        scope.define("range", BuiltinFunction.of("range", BOUNDS,
                        (context, arguments, span) -> NativeStream.wrap(streamClass,
                                Sources.range(arguments.integer(0, "начало"),
                                        arguments.integer(1, "конец"), context, span)))
                .documented("поток целых от начала до конца, границы включительно"));

        scope.define("iterate", BuiltinFunction.of("iterate", SEED_STEP,
                        (context, arguments, span) -> NativeStream.wrap(streamClass,
                                Sources.iterate(arguments.at(0), arguments.callback(1, "шаг"),
                                        context, span)))
                .documented("бесконечный поток: значение, затем шаг от предыдущего"));

        scope.define("repeat", BuiltinFunction.of("repeat", Arity.exactly(1),
                        (context, arguments, span) -> NativeStream.wrap(streamClass,
                                Sources.repeat(arguments.at(0), context, span)))
                .documented("бесконечный поток одного и того же значения"));

        scope.define("empty", BuiltinFunction.of("empty", Arity.exactly(0),
                        (context, arguments, span) ->
                                NativeStream.wrap(streamClass, Sources.empty()))
                .documented("поток без элементов"));

        scope.define("concat", BuiltinFunction.of("concat", Arity.atLeast(1),
                        (context, arguments, span) -> {
                            List<Source> parts = new ArrayList<>(arguments.size());
                            for (int i = 0; i < arguments.size(); i++) {
                                parts.add(part(arguments, i, context, span));
                            }
                            return NativeStream.wrap(streamClass, Sources.concat(parts));
                        })
                .documented("склейка потоков: сначала первый до конца, потом следующий"));

        scope.define("lines", BuiltinFunction.of("lines", PATH,
                        (context, arguments, span) -> NativeStream.wrap(streamClass,
                                lines(Files.pathOf(arguments, 0), open, context, span)))
                .documented("ленивое построчное чтение файла; закрывается через use"));

        // Член массива: 'a.stream()'. Без import его нет, ядро не меняется, цена
        // нулевая — набор ставится в таблицу членов запуска ровно тогда, когда модуль
        // выполнили. Метод, а не свойство: 'a.stream' свойством отдавало бы значение,
        // которое одноразово, — а имя обещает, что за ним ничего не происходит.
        MemberRegistry members = scope.members();
        if (members != null) {
            members.install(ValueType.ARRAY, MemberSet.builder()
                    .method("stream", Arity.exactly(0), (receiver, context, arguments, span) ->
                            NativeStream.wrap(streamClass,
                                    Sources.of(((ArrayValue) receiver).items(), context, span)))
                    .build());
        }
    }

    /**
     * Источник по значению: массив, диапазон или канал.
     * <p>
     * Ровно три, а не «всё перебираемое»: строку рассыпать на буквы почти всегда значит
     * опечатку, а объект перебирается ключами, и молча выбрать за автора одно из двух
     * хуже, чем спросить.
     */
    private static Source from(Args arguments, CallContext context, Span span) {
        Value value = arguments.at(0);
        if (value instanceof ArrayValue array) {
            return Sources.of(array.items(), context, span);
        }
        if (value instanceof RangeValue range) {
            if (!range.from().isInteger() || !range.to().isInteger()) {
                throw new WdlRuntimeError(ErrorKind.TYPE, span, "streams.of(): поток по диапазону"
                        + " требует целых границ, а здесь " + range + ": шаг перебора равен единице");
            }
            return Sources.range(range.from().asLong(), range.to().asLong(), context, span);
        }
        Threads.Taking channel = Threads.channelIn(value);
        if (channel != null) {
            return Sources.channel(channel, context, span);
        }
        throw arguments.wrong(0, "источник", "ожидался массив, диапазон или канал");
    }

    /** Звено склейки: либо готовый поток, либо то, из чего его делает {@code of}. */
    private static Source part(Args arguments, int index, CallContext context, Span span) {
        Source pipeline = Pipeline.sourceIn(arguments.at(index), span);
        if (pipeline != null) {
            return pipeline;
        }
        Value value = arguments.at(index);
        if (value instanceof ArrayValue array) {
            return Sources.of(array.items(), context, span);
        }
        throw arguments.wrong(index, "звено склейки", "ожидался поток или массив");
    }

    /**
     * Построчный источник поверх открытого файла.
     * <p>
     * Открывается тем же способом и с той же кодировкой, что у {@code sys.io}, и ошибка
     * открытия переводится тем же {@code Files.io}: одна и та же неудача обязана
     * выглядеть одинаково, каким бы модулем автор ни открыл файл.
     */
    private static Source lines(Path path, Set<Source> open, CallContext context, Span span) {
        BufferedReader reader = Files.io(span, () ->
                java.nio.file.Files.newBufferedReader(path, StandardCharsets.UTF_8));
        // Ссылка на источник нужна ему самому — чтобы сняться с учёта при закрытии,
        // — поэтому массив на один элемент: замыкание на ещё не созданный объект
        // иначе не собрать.
        Source[] holder = new Source[1];
        holder[0] = Sources.lines(reader, () -> open.remove(holder[0]), context, span);
        open.add(holder[0]);
        return holder[0];
    }
}
