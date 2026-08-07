package ru.wds.wdl.lexer;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Токен: вид, полезный текст и место в исходнике.
 * <p>
 * {@code text} — не всегда кусок исходника. Для {@link TokenType#STRING} это уже
 * развёрнутое значение (escape-последовательности заменены, кавычек нет), для чисел —
 * цифры без префикса и без разделителей {@code _}. Исходное написание всегда можно
 * достать из {@link ru.wds.wdl.source.Source} по {@link #span()}.
 *
 * @param type         вид токена
 * @param text         значение для литералов и имён, лексема для остальных
 * @param span         интервал в исходнике
 * @param afterNewline перед токеном был перевод строки
 */
public record Token(TokenType type, String text, Span span, boolean afterNewline) {

    public Token {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(span, "span");
    }

    public Token(TokenType type, String text, Span span) {
        this(type, text, span, false);
    }

    public boolean is(TokenType expected) {
        return type == expected;
    }

    /**
     * Был ли перед этим токеном перевод строки.
     * <p>
     * На грамматику это не влияет: перевод строки — такой же пробел, как остальные,
     * и парсер вправе флаг игнорировать. Он нужен тем, кому важно исходное
     * форматирование: форматтеру — чтобы не склеить весь файл в одну строку, линтеру
     * и диагностике — чтобы подсказать «вы, кажется, начали новую инструкцию».
     */
    @Override
    public boolean afterNewline() {
        return afterNewline;
    }

    @Override
    public String toString() {
        return text.isEmpty() ? type.name() : type.name() + "(" + text + ")";
    }
}
