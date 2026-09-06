package ru.wds.wdl.tools.service;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.tools.catalog.Origin;

import java.util.Objects;

/**
 * Что показать по наведению: сигнатура, описание и место, которое подсвечивается.
 * <p>
 * Разметки здесь нет — ни Markdown, ни HTML: как оформить, решает тот, кто
 * показывает. Сервису это неизвестно, а угадав однажды, он навяжет свой выбор
 * и плагину, и встроенному редактору.
 *
 * @param signature     краткая запись объявления: {@code total(price, count = 1)}
 * @param documentation описание словами или {@code null}
 * @param kind          чем объявлено имя
 * @param origin        откуда оно: файл, язык, библиотека, модуль, приложение
 * @param span          интервал имени под курсором
 */
public record Hover(String signature, String documentation, SymbolKind kind,
                    Origin origin, Span span) {

    public Hover {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(span, "span");
    }

    public boolean hasDocumentation() {
        return documentation != null && !documentation.isBlank();
    }

    @Override
    public String toString() {
        return signature + " (" + origin.title() + ")";
    }
}
