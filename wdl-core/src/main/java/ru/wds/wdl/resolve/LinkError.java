package ru.wds.wdl.resolve;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Связать класс не удалось: родителю передано не столько аргументов, требование трейта
 * не выполнено.
 * <p>
 * Своё исключение, а не {@code WdlRuntimeError} и не запись в {@code Diagnostics},
 * ровно по одной причине: связывание нужно двум разным стадиям, и адресат ошибки
 * у них разный. Интерпретатор превращает её в ошибку выполнения с местом в исходнике,
 * статическая проверка — в диагностику. Знай {@link Linker} про любого из них, он бы
 * годился только одному, а зависимость {@code resolve → runtime} вдобавок развернула бы
 * слои задом наперёд.
 * <p>
 * Стек вызовов Java не заполняется — как и у ошибки выполнения: он описывает путь
 * по методам движка, а нужен путь по коду скрипта, и он весь в {@link #span()}.
 */
public final class LinkError extends RuntimeException {

    private final transient Span span;

    public LinkError(Span span, String message) {
        super(message, null, false, false);
        this.span = Objects.requireNonNull(span, "span");
    }

    /** Место в исходнике: объявление класса или ссылка на родителя. */
    public Span span() {
        return span;
    }
}
