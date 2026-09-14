package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.types.BytesValue;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Перевод между текстом и байтами: одно правило на весь язык.
 *
 * <h2>UTF-8 по умолчанию, другая кодировка — явным аргументом</h2>
 * Скрипт не должен угадывать кодировку, а движок — зависеть от настроек машины,
 * на которой его запустили. Это то же решение, что у {@code sys.io}, просто записанное
 * теперь в одном месте: {@code b.text()} и {@code "…".bytes} берут UTF-8,
 * {@code b.text("cp1251")} и {@code "…".encode("cp1251")} — то, что попросили.
 *
 * <h2>Неверная последовательность — ошибка, а не {@code U+FFFD}</h2>
 * Декодер Java по умолчанию подменяет непарные байты символом-заменителем, и это
 * ровно та беда, из-за которой байты и заводились: испорченные данные превращаются
 * в правдоподобный текст, из которого исходного файла уже не собрать. Здесь отказ:
 * {@code CodingErrorAction.REPORT} и ошибка скрипта с местом в исходнике. Кому нужна
 * терпимость — просит её вторым аргументом и осознанно: {@code b.text("utf-8", true)}.
 */
public final class Encodings {

    private Encodings() {
    }

    /** Кодировка по умолчанию — та же, что у файлов и сокетов. */
    public static final Charset DEFAULT = StandardCharsets.UTF_8;

    /**
     * Кодировка по имени. Промах — ошибка значения: имя кодировки автор написал
     * руками, и молча подставить вместо него UTF-8 значило бы испортить данные
     * там, где он как раз позаботился о кодировке.
     */
    public static Charset of(String name, String what, Span span) {
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException unknown) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": неизвестная кодировка \""
                    + name + "\". Известны, например: utf-8, utf-16, cp1251, koi8-r, iso-8859-1");
        }
    }

    /**
     * Байты в текст.
     *
     * @param lenient {@code true} — испорченные последовательности заменяются на
     *                {@code U+FFFD} вместо отказа; просят это вторым аргументом и осознанно
     */
    public static String text(BytesValue data, Charset charset, boolean lenient,
                              String what, Span span) {
        byte[] raw = data.toArray();
        if (lenient) {
            return new String(raw, charset);
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException broken) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": это не текст в "
                    + charset.name() + " — последовательность байтов не декодируется. "
                    + "Если испорченное можно заменить знаком замены, попросите об этом "
                    + "вторым аргументом: text(\"" + charset.name().toLowerCase(java.util.Locale.ROOT)
                    + "\", true)");
        }
    }

    /**
     * Текст в байты.
     * <p>
     * Отказ здесь по той же причине, что и при чтении, только с другой стороны:
     * символ, которого в целевой кодировке нет ({@code "я"} в {@code iso-8859-1}),
     * молча превратился бы в {@code ?}, и обратно того же текста уже не собрать.
     */
    public static BytesValue bytes(String text, Charset charset, String what, Span span) {
        if (charset.equals(StandardCharsets.UTF_8)) {
            // UTF-8 кодирует любую строку без потерь, включая непарные суррогаты
            // (они станут '?'), — отдельного отказа тут не бывает.
            return BytesValue.owning(text.getBytes(StandardCharsets.UTF_8));
        }
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
            byte[] out = new byte[encoded.remaining()];
            encoded.get(out);
            return BytesValue.owning(out);
        } catch (CharacterCodingException impossible) {
            throw new WdlRuntimeError(ErrorKind.VALUE, span, what + ": текст не записывается в "
                    + charset.name() + " — в этой кодировке нет таких символов");
        }
    }
}
