package ru.wds.wdl.metrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Накопитель замеров одного запуска: он же приёмник для конвейера, он же отчёт
 * для приложения.
 * <p>
 * Принадлежит запуску, а не движку: движок — это рецепт, а замеры — состояние, и одно
 * на всех оно быть не может (см. {@code WdlEngine}). Поэтому у каждого экземпляра
 * скрипта свой накопитель.
 *
 * <h2>Потоки</h2>
 * Замеры приходят из потоков скрипта, а глобального замка в запуске нет, поэтому
 * очередь конкурентная, а суммы — {@link LongAdder}: это ровно тот случай, для которого
 * он сделан — много записи, редкое чтение. Таблицы сумм заполняются в конструкторе
 * и дальше не меняются структурно, так что читать их без синхронизации безопасно.
 */
public final class MetricsCollector implements Metrics, MetricsReport {

    /** Русская локаль в форматировании: «12,4 мс», а не «12.4 мс» — таблицу читает человек. */
    private static final Locale RU = Locale.forLanguageTag("ru");

    private final Consumer<Measurement> listener;
    private final ConcurrentLinkedQueue<Measurement> measurements = new ConcurrentLinkedQueue<>();
    /** Суммы и счётчики: {@code [0]} — главный файл, {@code [1]} — модули. */
    private final Map<Stage, LongAdder[]> totals = new EnumMap<>(Stage.class);
    private final Map<Stage, LongAdder[]> counts = new EnumMap<>(Stage.class);

    private final long startNanos = System.nanoTime();
    /** Момент {@link #finish()} или {@code -1}, пока запуск не закончился. */
    private volatile long finishNanos = -1;

    MetricsCollector(Consumer<Measurement> listener) {
        this.listener = listener;
        for (Stage stage : Stage.values()) {
            totals.put(stage, new LongAdder[]{new LongAdder(), new LongAdder()});
            counts.put(stage, new LongAdder[]{new LongAdder(), new LongAdder()});
        }
    }

    @Override
    public Measure begin(Stage stage, String subject, boolean module) {
        Objects.requireNonNull(stage, "stage");
        return new Open(stage, subject == null ? "" : subject, module);
    }

    /**
     * Фиксирует конец запуска: дальше {@link #wall()} отвечает одним и тем же числом.
     * <p>
     * Идемпотентен — первый вызов и решает. Иначе отчёт, напечатанный дважды, показал
     * бы разное время по часам, хотя между печатями ничего не выполнялось.
     */
    public MetricsCollector finish() {
        if (finishNanos < 0) {
            finishNanos = System.nanoTime();
        }
        return this;
    }

    /**
     * Втягивает замеры чужого отчёта.
     * <p>
     * Нужно там, где стадии одного запуска разнесены по разным объектам: разбор скрипта
     * случается в {@code WdlScript}, а выполнение — в {@code WdlInstance}, и приложению
     * нужен один отчёт на «скомпилировали и выполнили».
     * <p>
     * Слушателю втянутое не сообщается: замер случился раньше, и объявлять его
     * «только что завершившимся» было бы неправдой.
     */
    public void adopt(MetricsReport other) {
        Objects.requireNonNull(other, "other");
        if (other == this) {
            return;
        }
        for (Measurement measurement : other.all()) {
            store(measurement);
        }
    }

    @Override
    public List<Measurement> all() {
        return List.copyOf(measurements);
    }

    @Override
    public Map<Stage, Duration> byStage() {
        Map<Stage, Duration> result = new EnumMap<>(Stage.class);
        for (Stage stage : Stage.values()) {
            result.put(stage, total(stage));
        }
        return result;
    }

    @Override
    public Duration total(Stage stage) {
        LongAdder[] adders = totals.get(stage);
        return Duration.ofNanos(adders[0].sum() + adders[1].sum());
    }

    @Override
    public Duration total(Stage stage, boolean module) {
        return Duration.ofNanos(totals.get(stage)[module ? 1 : 0].sum());
    }

    @Override
    public int count(Stage stage) {
        LongAdder[] adders = counts.get(stage);
        return (int) (adders[0].sum() + adders[1].sum());
    }

    @Override
    public int count(Stage stage, boolean module) {
        return (int) counts.get(stage)[module ? 1 : 0].sum();
    }

    @Override
    public Duration sum() {
        long nanos = 0;
        for (Stage stage : Stage.values()) {
            LongAdder[] adders = totals.get(stage);
            nanos += adders[0].sum() + adders[1].sum();
        }
        return Duration.ofNanos(nanos);
    }

    @Override
    public Duration wall() {
        long end = finishNanos;
        return Duration.ofNanos((end < 0 ? System.nanoTime() : end) - startNanos);
    }

