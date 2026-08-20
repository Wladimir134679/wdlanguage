package ru.wds.wdl.metrics;

import java.time.Duration;

/**
 * Один законченный замер: стадия, над чем она работала и сколько это заняло.
 *
 * @param stage         стадия конвейера
 * @param subject       над чем работали: путь файла или ключ модуля. Нужен затем, чтобы
 *                      на длинном запуске было видно, какой именно модуль тормозит
 * @param module        замер принадлежит импортированному модулю, а не главному файлу.
 *                      Отдельным признаком, а не догадкой по {@code subject}: путь
 *                      главного файла и ключ модуля различить по виду нельзя
 * @param startNanos    момент начала по {@link System#nanoTime()}. Абсолютного смысла
 *                      не имеет — только разностный, и ровно для него и хранится:
 *                      по началу и длительности видно, какой замер вложен в какой
 * @param durationNanos сколько стадия заняла
 * @param thread        имя потока, в котором стадия шла. Внутри запуска потоков может
 *                      быть сколько угодно, и без этого поля «сумма замеров больше
 *                      времени по часам» выглядело бы ошибкой измерения
 */
public record Measurement(Stage stage, String subject, boolean module,
                          long startNanos, long durationNanos, String thread) {

    /** Длительность обычным {@link Duration} — им и удобнее считать снаружи. */
    public Duration duration() {
        return Duration.ofNanos(durationNanos);
    }

    /**
     * Лежит ли этот замер целиком внутри другого.
     * <p>
     * Так видно, что время модуля входит во время скрипта: {@code import} выполняется
     * внутри {@code EXECUTE} главного файла, и складывать их в «итого» нельзя.
     */
    public boolean insideOf(Measurement outer) {
        return startNanos >= outer.startNanos
                && startNanos + durationNanos <= outer.startNanos + outer.durationNanos;
    }
}
