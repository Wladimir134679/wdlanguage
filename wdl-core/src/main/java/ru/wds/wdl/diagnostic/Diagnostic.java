package ru.wds.wdl.diagnostic;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Одна найденная проблема: что не так и где именно.
 * <p>
 * Диагностика не знает исходный текст — только интервал в нём. Печатью с показом
 * строки кода занимается {@link Diagnostics}, у которого есть
 * {@link ru.wds.wdl.source.Source}.
 *
 * @param severity серьёзность
 * @param message  текст для человека, без имени файла и позиции — их добавит форматтер
 * @param span     интервал в исходнике, к которому относится сообщение
 */
public record Diagnostic(Severity severity, String message, Span span) {

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return severity.title() + ": " + message + " (" + span + ")";
    }
}
