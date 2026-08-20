package ru.wds.wdl.metrics;

import java.util.function.Consumer;

/**
 * Куда движок сообщает о времени стадий.
 * <p>
 * Метрики — <b>зависимость, а не статика</b>, ровно как {@link ru.wds.wdl.runtime.Output}
 * и {@code Diagnostics}: приёмник создаёт хозяин запуска и передаёт его в конвейер.
 * Счётчик в статическом поле сложил бы вместе два интерпретатора, работающих в одном
 * процессе, — а изоляция запусков есть то, ради чего в ядре нет изменяемой статики.
 * <p>
 * <b>По умолчанию метрик нет.</b> {@link #off()} — синглтон, чей {@code begin} отдаёт
 * себя же, а {@code close()} пуст: на выключенном пути нет ни аллокации, ни обращения
 * к часам. Движок, встроенный в чужое приложение, не платит за то, чего не просил, —
 * то же правило, что у вывода в никуда.
 * <p>
 * Стадию меряет тот, кто её запускает, а не она сама: {@code Lexer.tokenize}
 * и {@code Parser.parseProgram} об этом интерфейсе не знают вовсе.
 * <pre>{@code
 * MetricsCollector metrics = Metrics.collecting();
 * Measure measure = metrics.begin(Stage.LEX, "script.wdl");
 * try {
 *     tokens = Lexer.tokenize(source, diagnostics);
 * } finally {
 *     measure.close();
 * }
 * }</pre>
 * Почему {@code finally}, а не {@code try}-с-ресурсами, разобрано в {@link Measure}.
 */
public interface Metrics {

    /**
     * Открывает замер. Закрывать обязательно — обычно {@code try}-с-ресурсами.
     *
     * @param stage   стадия конвейера
     * @param subject путь файла или ключ модуля
     * @param module  замер принадлежит импортированному модулю
     */
    Measure begin(Stage stage, String subject, boolean module);

    /** То же для главного файла: {@code module = false}. */
    default Measure begin(Stage stage, String subject) {
        return begin(stage, subject, false);
    }

    /**
     * Ничего не измеряет и ничего не помнит — значение по умолчанию везде, где
     * приёмник не задан явно.
     */
    static Metrics off() {
        return NoMetrics.INSTANCE;
    }

    /** Накопитель: помнит все замеры и отдаёт их отчётом. */
    static MetricsCollector collecting() {
        return new MetricsCollector(null);
    }

    /**
     * Накопитель, который вдобавок сообщает о каждом замере сразу по его завершении.
     * <p>
     * Это и есть «включить логирование этапа»: слушателю не нужно ждать конца запуска
     * — он получает стадию тогда, когда она закончилась, и волен писать её в свой лог
     * или в свою систему метрик. Зовётся слушатель в том потоке, где стадия шла,
     * поэтому долгой работы в нём делать не стоит.
     */
    static MetricsCollector collecting(Consumer<Measurement> listener) {
        return new MetricsCollector(listener);
    }
}
