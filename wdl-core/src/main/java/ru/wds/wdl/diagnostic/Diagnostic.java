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
 * @param code     вид проблемы для машины или {@link DiagnosticCode#NONE};
 *                 текст меняется свободно, код — нет
 * @param message  текст для человека, без имени файла и позиции — их добавит форматтер
 * @param span     интервал в исходнике, к которому относится сообщение
 */
public record Diagnostic(Severity severity, DiagnosticCode code, String message, Span span) {

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        code = code == null ? DiagnosticCode.NONE : code;
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(span, "span");
    }

    /** Сообщение без кода — то же самое, что было до появления кодов. */
    public Diagnostic(Severity severity, String message, Span span) {
        this(severity, DiagnosticCode.NONE, message, span);
    }

    @Override
    public String toString() {
        return severity.title() + ": " + message + " (" + span + ")";
    }
}
