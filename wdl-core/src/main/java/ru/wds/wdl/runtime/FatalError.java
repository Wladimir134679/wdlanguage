package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.List;

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

    private final transient WdlError reason;
    /**
     * Прерывание ли это.
     * <p>
     * Нужно затем, что причину прерывания знает не тот, кто его заметил: библиотека,
     * вышедшая из {@code sleep} по {@code interrupt}, отвечает «выполнение прервано»
     * и не догадывается, что прервал её сторож таймаута. Переименовать такое обратно
     * может только запуск, на границе — см. {@code Run.explain}.
     */
    private final transient boolean interruption;

    private FatalError(String message, Span span, Source source, WdlError reason) {
        this(message, span, source, reason, false);
    }

    private FatalError(String message, Span span, Source source, WdlError reason,
                       boolean interruption) {
        super(message, span, source);
        this.reason = reason;
        this.interruption = interruption;
    }

    /**
     * Скрипт остановлен снаружи обычным {@link Thread#interrupt()}.
     * <p>
     * Три строки на цикл против «приложение висит, и сделать с этим нечего».
     * <p>
     * Открыт наружу ради библиотек, которые блокируются: {@code th.sleep}, ожидание
     * канала, {@code join} с таймаутом. Прерывание там надо сообщать <b>тем же</b>
     * типом, что и в цикле, — иначе {@code while (true) { try { th.sleep(100) } catch (e) {} }}
     * съел бы остановку, и та самая кнопка «прервать» перестала бы работать.
     */
    public static FatalError interrupted(Span span) {
        return new FatalError("выполнение прервано", span, null, null, true);
    }

    /** Прерывание ли это — в отличие от исчерпанных шагов, времени или рекурсии. */
    boolean isInterruption() {
        return interruption;
    }

    /**
     * В скрипт вошли после того, как запуск закрыт.
     * <p>
     * Так бывает у пережившего закрытие потока: он проснулся, дошёл до вызова функции
     * скрипта — а модули запуска уже закрыты. Работать по закрытым ресурсам молча
     * хуже, чем остановиться, и ловить такое обработчиком незачем: это не «операция
     * не удалась», а «запуска больше нет».
     */
    static FatalError runClosed(Span span) {
        return new FatalError("запуск закрыт: вызвать функцию скрипта больше нельзя. "
                + "Остановите потоки скрипта до close() — 't.interrupt()' и 't.join()'",
                span, null, null);
    }

    /**
     * Стек потока кончился раньше, чем счётчик вложенных вызовов
     * ({@link Limits#maxCallDepth()}) — вторая линия защиты от рекурсии.
     * <p>
     * Места в исходнике здесь нет и быть не может: его знал тот кадр, которого уже
     * не существует.
     */
    static FatalError stackExhausted() {
        return new FatalError("стек вызовов исчерпан: рекурсия оказалась глубже, "
                + "чем выдерживает поток. Проверьте условие выхода из рекурсии", Span.NONE, null, null);
    }

    /**
     * Вложенных вызовов больше, чем разрешено пределами запуска.
     *
     * @param limit фактический предел — он же и печатается: константы здесь больше нет,
     *              предел задаёт хозяин запуска ({@link Limits})
     * @param what  что проверить автору скрипта: имя функции или создаваемого класса
     */
    static FatalError tooDeep(Span span, int limit, String what) {
        return new FatalError("слишком глубокая рекурсия: вложенных вызовов больше "
                + limit + ". " + what, span, null, null);
    }

    /**
     * Скрипт исчерпал отведённые ему шаги ({@link Limits#maxSteps()}).
     * <p>
     * Остановка, а не ошибка скрипта, и ровно по той же причине, что у прерывания:
     * {@code while (true) { try { ... } catch (e) {} }} поймал бы такую ошибку
     * и продолжил работу — то есть съел бы собственный предохранитель.
     */
    static FatalError stepsExhausted(Span span, long limit) {
        return new FatalError("выполнение остановлено: скрипт исчерпал отведённые "
                + limit + " шагов. Проверьте условие выхода из цикла или рекурсии",
                span, null, null);
    }

    /**
     * Время запуска вышло ({@link Limits#timeout()}).
     * <p>
     * Приходит двумя путями и называется одинаково: мягкой проверкой дедлайна в
     * {@code Run.checkpoint} и прерыванием от сторожа, если скрипт застрял
     * в блокирующем вызове и до точки проверки уже не дойдёт.
     */
    static FatalError timedOut(Span span, java.time.Duration limit) {
        return new FatalError("выполнение остановлено: вышло отведённое время ("
                + limit.toMillis() + " мс)", span, null, null);
    }

    /**
     * Утверждение {@code try!} не выполнилось.
     * <p>
     * Ошибка не подавлена, а признана дефектом скрипта: ловить её обработчиком
     * неправильно, поэтому наружу идёт остановка выполнения — с исходной ошибкой
     * в {@link #reason()} и с её же местом, чтобы подчёркивание указывало туда,
     * где на самом деле не сложилось.
     */
    static FatalError assertionFailed(WdlError reason) {
        return new FatalError("здесь ошибки быть не должно ('try!'), а случилась — "
                + describe(reason), reason.span(), reason.source(), reason);
    }

    private static String describe(WdlError reason) {
        String kind = reason.kindName();
        return kind == null ? reason.getMessage() : kind + ": " + reason.getMessage();
    }

    /** Ошибка, из-за которой выполнение остановлено, или {@code null}. */
    public WdlError reason() {
        return reason;
    }

    @Override
    public FatalError inSource(Source known) {
        if (source() != null || known == null) {
            return this;
        }
        return new FatalError(getMessage(), span(), known, reason, interruption);
    }

    /** Путь по скрипту берётся у причины: у самой остановки его нет. */
    @Override
    public List<String> trace() {
        return reason == null ? super.trace() : reason.trace();
    }
}
