package ru.wds.wdl.stdlib.bytes;

import ru.wds.wdl.bridge.Module;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Encodings;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Octets;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.Signature.Param;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BytesValue;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Модуль {@code sys.bytes}: откуда байты берутся и где их собирают.
 *
 * <pre>{@code
 * import sys.bytes as bin
 *
 * signature = bin.hex("89504e47")
 * println(signature.size)                  // 4
 *
 * w = bin.writer()
 * w.order("le").putInt32(42).putText("ok")
 * packet = w.bytes                         // снимок; в w можно писать дальше
 *
 * r = bin.reader(packet)
 * r.order("le")
 * println(r.int32(), " ", r.text(2))       // 42 ok
 * }</pre>
 *
 * <h2>Граница модуля — одна фраза</h2>
 * <b>С готовыми байтами язык умеет всё сам, а чтобы байты появились, нужен модуль.</b>
 * Длина, элемент, срез, обход, {@code ==}, {@code +}, {@code hex}, {@code text()} —
 * это члены типа {@code bytes} в ядре, и импорта они не требуют. Здесь же — фабрики
 * ({@code of}, {@code hex}, {@code base64}, {@code zeros}, {@code random},
 * {@code join}) и два класса. Единственное исключение из границы —
 * {@code "строка".bytes}: текст в языке уже есть, и требовать импорт ради перевода
 * строки в байты — то же самое, что требовать его ради {@code len}.
 *
 * <h2>Почему фабрики не висят на дескрипторе {@code Bytes}</h2>
 * {@code Bytes.hex("89504e47")} читалось бы лучше и импорта не требовало — но имя уже
 * занято: обращение к члену значения через дескриптор ({@code Array.size(a)} ≡
 * {@code a.size}) делает {@code Bytes.hex} записью «возьми свойство {@code hex}
 * у переданных байтов». Фабрика с тем же именем превратила бы {@code Bytes.hex(x)}
 * в выражение, смысл которого зависит от типа {@code x}, — худший вид перегрузки.
 * Поэтому дескриптор остаётся чистым дескриптором, как {@code String} и {@code Array}.
 *
 * <h2>Псевдоним {@code bin}, а не {@code bytes}</h2>
 * Во всех примерах и документах модуль импортируется как {@code bin}. Соблазнительное
 * {@code as bytes} не берётся намеренно: имя в области, отличающееся от дескриптора
 * {@code Bytes} одним регистром, превращает опечатку в одну букву в другое осмысленное
 * выражение.
 */
public final class Binaries {

    private Binaries() {
    }

    private static final Signature SIZE = Signature.of(Param.required("size"));
    private static final Signature SOURCE = Signature.of(Param.required("source"));

    /** Библиотека модуля: два класса и девять функций. */
    public static Library library() {
        return Module.named("sys/bytes")
                .doc("байты: откуда их взять, чем собрать и чем разобрать")

                .type("Writer", scope -> NativeWriter.build())
                .doc("место, где байты собирают: put-методы, порядок байтов, снимок")
                .type("Reader", scope -> NativeReader.build())
                .doc("чтение готовых байтов по порядку: позиция сама сдвигается")

                // Функции, которым нужны собранные классы, заводятся куском кода:
                // построитель отдаёт область, класс берётся из неё. Тот же приём,
                // что у sys.io и sys.streams.
                .install(scope -> {
                    NativeClass writer = Module.typeIn(scope, "Writer");
                    NativeClass reader = Module.typeIn(scope, "Reader");
                    scope.define("writer", BuiltinFunction.of("writer", Arity.exactly(0),
                                    (context, arguments, span) ->
                                            instance(writer, ByteSink.growing(), context, span))
                            .documented("растущий буфер: пишет столько, сколько дадут"));
                    scope.define("fixed", BuiltinFunction.of("fixed", SIZE,
                                    (context, arguments, span) -> {
                                        int size = length(arguments, "bin.fixed()", span);
                                        context.allocating(size, "bin.fixed()", span);
                                        return instance(writer, ByteSink.fixed(size), context, span);
                                    })
                            .documented("буфер ровно на столько байтов: переполнение — ошибка"));
                    scope.define("reader", BuiltinFunction.of("reader", SOURCE,
                                    (context, arguments, span) -> instance(reader,
                                            new ByteCursor(Args.of("bin.reader", arguments, context, span)
                                                    .bytes(0, "байты")), context, span))
                            .documented("курсор по готовым байтам; сами байты не меняются"));
                })

                // Массив чисел на входе берётся в любой записи байта: 200 и -56 —
                // две записи одного байта, и bin.of([0x89, 0x50, 0x4e, 0x47]),
                // переписанное из документации формата, работает буквально.
                .function("of", SOURCE, (context, arguments, span) -> {
                    ArrayValue source = Args.of("bin.of", arguments, context, span)
                            .array(0, "массив байтов");
                    List<Value> items = source.items();
                    context.allocating(items.size(), "bin.of()", span);
                    Octets.work(context, items.size(), span);
                    byte[] out = new byte[items.size()];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = Octets.any(items.get(i), "bin.of(): элемент " + i, span);
                    }
                    return BytesValue.owning(out);
                })
                .doc("байты из массива чисел; байт пишется и знаковым, и беззнаковым")

