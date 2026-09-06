package ru.wds.wdl.tools.service;

import java.util.Objects;

/** Сигнатура известного вызова, независимая от конкретного протокола редактора. */
public record CallSignature(String label, String documentation, int activeParameter) {

    public CallSignature {
        Objects.requireNonNull(label, "label");
        activeParameter = Math.max(0, activeParameter);
    }
}
