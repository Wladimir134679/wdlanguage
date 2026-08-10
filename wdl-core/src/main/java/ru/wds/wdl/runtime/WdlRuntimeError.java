package ru.wds.wdl.runtime;

import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.diagnostic.Severity;
import ru.wds.wdl.source.Source;
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
    private final transient Source source;

    public WdlRuntimeError(Span span, String message) {
        this(span, message, null);
    }

    private WdlRuntimeError(Span span, String message, Source source) {
        super(message, null, false, false);
        this.span = Objects.requireNonNull(span, "span");
        this.source = source;
    }

    public Span span() {
        return span;
    }

    /**
     * Файл, в котором стоит {@link #span()}, или {@code null}, если он неизвестен.
     * <p>
     * Смещение само по себе ничего не значит: с появлением {@code import} выполняются
     * несколько файлов сразу, и одно и то же число указывает в каждом из них на разное
     * место. Кто печатает ошибку, тот и обязан взять исходник отсюда.
     */
    public Source source() {
        return source;
    }

    /**
     * Та же ошибка, но с проставленным файлом.
     * <p>
     * Проставляет его тот, кто первым узнаёт ответ, — граница выполнения файла:
     * вызов функции знает юнит своего объявления, загрузка модуля знает его исходник.
     * Уже отвеченный вопрос второй раз не задаётся: ошибка, поднимающаяся из модуля
     * через вызов из главного скрипта, остаётся ошибкой модуля.
     */
    public WdlRuntimeError inSource(Source known) {
        if (source != null || known == null) {
            return this;
        }
        return new WdlRuntimeError(span, getMessage(), known);
    }

    /** Ошибка в виде диагностики — чтобы напечатать её тем же кодом, что и ошибки разбора. */
    public Diagnostic toDiagnostic() {
        return new Diagnostic(Severity.ERROR, getMessage(), span);
    }
}
