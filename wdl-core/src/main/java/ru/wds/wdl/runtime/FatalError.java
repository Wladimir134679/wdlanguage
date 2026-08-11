package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

/**
 * Выполнение больше не может продолжаться — и скрипт с этим ничего сделать не может.
 * <p>
 * <b>Вне иерархии {@code Exception} языка</b>: не ловится ни {@code catch (e is ...)},
 * ни {@code catch (e)}, ни {@code try?}. Причина простая — все ситуации отсюда значат
 * «выполнение остановлено», а не «эта операция не удалась». Скрипт, который «поймал»
 * прерывание потока и продолжил работу, — ровно тот случай, ради которого приложение
 * и звало {@link Thread#interrupt()}; {@code while (true) { try { ... } catch (e) {} }}
 * съел бы его молча.
 * <p>
 * {@code finally}, {@code defer} и закрытие ресурсов при этом <b>выполняются</b>:
 * закрыть файл на пути наружу можно и нужно, а вот подавить причину — нет.
 * <p>
 * Хозяину запуска приходит отдельным типом, и по нему видно, что скрипт не просто
 * ошибся, а был остановлен.
 */
public final class FatalError extends WdlError {

    private FatalError(String message, Span span, Source source) {
        super(message, span, source);
    }

    /**
     * Скрипт остановлен снаружи обычным {@link Thread#interrupt()}.
     * <p>
     * Три строки на цикл против «приложение висит, и сделать с этим нечего».
     */
    static FatalError interrupted(Span span) {
        return new FatalError("выполнение прервано", span, null);
    }

    /**
     * Стек потока кончился раньше, чем счётчик вложенных вызовов
     * ({@link ExecutionContext#MAX_CALL_DEPTH}) — вторая линия защиты от рекурсии.
     * <p>
     * Места в исходнике здесь нет и быть не может: его знал тот кадр, которого уже
     * не существует.
     */
    static FatalError stackExhausted() {
        return new FatalError("стек вызовов исчерпан: рекурсия оказалась глубже, "
                + "чем выдерживает поток. Проверьте условие выхода из рекурсии", Span.NONE, null);
    }

    /**
     * Вложенных вызовов больше, чем {@link ExecutionContext#MAX_CALL_DEPTH}.
     *
     * @param what что проверить автору скрипта: имя функции или создаваемого класса
     */
    static FatalError tooDeep(Span span, String what) {
        return new FatalError("слишком глубокая рекурсия: вложенных вызовов больше "
                + ExecutionContext.MAX_CALL_DEPTH + ". " + what, span, null);
    }

    @Override
    public FatalError inSource(Source known) {
        if (source() != null || known == null) {
            return this;
        }
        return new FatalError(getMessage(), span(), known);
    }
}
