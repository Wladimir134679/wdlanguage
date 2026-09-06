package ru.wds.wdl.tools.service;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Один окрашенный кусок текста.
 * <p>
 * Интервалы идут слева направо и не пересекаются, но покрывают документ
 * <b>не целиком</b>: пробелы, скобки и незнакомые имена цвета не несут, и выдумывать
 * им вид значило бы красить наугад.
 *
 * @param span        интервал в тексте; может пересекать перевод строки — резать его
 *                    по строкам приходится протоколу, а не тому, кто читает текст
 * @param style       чем красить
 * @param declaration стоит ли здесь <b>объявление</b> имени, а не его употребление
 */
public record HighlightToken(Span span, TokenStyle style, boolean declaration) {

    public HighlightToken {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(style, "style");
    }

    public HighlightToken(Span span, TokenStyle style) {
        this(span, style, false);
    }

    @Override
    public String toString() {
        return style + " " + span + (declaration ? " (объявление)" : "");
    }
}
