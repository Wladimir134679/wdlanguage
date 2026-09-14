package ru.wds.wdl.runtime.members;

import ru.wds.wdl.runtime.Args;
import ru.wds.wdl.runtime.Encodings;
import ru.wds.wdl.runtime.ErrorKind;
import ru.wds.wdl.runtime.Indexes;
import ru.wds.wdl.runtime.Octets;
import ru.wds.wdl.runtime.Overloading;
import ru.wds.wdl.runtime.WdlRuntimeError;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.CallContext;
import ru.wds.wdl.value.MemberSet;
import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.BytesValue;
import ru.wds.wdl.value.types.IntValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Члены байтов.
 * <p>
 * <b>Ни одного члена, меняющего получателя</b>, и по той же причине, что у строки:
 * {@code bytes} неизменяем. {@code hex} и {@code base64} — свойства-снимки: новая
 * строка каждый раз, а байты как были. Глагольных пар вида {@code sort()}/{@code sorted}
 * здесь поэтому не бывает вовсе, а место, где байты <i>меняют</i>, называется
 * {@code bin.writer()} и живёт в {@code sys.bytes}.
 *
 * <h2>Что тут поштучно и почему знаковое</h2>
 * {@code first}, {@code last}, {@code numbers} и результат {@code int8(at)} — знаковые
 * числа {@code -128..127}, как в Java. Беззнаковый ответ просят по имени:
 * {@code uint8(at)}. Одно правило вместо двух — то, что покупается этим решением,
 * разобрано в {@link Octets} и в {@code docs/bytes.md}; там же и цена:
 * {@code data[0] == 0x89} для подписи PNG — ложь, а {@code data.uint8(0) == 0x89}
 * и {@code data.startsWith(bin.hex("89504e47"))} — правда.
 *
 * <h2>Девять видов числа — девять одноимённых членов</h2>
 * {@code int8} … {@code float64} читают число, лежащее в байтах начиная с позиции;
 * порядок байтов — вторым аргументом, {@code "be"} по умолчанию. Ни списка имён,
 * ни диапазонов здесь нет: словарь один и объявлен в {@link Octets.Kind}, а члены
 * заводятся по нему циклом — новый вид числа появится сразу и здесь, и в буфере.
 */
public final class BytesMembers {

    private BytesMembers() {
    }

