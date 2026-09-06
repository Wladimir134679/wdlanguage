package ru.wds.wdl.tools.service;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Место в документе: куда перейти, где ещё встречается имя.
 *
 * @param document адрес документа
 * @param span     интервал в нём
 */
public record Location(DocumentId document, Span span) {

    public Location {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(span, "span");
    }

    @Override
    public String toString() {
        return document + " " + span;
    }
}
