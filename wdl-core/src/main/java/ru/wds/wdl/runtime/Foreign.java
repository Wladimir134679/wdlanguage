package ru.wds.wdl.runtime;

import ru.wds.wdl.source.Span;

/**
 * Вызов чужого кода: своё пропустить наверх, чужое завернуть.
 * <p>
 * Чужой здесь — всё, что написано <b>не движком</b>: тело встроенной функции,
 * конструктор нативного класса, аксессор свойства от приложения, метод Java,
 * достигнутый через мост. Оттуда может прилететь что угодно, и для автора скрипта
 * это не должно выглядеть как крах движка.
 *
 * <h2>Одно правило на весь движок</h2>
 * Правило «что считать своим» жило в двух местах сразу — в {@link Interpreter}
 * вокруг вызова и в мосте вокруг Java-метода, — и в двух местах разошлось:
 * ядро заворачивало только {@code RuntimeException} и пропускало сигналы по типу,
 * мост ловил всё и опознавал своё по имени пакета. Здесь оно записано один раз:
 * <ul>
 *   <li>{@link WdlError} и {@link ControlSignal} — <b>своё</b>: у ошибки скрипта уже
 *       есть место, текст и класс, а {@code return} из обработчика обязан дойти
 *       до своей функции. Заворачивать их значит подменять причину;</li>
 *   <li>{@code RuntimeException}, проверяемые исключения и {@link LinkageError} —
 *       <b>чужое</b>: становятся {@link ErrorKind#JAVA};</li>
 *   <li>остальные {@code Error} ({@code OutOfMemoryError}, {@code StackOverflowError})
 *       идут наверх нетронутыми. Это отказ виртуальной машины, а не ошибка
 *       библиотеки, и делать вид, что скрипт может его поймать, — обман.</li>
 * </ul>
 *
 * <h2>Почему не try-catch на месте</h2>
 * Потому что мест таких пять и будет больше: вызов, создание, чтение свойства,
 * запись свойства, метод через мост. Пока правило переписывалось руками, чтение
 * свойства нативного класса его вовсе не имело — исключение из аксессора уходило
 * наружу как крах движка.
 */
public final class Foreign {

    /** Тело вызова, которому позволено бросить что угодно. */
    @FunctionalInterface
    public interface Body<T> {

        T get() throws Throwable;
    }

    private Foreign() {
    }

    /**
     * Зовёт чужой код и заворачивает то, что вылетело.
     *
     * @param subject что звали — {@code "Date.plusDays()"}; {@code null}, если текст
     *                ошибки надо взять у самого исключения (так делает ядро: там имя
     *                уже названо в трассировке)
     * @param module  имя модуля, из которого прилетело, или {@code null}
     */
    public static <T> T call(Span span, String subject, String module, Body<T> body) {
        try {
            return body.get();
        } catch (WdlRuntimeError own) {
            // Библиотека вправе бросить ошибку языка и не знать при этом места
            // в скрипте: место знает эта граница, она его и проставляет.
            throw own.at(span);
        } catch (WdlError | ControlSignal known) {
            throw known;
        } catch (Throwable failure) {
            throw wrap(failure, span, subject, module);
        }
    }

    /**
     * Ошибка скрипта из чужого исключения — для тех, кто поймал его сам.
     * <p>
     * {@code Error}, кроме {@link LinkageError}, отсюда уходит наверх как есть:
     * см. правило в описании класса.
     */
    public static RuntimeException wrap(Throwable failure, Span span, String subject, String module) {
        if (failure instanceof WdlRuntimeError own) {
            return own.at(span);
        }
        if (failure instanceof WdlError || failure instanceof ControlSignal) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error error && !(failure instanceof LinkageError)) {
            throw error;
        }
        if (subject == null) {
            return WdlRuntimeError.fromJava(span, failure, module);
        }
        String message = failure.getMessage();
        return WdlRuntimeError.fromJava(span, subject + ": " + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message), failure, module);
    }
}