    static MemberSet set() {
        MemberSet.Builder builder = MemberSet.builder()
                .property("size", (receiver, context, span) -> IntValue.of(self(receiver).size()))
                .property("empty", (receiver, context, span) -> BoolValue.of(self(receiver).isEmpty()))
                .property("first", (receiver, context, span) -> {
                    BytesValue data = self(receiver);
                    return data.isEmpty() ? NullValue.NULL : IntValue.of(data.at(0));
                })
                .property("last", (receiver, context, span) -> {
                    BytesValue data = self(receiver);
                    return data.isEmpty() ? NullValue.NULL : IntValue.of(data.at(data.size() - 1));
                })

                // Свойства-снимки, а не методы: имя — существительное, аргументов нет,
                // ответ зависит только от самих байтов и устареть не может.
                // Шаги берутся пачкой, пропорционально объёму: цикл на сто мегабайт
                // идёт целиком внутри Java и ни одной точки движка не проходит —
                // без этого предел шагов обходился бы одной строкой скрипта.
                .snapshot("hex", (receiver, context, span) -> {
                    BytesValue data = self(receiver);
                    Octets.work(context, data.size(), span);
                    return StringValue.of(data.hex());
                })
                .snapshot("base64", (receiver, context, span) -> {
                    BytesValue data = self(receiver);
                    Octets.work(context, data.size(), span);
                    return StringValue.of(Base64.getEncoder().encodeToString(data.toArray()));
                })
                .snapshot("numbers", (receiver, context, span) -> {
                    BytesValue data = self(receiver);
                    Octets.work(context, data.size(), span);
                    List<Value> numbers = new ArrayList<>(data.size());
                    for (int i = 0; i < data.size(); i++) {
                        numbers.add(IntValue.of(data.at(i)));
                    }
                    return ArrayValue.of(numbers);
                })

                // Метод, а не свойство: есть аргументы, и за ним стоит декодирование,
                // которое может не получиться. Испорченная последовательность — ошибка,
                // а не молчаливые вопросики: см. Encodings.
                .method("text", Arity.between(0, 2), (receiver, context, arguments, span) -> {
                    Args args = args("text", arguments, context, span);
                    Octets.work(context, self(receiver).size(), span);
                    return StringValue.of(Encodings.text(self(receiver),
                            args.has(0) ? Encodings.of(args.string(0, "кодировка"),
                                    "bytes.text()", span) : Encodings.DEFAULT,
                            args.flag(1, "терпимо", false), "bytes.text()", span));
                })

                .method("slice", Arity.between(1, 2), (receiver, context, arguments, span) -> {
                    Args args = args("slice", arguments, context, span);
                    BytesValue data = self(receiver);
                    int from = Indexes.cut(data.size(), args.integer(0, "начало"));
                    int to = args.has(1) ? Indexes.cut(data.size(), args.integer(1, "конец")) : data.size();
                    return data.slice(from, to);
                })

                // Искать можно и байт, и целую последовательность: подпись формата
                // ищут вторым способом, разделитель пакета — первым.
                .method("indexOf", Arity.between(1, 2), (receiver, context, arguments, span) -> {
                    Args args = args("indexOf", arguments, context, span);
                    BytesValue data = self(receiver);
                    int from = args.has(1) ? Indexes.cut(data.size(), args.integer(1, "с какой позиции")) : 0;
                    Value what = args.at(0);
                    if (what instanceof BytesValue part) {
                        return IntValue.of(data.indexOf(part, from));
                    }
                    Byte octet = Octets.matching(what);
                    if (octet == null) {
                        throw args.wrong(0, "искомое", "ожидался байт (-128..255) или bytes");
                    }
                    return IntValue.of(data.indexOf(octet, from));
                })

                // Ответ берётся у той же реализации, что стоит за 'x in b': член
                // и оператор обязаны отвечать одинаково всегда, а не пока за ними следят.
                .method("contains", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(Overloading.contains(self(receiver), arguments.get(0), span, context)))

                // Подпись файла сравнивают целиком, а не побайтно: одна строка вместо
                // четырёх, и она читается ровно как строка из описания формата.
                .method("startsWith", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(self(receiver).startsWith(
                                args("startsWith", arguments, context, span).bytes(0, "начало"))))
                .method("endsWith", Arity.exactly(1), (receiver, context, arguments, span) ->
                        BoolValue.of(self(receiver).endsWith(
                                args("endsWith", arguments, context, span).bytes(0, "конец"))));

        // Девять членов чтения числа — по словарю, а не списком: вид числа объявлен
        // один раз, и второй его копии здесь быть не должно.
        for (Octets.Kind kind : Octets.Kind.values()) {
            builder.method(kind.id(), Arity.between(1, 2), (receiver, context, arguments, span) -> {
                Args args = args(kind.id(), arguments, context, span);
                BytesValue data = self(receiver);
                int at = start(data, kind, args, span);
                boolean little = Octets.littleEndian(args.string(1, "порядок байтов", "be"),
                        "bytes." + kind.id() + "()", span);
                return kind.read(data, at, little);
            });
        }
        return builder.build();
    }

    /**
     * Позиция, с которой читается число: обычная адресация плюс проверка, что
     * число целиком помещается до конца.
     * <p>
     * Адресация строгая — то же правило, что у {@code b[i]} ({@link Indexes}):
     * вопрос «какое число лежит здесь» у отсутствующей позиции ответа не имеет,
     * а промолчать значило бы спрятать сдвиг в разборе формата на один байт.
     */
    private static int start(BytesValue data, Octets.Kind kind, Args args, Span span) {
        int at = Indexes.element(data.size(), args.integer(0, "позиция"), "байтов", span);
        if (at + kind.size() > data.size()) {
            throw new WdlRuntimeError(ErrorKind.INDEX, span, "bytes." + kind.id() + "(): "
                    + kind.id() + " занимает " + kind.size() + " байт, а от позиции " + at
                    + " до конца их " + (data.size() - at));
        }
        return at;
    }

    private static BytesValue self(Value receiver) {
        return (BytesValue) receiver;
    }

    private static Args args(String name, List<Value> arguments, CallContext context, Span span) {
        return Args.of("bytes." + name, arguments, context, span);
    }
}
