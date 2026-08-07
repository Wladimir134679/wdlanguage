package ru.wds.wdl.runtime;

import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.diagnostic.Severity;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Ошибка времени выполнения скрипта: деление на ноль, неверный тип, выход за границы.
 * <p>
 * Несёт {@link Span} — место в скрипте, а не в коде интерпретатора. Благодаря этому
 * ошибка выполнения показывается человеку ровно тем же способом, что и ошибка разбора:
 * строкой исходника с подчёркиванием (см. {@link ru.wds.wdl.diagnostic.Diagnostics#render}).
 * <p>
 * Стек вызовов Java не заполняется: он описывает путь по методам интерпретатора,
 * а пользователю скрипта нужен путь по его собственному коду. Заодно это делает
 * создание ошибки дешёвым. Когда появятся функции, сюда добавится стек вызовов
 * самого скрипта.
 */
public class WdlRuntimeError extends RuntimeException {

    private final transient Span span;

    public WdlRuntimeError(Span span, String message) {
        super(message, null, false, false);
        this.span = Objects.requireNonNull(span, "span");
    }

    public Span span() {
        return span;
    }

    /** Ошибка в виде диагностики — чтобы напечатать её тем же кодом, что и ошибки разбора. */
    public Diagnostic toDiagnostic() {
        return new Diagnostic(Severity.ERROR, getMessage(), span);
    }
}
