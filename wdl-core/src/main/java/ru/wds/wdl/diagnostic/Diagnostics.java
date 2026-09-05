package ru.wds.wdl.diagnostic;

import ru.wds.wdl.source.Position;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Накопитель диагностики одного разбора.
 * <p>
 * Лексер и парсер не бросают исключение на первой же ошибке: пользователь должен
 * увидеть все проблемы файла за один запуск. Исключение — задача вызывающего кода,
 * когда он решит, что дальше идти незачем.
 * <p>
 * Класс не потокобезопасен и рассчитан на один разбор: свой экземпляр на каждый файл.
 */
public final class Diagnostics {

    /**
     * Предел, после которого сообщения перестают копиться. Одна ошибка в начале файла
     * легко порождает сотни производных — вываливать их все бессмысленно.
     */
    public static final int LIMIT = 100;

    private final Source source;
    private final List<Diagnostic> items = new ArrayList<>();
    private int errorCount;
    private boolean limitReported;

    public Diagnostics(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public Source source() {
        return source;
    }

    public void error(Span span, String message) {
        add(new Diagnostic(Severity.ERROR, DiagnosticCode.NONE, message, span));
    }

    /** То же с видом проблемы: код нужен редактору — см. {@link DiagnosticCode}. */
    public void error(Span span, DiagnosticCode code, String message) {
        add(new Diagnostic(Severity.ERROR, code, message, span));
    }

    public void warning(Span span, String message) {
        add(new Diagnostic(Severity.WARNING, DiagnosticCode.NONE, message, span));
    }

    /** То же с видом проблемы. */
    public void warning(Span span, DiagnosticCode code, String message) {
        add(new Diagnostic(Severity.WARNING, code, message, span));
    }

    public void add(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (items.size() >= LIMIT) {
            if (!limitReported) {
                limitReported = true;
                items.add(new Diagnostic(Severity.ERROR,
                        "слишком много ошибок, остальные не показаны", diagnostic.span()));
            }
            return;
        }
        if (diagnostic.severity() == Severity.ERROR) {
            errorCount++;
        }
        items.add(diagnostic);
    }

    /** Сообщения в порядке появления. */
    public List<Diagnostic> all() {
        return Collections.unmodifiableList(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean hasErrors() {
        return errorCount > 0;
    }

    public int errorCount() {
        return errorCount;
    }

    /**
     * Готовит сообщение для человека: место, текст и сама строка исходника
     * с подчёркиванием проблемного места.
     * <pre>
     * showcase.wdl:3:9: ошибка: неизвестный символ '@'
     *   3 | total = @value
     *     |         ^
     * </pre>
     */
    public String render(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        StringBuilder sb = new StringBuilder(128);
        Span span = diagnostic.span();
        if (span.isNone()) {
            return sb.append(source.name()).append(": ")
                    .append(diagnostic.severity().title()).append(": ")
                    .append(diagnostic.message()).toString();
        }

        int start = Math.min(span.start(), source.length());
        Position position = source.positionOf(start);
        sb.append(source.name()).append(':').append(position).append(": ")
                .append(diagnostic.severity().title()).append(": ")
                .append(diagnostic.message()).append('\n');

        String line = source.lineText(position.line());
        String gutter = String.valueOf(position.line());
        sb.append("  ").append(gutter).append(" | ").append(line).append('\n');
        sb.append("  ").append(" ".repeat(gutter.length())).append(" | ");
        // Подчёркивание не переносим на следующие строки: хвост длинного интервала обрезаем.
        int column = Math.min(position.column() - 1, line.length());
        int width = Math.max(1, Math.min(Math.max(span.length(), 1), line.length() - column));
        sb.append(" ".repeat(column)).append("^".repeat(width));
        return sb.toString();
    }

    /** Все сообщения подряд, каждое — блоком из {@link #render(Diagnostic)}. */
    public String renderAll() {
        StringBuilder sb = new StringBuilder(256);
        for (Diagnostic diagnostic : items) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(render(diagnostic)).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Diagnostics[" + source.name() + ", " + items.size() + " сообщений]";
    }
}
