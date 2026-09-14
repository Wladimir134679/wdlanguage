package ru.wds.wdl.stdlib.bytes;

import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.NativeInstance;
import ru.wds.wdl.runtime.Encodings;
import ru.wds.wdl.runtime.Octets;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.BytesValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.StringValue;

/**
 * Класс {@code bin.Reader}: чтение готовых байтов по порядку.
 *
 * <pre>{@code
 * r = bin.reader(packet)
 * r.order("le")
 * kind = r.uint8()
 * size = r.int32()
 * body = r.bytes(size)
 * println(r.remaining)                 // сколько осталось
 * }</pre>
 *
 * <h2>Зачем он, если есть {@code data.int32(at)}</h2>
 * Разбор формата — это «четыре байта, потом два, потом строка такой длины», и считать
 * смещение между вызовами руками значит один раз ошибиться и искать это полдня.
 * Курсор считает его сам. Байты при этом не меняются: {@code Reader} держит только
 * позицию, и два курсора по одним и тем же байтам друг другу не мешают.
 *
 * <h2>Имя — то же, что у остальной библиотеки</h2>
 * Роль «отсюда читают» уже названа словом {@code Reader} ({@code io.Reader}), и второе
 * слово для неё ({@code Cursor}) только удвоило бы словарь. Разница между ними видна
 * из модуля: {@code io.Reader} читает файл и закрывается, {@code bin.Reader} читает
 * память, и закрывать его нечего.
 *
 * <h2>Конец данных — ошибка, а не «сколько есть»</h2>
 * {@code r.int32()} на трёх оставшихся байтах откажет. Обрезанный файл или сдвиг
 * на байт выше по коду — самое частое, что стоит за таким чтением, и отдать наверх
 * правдоподобный мусор хуже, чем остановиться. Прочитать «сколько получится» можно
 * осознанно: сперва спросить {@code remaining}.
 */
final class NativeReader {

    private NativeReader() {
    }

    static NativeClass build() {
        NativeClass.Builder builder = NativeClass.named("Reader")
                .doc("курсор по готовым байтам: позиция сдвигается сама")
                .backing(ByteCursor.class)

                // Свойства: аргументов нет, и меняются они только от чтения,
                // написанного в тексте, — как 'a.size' у изменяемого массива.
                .property("position", (self, context, span) ->
                        IntValue.of(cursor(self, span).position()))
                .property("remaining", (self, context, span) ->
                        IntValue.of(cursor(self, span).remaining()))
                .property("done", (self, context, span) ->
                        BoolValue.of(cursor(self, span).remaining() == 0))
                // Те же байты, что дали курсору: они неизменны, отдавать их безопасно
                // и копировать нечего.
                .property("source", (self, context, span) -> cursor(self, span).data())

                .method("order", Arity.exactly(1), (self, context, arguments, span) -> {
                    cursor(self, span).order(Octets.littleEndian(
                            arguments.string(0, "порядок байтов"), "bin.Reader.order()", span));
                    return self;
                })

                .method("bytes", Arity.exactly(1), (self, context, arguments, span) -> {
                    int count = Binaries.count(arguments, 0, "bin.Reader.bytes()", span);
                    Octets.work(context, count, span);
                    return cursor(self, span).take(count, "bytes()", span);
                })

                .method("text", Arity.between(1, 2), (self, context, arguments, span) -> {
                    int count = Binaries.count(arguments, 0, "bin.Reader.text()", span);
                    Octets.work(context, count, span);
                    BytesValue chunk = cursor(self, span).take(count, "text()", span);
                    return StringValue.of(Encodings.text(chunk,
                            Binaries.charset(arguments, 1, "bin.Reader.text()", span),
                            false, "bin.Reader.text()", span));
                })

                // Всё, что осталось, — одним куском: обычный конец разбора, когда
                // длина хвоста в формате не записана.
                .method("rest", Arity.exactly(0), (self, context, arguments, span) -> {
                    ByteCursor cursor = cursor(self, span);
                    int left = cursor.remaining();
                    Octets.work(context, left, span);
                    return cursor.take(left, "rest()", span);
                })

                .method("skip", Arity.exactly(1), (self, context, arguments, span) -> {
                    cursor(self, span).skip(
                            Binaries.count(arguments, 0, "bin.Reader.skip()", span), span);
                    return self;
                })

                .method("seek", Arity.exactly(1), (self, context, arguments, span) -> {
                    cursor(self, span).seek(Binaries.at(arguments, 0), span);
                    return self;
                });

        // Девять методов чтения числа — по тому же словарю, что и члены bytes
        // и put-методы буфера. Вид числа объявлен один раз, копий у него нет.
        for (Octets.Kind kind : Octets.Kind.values()) {
            builder.method(kind.id(), Arity.exactly(0), (self, context, arguments, span) ->
                    cursor(self, span).take(kind, span));
        }
        return builder.build();
    }

    private static ByteCursor cursor(NativeInstance self, Span span) {
        return Binaries.state(self, ByteCursor.class, span);
    }
}