                .function("hex", SOURCE, (context, arguments, span) ->
                        fromHex(Args.of("bin.hex", arguments, context, span)
                                .string(0, "шестнадцатеричная запись"), context, span))
                .doc("байты из шестнадцатеричной записи: bin.hex(\"89504e47\")")

                .function("base64", SOURCE, (context, arguments, span) -> {
                    String text = Args.of("bin.base64", arguments, context, span)
                            .string(0, "запись base64");
                    context.allocating(text.length(), "bin.base64()", span);
                    Octets.work(context, text.length(), span);
                    try {
                        return BytesValue.owning(Base64.getDecoder().decode(text));
                    } catch (IllegalArgumentException broken) {
                        throw new WdlRuntimeError(ErrorKind.VALUE, span,
                                "bin.base64(): это не base64 — " + broken.getMessage());
                    }
                })
                .doc("байты из записи base64")

                .function("zeros", SIZE, (context, arguments, span) -> {
                    int size = length(arguments, "bin.zeros()", span);
                    context.allocating(size, "bin.zeros()", span);
                    Octets.work(context, size, span);
                    return BytesValue.owning(new byte[size]);
                })
                .doc("столько нулевых байтов")

                .function("random", SIZE, (context, arguments, span) -> {
                    int size = length(arguments, "bin.random()", span);
                    context.allocating(size, "bin.random()", span);
                    Octets.work(context, size, span);
                    byte[] out = new byte[size];
                    ThreadLocalRandom.current().nextBytes(out);
                    return BytesValue.owning(out);
                })
                .doc("столько случайных байтов: соль, идентификатор, тестовые данные")

                // Склейка списком, а не цепочкой '+': десять кусков через '+' — это
                // девять промежуточных копий, а здесь одна и сразу нужного размера.
                .function("join", SOURCE, (context, arguments, span) -> {
                    Args args = Args.of("bin.join", arguments, context, span);
                    List<Value> parts = args.array(0, "массив байтов").items();
                    long total = 0;
                    for (int i = 0; i < parts.size(); i++) {
                        if (!(parts.get(i) instanceof BytesValue part)) {
                            throw new WdlRuntimeError(ErrorKind.TYPE, span, Args.because(
                                    "bin.join(): элемент " + i, "ожидались байты", parts.get(i)));
                        }
                        total += part.size();
                    }
                    context.allocating(total, "bin.join()", span);
                    Octets.work(context, total, span);
                    byte[] out = new byte[(int) Math.min(total, Integer.MAX_VALUE)];
                    int at = 0;
                    for (Value part : parts) {
                        byte[] raw = ((BytesValue) part).toArray();
                        System.arraycopy(raw, 0, out, at, raw.length);
                        at += raw.length;
                    }
                    return BytesValue.owning(out);
                })
                .doc("склейка массива байтов в одно значение — одной копией")

