package ru.wds.wdl.runtime;

import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.diagnostic.Severity;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Всё, чем может кончиться выполнение: место в скрипте плюс сообщение.
 * <p>
 * Наследников ровно два, и различаются они одним — можно ли это поймать из скрипта.
 * {@link WdlRuntimeError} — обычная ошибка выполнения, у неё есть класс в иерархии
 * {@code Exception}, и {@code catch} её видит. {@link FatalError} значит «выполнение
 * больше не может продолжаться», и не ловится ничем, кроме хозяина запуска.
 * <p>
 * Общий предок нужен не ради красоты иерархии, а ради двух вещей, которые для обоих
 * делаются одинаково: приписать ошибке файл на границе выполнения ({@link #inSource})
 * и напечатать её строкой исходника с подчёркиванием ({@link #toDiagnostic}).
 * Иначе каждая граница — вызов функции, загрузка модуля, выполнение файла — ловила бы
 * два типа подряд и повторяла бы код.
 * <p>
 * Хозяин запуска, наоборот, ловит их <b>по отдельности</b>: «скрипт ошибся» и «скрипт
 * остановлен» — разные новости, и решения по ним разные.
 * <p>
 * Стек вызовов Java не заполняется ({@code super(message, null, false, false)}):
 * он описывает путь по методам интерпретатора, а автору скрипта нужен путь по его
 * собственному коду — см. {@link WdlRuntimeError#trace()}.
 */
public abstract sealed class WdlError extends RuntimeException permits WdlRuntimeError, FatalError {

    private final transient Span span;
    private final transient Source source;

    WdlError(String message, Span span, Source source) {
        super(message, null, false, false);
        this.span = Objects.requireNonNull(span, "span");
        this.source = source;
    }

    /** Место в скрипте, а не в коде интерпретатора. */
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
    public abstract WdlError inSource(Source known);

    /**
     * Имя класса ошибки в языке ({@code "IndexError"}) или {@code null}, если его нет.
     * <p>
     * Печатается перед сообщением: по нему сразу видно, что именно можно поймать
     * обработчиком. У {@link FatalError} его нет — ловить нечего.
     */
    public String kindName() {
        return null;
    }

    /**
     * Путь по скрипту от места броска к главному файлу — по строке на вызов.
     * Пустой, если ошибка случилась вне вызовов.
     */
    public List<String> trace() {
        return List.of();
    }

    /**
     * Ошибка в виде диагностики — чтобы напечатать её тем же кодом, что и ошибки разбора.
     * <p>
     * Вид ошибки идёт в текст, а не отдельным полем: {@code Diagnostic} — это то, что
     * показывают человеку, и «{@code IndexError: индекс 5 вне границ}» человек читает
     * одной строкой.
     */
    public Diagnostic toDiagnostic() {
        String kind = kindName();
        return new Diagnostic(Severity.ERROR,
                kind == null ? getMessage() : kind + ": " + getMessage(), span);
    }
}
