package ru.wds.wdl.stdlib.bytes;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.Encodings;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Octets;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.NumberValue;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.BytesValue;
import ru.wds.wdl.value.types.IntValue;

/**
 * Класс {@code bin.Writer}: место, где байты собирают.
 *
 * <pre>{@code
 * w = bin.writer()
 * w.order("le")
 * w.putInt32(42).putText("hello").put(signature)
 * packet = w.bytes                    // снимок; писать в w можно дальше
 * }</pre>
 *
 * <h2>Имя — то же, что у остальной библиотеки</h2>
 * Роль «сюда пишут» в библиотеке уже названа: {@code io.Writer}. Заводить для неё
 * второе слово ({@code Buffer}) значило бы, что имён вдвое больше, чем ролей.
 * Совпадение не мешает — класс в скрипте всегда пишется с модулем, — а разница между
 * ними честная и видна из модуля: {@code io.Writer} пишет в файл и его надо закрывать,
 * {@code bin.Writer} пишет в память, и закрывать его нечего. Поэтому {@code use} здесь
 * и не обязателен, и трейта {@code Closeable} у класса нет.
 *
 * <h2>Каждый {@code put} возвращает сам буфер</h2>
 * Чтобы {@code w.putInt32(n).putText(s)} читалось одной строкой. То же правило, что
 * у {@code io.File.write} и {@code io.Writer.write}: «ничего» никому не нужно.
 *
 * <h2>Девять видов числа — девять методов, и все из одного словаря</h2>
 * {@code putInt8} … {@code putFloat64} заводятся циклом по {@link Octets.Kind}:
 * список видов объявлен один раз, и второй его копии здесь нет. Диапазон каждый
 * проверяет свой и строго — их для того и пишут: {@code putInt8(200)} ошибка,
 * {@code putUint8(200)} нет. Широкая запись байта ({@code -128..255}) — только
 * у {@code put(v)}, где вопрос стоит иначе: «это вообще байт?».
 */
final class NativeWriter {

    private NativeWriter() {
    }

    static NativeClass build() {
        NativeClass.Builder builder = NativeClass.named("Writer")
                .doc("буфер, в который собирают байты; снимок — свойство 'bytes'")
                .backing(ByteSink.class)

                // Свойство, а не метод: существительное, без аргументов, и меняется
                // только от записи, написанной в тексте, — то же правило, по которому
                // свойством остаётся 'a.size' у изменяемого массива.
                .property("size", (self, context, span) -> IntValue.of(sink(self, span).size()))
                .property("empty", (self, context, span) ->
                        ru.wds.wdl.value.types.BoolValue.of(sink(self, span).size() == 0))

                // Снимок: новые байты каждый раз, а буфер как был. Свойство по той же
                // причине, по которой свойством сделан 'a.copy' у массива.
                .property("bytes", (self, context, span) -> {
                    ByteSink sink = sink(self, span);
                    Octets.work(context, sink.size(), span);
                    return sink.snapshot();
                })

                .method("order", Arity.exactly(1), (self, context, arguments, span) -> {
                    sink(self, span).order(Octets.littleEndian(
                            arguments.string(0, "порядок байтов"), "bin.Writer.order()", span));
                    return self;
                })

                // Один байт или целые байты — один метод: вопрос у них общий,
                // «допиши это в конец», и разводить его по двум именам не за что.
                .method("put", Arity.exactly(1), (self, context, arguments, span) -> {
                    ByteSink sink = sink(self, span);
                    Value what = arguments.at(0);
                    if (what instanceof BytesValue data) {
                        Octets.work(context, data.size(), span);
                        sink.put(data.toArray(), context, span);
                        return self;
                    }
                    sink.put(Octets.any(what, "bin.Writer.put(): значение", span), context, span);
                    return self;
                })

                .method("putText", Arity.between(1, 2), (self, context, arguments, span) -> {
                    String text = arguments.at(0).display();
                    BytesValue encoded = Encodings.bytes(text,
                            Binaries.charset(arguments, 1, "bin.Writer.putText()", span),
                            "bin.Writer.putText()", span);
                    Octets.work(context, encoded.size(), span);
                    sink(self, span).put(encoded.toArray(), context, span);
                    return self;
                })

                // Забыть написанное, оставив ёмкость: так буфер переиспользуют в цикле
                // пакетов, не выделяя память заново на каждый.
                .method("clear", Arity.exactly(0), (self, context, arguments, span) -> {
                    sink(self, span).clear();
                    return self;
                });

        for (Octets.Kind kind : Octets.Kind.values()) {
            builder.method(Binaries.putter(kind), Arity.exactly(1),
                    (self, context, arguments, span) -> {
                        Value given = arguments.at(0);
                        if (!(given instanceof NumberValue number)) {
                            throw new WdlRuntimeError(ErrorKind.TYPE, span, Args.because(
                                    "bin.Writer." + Binaries.putter(kind) + "()",
                                    "ожидалось число", given));
                        }
                        sink(self, span).put(kind, number,
                                "bin.Writer." + Binaries.putter(kind) + "()", context, span);
                        return self;
                    });
        }
        return builder.build();
    }

    private static ByteSink sink(NativeInstance self, Span span) {
        return Binaries.state(self, ByteSink.class, span);
    }
}