                .build();
    }

    /** Экземпляр класса модуля с готовым состоянием внутри. */
    private static Value instance(NativeClass type, Object state, CallContext context, Span span) {
        Value created = type.instantiate(List.of(), context, span);
        ((NativeInstance) created).state(state);
        return created;
    }

    /**
     * Размер в байтах из аргумента: целое, неотрицательное и влезающее в массив Java.
     * <p>
     * Отдельной проверкой, а не приведением: {@code bin.zeros(-1)} и
     * {@code bin.zeros(1 << 40)} — разные ошибки, и сказать о них надо разное.
     * Предел запуска ({@code Limits.maxBufferBytes}) проверяется отдельно и раньше
     * настоящего выделения — см. вызовы {@code context.allocating}.
     */
    private static int length(Args arguments, String what, Span span) {
        long size = arguments.integer(0, "размер");
        if (size < 0) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what
                    + ": размер не может быть отрицательным: " + size);
        }
        if (size > Integer.MAX_VALUE - 8) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": " + size
                    + " байт одним куском не бывает — больше, чем длина массива в Java");
        }
        return (int) size;
    }

    /**
     * Разбор шестнадцатеричной записи.
     * <p>
     * Разделителей нет и пробелы не пропускаются: {@code bin.hex("89 50")} — ошибка,
     * а не «мы догадались». Запись сюда переписывают из описания формата, и молчаливое
     * проглатывание лишнего символа там, где каждый символ — половина байта, стоит
     * дороже удобства.
     */
    private static BytesValue fromHex(String text, CallContext context, Span span) {
        if ((text.length() & 1) != 0) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "bin.hex(): в записи "
                    + text.length() + " цифр — нечётное число, а байт это ровно две");
        }
        context.allocating(text.length() / 2, "bin.hex()", span);
        Octets.work(context, text.length() / 2, span);
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((digit(text, i * 2, span) << 4) | digit(text, i * 2 + 1, span));
        }
        return BytesValue.owning(out);
    }

    private static int digit(String text, int at, Span span) {
        int value = Character.digit(text.charAt(at), 16);
        if (value < 0) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, "bin.hex(): '" + text.charAt(at)
                    + "' на позиции " + at + " не шестнадцатеричная цифра (0-9, a-f)");
        }
        return value;
    }

    /** Имя {@code put}-метода или члена по виду числа: {@code int32} → {@code putInt32}. */
    static String putter(Octets.Kind kind) {
        return "put" + Character.toUpperCase(kind.id().charAt(0)) + kind.id().substring(1);
    }

    /** Живое состояние экземпляра или отказ: класс без состояния собрать нельзя. */
    static <T> T state(NativeInstance self, Class<T> type, Span span) {
        T live = self.state(type);
        if (live == null) {
            throw new WdlRuntimeError(span, "буфер не готов: экземпляр создан в обход "
                    + "фабрик модуля ('bin.writer()', 'bin.fixed(n)', 'bin.reader(data)')");
        }
        return live;
    }

    /** Число байтов из аргумента метода — то же правило, что у размера фабрики. */
    static int count(Args arguments, int index, String what, Span span) {
        long size = arguments.integer(index, "сколько байт");
        if (size < 0 || size > Integer.MAX_VALUE - 8) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": " + size
                    + " — не число байтов");
        }
        return (int) size;
    }

    /** Кодировка из необязательного аргумента; по умолчанию UTF-8. */
    static java.nio.charset.Charset charset(Args arguments, int index, String what, Span span) {
        return arguments.has(index)
                ? Encodings.of(arguments.string(index, "кодировка"), what, span)
                : Encodings.DEFAULT;
    }

    /** Позиция для {@code seek} и прочего — целым числом. */
    static int at(Args arguments, int index) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(arguments.integer(index, "позиция"),
                Integer.MAX_VALUE));
    }
}
