package ru.wds.wdl.tools.service;

import ru.wds.wdl.tools.analysis.SymbolKind;

import java.util.Objects;

/** Экспортированное объявление из проиндексированной рабочей папки. */
public record WorkspaceSymbol(String name, SymbolKind kind, String container,
                              DocumentId document, ru.wds.wdl.source.Span span) {
    public WorkspaceSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(span, "span");
    }
}