    @Override
    public int threads() {
        Set<String> names = new HashSet<>();
        for (Measurement measurement : measurements) {
            names.add(measurement.thread());
        }
        return names.size();
    }

    @Override
    public boolean isEmpty() {
        return measurements.isEmpty();
    }

    /**
     * Таблица для человека.
     * <p>
     * Стадия без единого замера строки не получает: «выполнение 0,0 мс» у скрипта,
     * который не дошёл до выполнения, читается как «выполнился мгновенно», а это
     * не то же самое, что «не выполнялся».
     */
    @Override
    public String render() {
        if (isEmpty()) {
            return "";
        }
        List<Row> rows = new ArrayList<>(5);
        addRow(rows, Stage.LEX, false, Stage.LEX.title(), null);
        addRow(rows, Stage.PARSE, false, Stage.PARSE.title(), null);
        addRow(rows, Stage.EXECUTE, false, Stage.EXECUTE.title(),
                count(Stage.EXECUTE, true) == 0 ? null
                        : "в т.ч. модули " + millis(total(Stage.EXECUTE, true)));

        long modulesParsed = total(Stage.LEX, true).toNanos() + total(Stage.PARSE, true).toNanos();
        int moduleFiles = count(Stage.PARSE, true);
        if (moduleFiles > 0) {
            rows.add(new Row("модули: разбор", millis(Duration.ofNanos(modulesParsed)),
                    moduleFiles + " " + plural(moduleFiles, "файл", "файла", "файлов")));
        }
        addRow(rows, Stage.SHUTDOWN, false, Stage.SHUTDOWN.title(), null);

        int labelWidth = 0;
        int valueWidth = 0;
        for (Row row : rows) {
            labelWidth = Math.max(labelWidth, row.label.length());
            valueWidth = Math.max(valueWidth, row.value.length());
        }

        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder(256);
        sb.append("── метрики ").append("─".repeat(30)).append(nl);
        for (Row row : rows) {
            sb.append("  ").append(pad(row.label, labelWidth))
                    .append("   ").append(indent(row.value, valueWidth));
            if (row.note != null) {
                sb.append("   ").append(row.note);
            }
            sb.append(nl);
        }
        sb.append("── сумма замеров ").append(millis(sum()))
                .append(" · по часам ").append(millis(wall()));
        int threads = threads();
        if (threads > 1) {
            // Больше одного потока — единственное честное объяснение того, что сумма
            // замеров оказалась больше времени по часам.
            sb.append(" · потоков ").append(threads);
        }
        sb.append(nl);
        return sb.toString();
    }

    private void addRow(List<Row> rows, Stage stage, boolean module, String label, String note) {
        int times = count(stage, module);
        if (times == 0) {
            return;
        }
        String note2 = note != null ? note
                : times > 1 ? times + " " + plural(times, "файл", "файла", "файлов") : null;
        rows.add(new Row(label, millis(total(stage, module)), note2));
    }

    /** Записывает завершённый замер. */
    private void store(Measurement measurement) {
        measurements.add(measurement);
        int slot = measurement.module() ? 1 : 0;
        totals.get(measurement.stage())[slot].add(measurement.durationNanos());
        counts.get(measurement.stage())[slot].increment();
    }

    private static String millis(Duration duration) {
        return String.format(RU, "%.1f мс", duration.toNanos() / 1_000_000.0);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private static String indent(String text, int width) {
        return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
    }

    /** Русское склонение после числа: 1 файл, 2 файла, 5 файлов. */
    private static String plural(int count, String one, String few, String many) {
        int tail = count % 100;
        if (tail >= 11 && tail <= 14) {
            return many;
        }
        return switch (count % 10) {
            case 1 -> one;
            case 2, 3, 4 -> few;
            default -> many;
        };
    }

    /** Строка таблицы: подпись, время, необязательное пояснение справа. */
    private record Row(String label, String value, String note) {
    }

    /**
     * Открытый замер.
     * <p>
     * Живёт в одном потоке от {@code begin} до {@code close}, поэтому флаг закрытия
     * обычный. Повторное закрытие ничего не делает: {@code try}-с-ресурсами закрывает
     * один раз, но чужой код вправе позвать {@code close()} и сам.
     */
    private final class Open implements Measure {

        private final Stage stage;
        private final String subject;
        private final boolean module;
        private final long begin = System.nanoTime();
        private boolean closed;

        private Open(Stage stage, String subject, boolean module) {
            this.stage = stage;
            this.subject = subject;
            this.module = module;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Measurement measurement = new Measurement(stage, subject, module, begin,
                    System.nanoTime() - begin, Thread.currentThread().getName());
            store(measurement);
            if (listener != null) {
                listener.accept(measurement);
            }
        }
    }
}
