package ru.wds.wdl.profile;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Накопитель профиля одного запуска: он же приёмник для точек съёма, он же отчёт
 * для приложения.
 * <p>
 * Принадлежит запуску, а не движку: движок — это рецепт, а счётчики — состояние,
 * и одно на всех оно быть не может. У каждого экземпляра скрипта свой накопитель.
 *
 * <h2>Как считается «сам»</h2>
 * У каждого открытого вызова есть счётчик времени детей. Закрываясь, вызов прибавляет
 * своё «всего» к счётчику родителя — поэтому у родителя «сам» получается вычитанием,
 * а не отдельным измерением. Это тот же приём, которым считают собственное время все
 * инструментирующие профилировщики, и он единственный, который не требует обходить
 * стек на каждом шаге.
 *
 * <h2>Потоки</h2>
 * Стек открытых вызовов — <b>у каждого потока свой</b>: внутри запуска потоков сколько
 * угодно, глобального замка нет, а вложенность вызовов — свойство потока, а не запуска.
 * Итоги, наоборот, общие: {@link LongAdder} — ровно тот случай, для которого он сделан
 * (много записи, редкое чтение), а максимум держит {@link LongAccumulator}.
 * <p>
 * Опустевший стек снимается с потока целиком ({@code ThreadLocal.remove}) — по той же
 * причине, по которой это делает {@code Run.leave}: поток из пула живёт дольше запуска,
 * и оставленная запись тянула бы за собой ссылку на закончившийся сеанс. Пустой стек
 * ничего не помнит, поэтому потерять при этом нечего.
 */
public final class CallProfiler implements Profiler, ProfileReport {

    /** Русская локаль в форматировании: «12,40 мс», как и вся остальная диагностика. */
    private static final Locale RU = Locale.forLanguageTag("ru");

    /** Сколько строк показывает таблица: дальше идёт хвост, который никто не читает. */
    private static final int RENDERED_ROWS = 15;

    private final ConcurrentHashMap<CallSite, Stats> sites = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Edge, Stats> edges = new ConcurrentHashMap<>();
    /**
     * Имена потоков, что-нибудь позвавших: по ним отчёт объясняет разницу со временем
     * по часам.
     */
    private final Set<String> threads = ConcurrentHashMap.newKeySet();

    /**
     * Стек открытых вызовов текущего потока.
     * <p>
     * Вместе со стеком — карта «место → глубина вложенности в этом потоке»: по ней
     * узнаётся рекурсивный вход, чьё «всего» второй раз складывать нельзя. Обход стека
     * дал бы тот же ответ, но стоил бы глубины на каждый вызов.
     */
    private final ThreadLocal<Trace> traces = ThreadLocal.withInitial(Trace::new);

    private final long startNanos = System.nanoTime();
    /** Момент {@link #finish()} или {@code -1}, пока запуск не закончился. */
    private volatile long finishNanos = -1;

    CallProfiler() {
    }

    @Override
    public Probe enter(CallSite site) {
        Objects.requireNonNull(site, "site");
        Trace trace = traces.get();
        if (trace.stack.isEmpty()) {
            threads.add(Thread.currentThread().getName());
        }
        int[] depth = trace.depth.computeIfAbsent(site, key -> new int[1]);
        Open open = new Open(site, trace.stack.peek(), trace, depth, depth[0]++ > 0);
        trace.stack.push(open);
        return open;
    }

    @Override
    public boolean recording() {
        return true;
    }

    /**
     * Фиксирует конец запуска: дальше {@link #wall()} отвечает одним и тем же числом.
     * <p>
     * Идемпотентен — первый вызов и решает. Иначе отчёт, напечатанный дважды, показал бы
     * разное время, хотя между печатями ничего не выполнялось.
     */
    public CallProfiler finish() {
        if (finishNanos < 0) {
            finishNanos = System.nanoTime();
        }
        return this;
    }

    @Override
    public List<CallProfile> all() {
        List<CallProfile> profiles = new ArrayList<>(sites.size());
        sites.forEach((site, stats) -> profiles.add(new CallProfile(site, stats.calls.sum(),
                stats.total.sum(), stats.self.sum(), stats.max.get())));
        // По собственному времени: горячее место — то, где скрипт работает сам,
        // а не то, откуда он раздаёт работу. Имя вторым ключом, чтобы порядок
        // не зависел от того, в каком порядке карта отдала записи.
        profiles.sort(Comparator.comparingLong(CallProfile::selfNanos).reversed()
                .thenComparing(profile -> profile.site().title()));
        return List.copyOf(profiles);
    }

    @Override
    public List<CallProfile> hottest(int limit) {
        List<CallProfile> profiles = all();
        return limit <= 0 || limit >= profiles.size()
                ? profiles
                : List.copyOf(profiles.subList(0, limit));
    }

    @Override
    public List<CallEdge> edges() {
        List<CallEdge> result = new ArrayList<>(edges.size());
        edges.forEach((edge, stats) -> result.add(new CallEdge(edge.caller(), edge.callee(),
                stats.calls.sum(), stats.total.sum())));
        result.sort(Comparator.comparingLong(CallEdge::totalNanos).reversed()
                .thenComparing(edge -> edge.callee().title()));
        return List.copyOf(result);
    }

    @Override
    public long calls() {
        long total = 0;
        for (Stats stats : sites.values()) {
            total += stats.calls.sum();
        }
        return total;
    }

    @Override
    public Duration self() {
        long nanos = 0;
        for (Stats stats : sites.values()) {
            nanos += stats.self.sum();
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
        return threads.size();
    }

    @Override
    public boolean isEmpty() {
        return sites.isEmpty();
    }

    /**
     * Таблица для человека: самые горячие места сверху.
     * <p>
     * Строк ровно {@link #RENDERED_ROWS}, а остаток назван числом: профиль длинного
     * скрипта — это сотни имён, из которых читают первый десяток, а полный список
     * всегда доступен геттерами.
     */
    @Override
    public String render() {
        if (isEmpty()) {
            return "";
        }
        List<CallProfile> profiles = all();
        int shown = Math.min(RENDERED_ROWS, profiles.size());
        List<Row> rows = new ArrayList<>(shown + 1);
        rows.add(new Row("что", "вызовов", "всего", "сам", "макс"));
        for (CallProfile profile : profiles.subList(0, shown)) {
            rows.add(new Row(profile.site().title(), String.valueOf(profile.calls()),
                    millis(profile.totalNanos()), millis(profile.selfNanos()),
                    millis(profile.maxNanos())));
        }

        int[] widths = new int[Row.COLUMNS];
        for (Row row : rows) {
            for (int column = 0; column < Row.COLUMNS; column++) {
                widths[column] = Math.max(widths[column], row.cell(column).length());
            }
        }

        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder(512);
        sb.append("── профиль ").append("─".repeat(30)).append(nl);
        for (Row row : rows) {
            sb.append("  ").append(pad(row.what(), widths[0]));
            for (int column = 1; column < Row.COLUMNS; column++) {
                sb.append("   ").append(indent(row.cell(column), widths[column]));
            }
            sb.append(nl);
        }
        int hidden = profiles.size() - shown;
        if (hidden > 0) {
            sb.append("  … и ещё ").append(hidden).append(' ')
                    .append(plural(hidden, "место", "места", "мест")).append(nl);
        }
        sb.append("── вызовов ").append(calls())
                .append(" · сам ").append(millis(self().toNanos()))
                .append(" · по часам ").append(millis(wall().toNanos()));
        int workers = threads();
        if (workers > 1) {
            // Больше одного потока — единственное честное объяснение того, что сумма
            // собственного времени оказалась больше времени по часам.
            sb.append(" · потоков ").append(workers);
        }
        sb.append(nl);
        return sb.toString();
    }

    /** Итоги места или ребра: заводятся при первом попадании и дальше только растут. */
    private static final class Stats {

        private final LongAdder calls = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final LongAdder self = new LongAdder();
        private final LongAccumulator max = new LongAccumulator(Math::max, 0);
    }

    /** Ключ ребра графа вызовов. */
    private record Edge(CallSite caller, CallSite callee) {
    }

    /** Стек открытых вызовов одного потока и глубины по местам. */
    private static final class Trace {

        private final ArrayDeque<Open> stack = new ArrayDeque<>();
        private final Map<CallSite, int[]> depth = new HashMap<>();
    }

    /** Строка таблицы: подпись и четыре числа. */
    private record Row(String what, String calls, String total, String self, String max) {

        private static final int COLUMNS = 5;

        String cell(int column) {
            return switch (column) {
                case 0 -> what;
                case 1 -> calls;
                case 2 -> total;
                case 3 -> self;
                default -> max;
            };
        }
    }

    /**
     * Открытый вызов.
     * <p>
     * Живёт в одном потоке от {@code enter} до {@code close}, поэтому счётчик детей
     * и флаг закрытия обычные, без синхронизации: в общие счётчики всё уходит один раз,
     * при закрытии. Повторное закрытие ничего не делает — точки съёма закрывают счёт
     * из {@code finally}, но чужой код вправе позвать {@code close()} и сам.
     */
    private final class Open implements Probe {

        private final CallSite site;
        private final Open parent;
        private final Trace trace;
        private final int[] depth;
        /** Вложенный вход в то же место: его «всего» уже посчитано внешним входом. */
        private final boolean recursive;
        private final long start = System.nanoTime();
        /** Время вложенных вызовов — из него и получается «сам». */
        private long children;
        private boolean closed;

        private Open(CallSite site, Open parent, Trace trace, int[] depth, boolean recursive) {
            this.site = site;
            this.parent = parent;
            this.trace = trace;
            this.depth = depth;
            this.recursive = recursive;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            long total = System.nanoTime() - start;
            unwind();
            Stats stats = sites.computeIfAbsent(site, key -> new Stats());
            stats.calls.increment();
            stats.self.add(total - children);
            if (!recursive) {
                // Рекурсивный вход своё «всего» не добавляет: те же наносекунды уже
                // посчитаны внешним входом, и сумма выросла бы вдвое на каждом уровне.
                stats.total.add(total);
            }
            stats.max.accumulate(total);
            if (parent != null) {
                parent.children += total;
                Stats edge = edges.computeIfAbsent(new Edge(parent.site, site), key -> new Stats());
                edge.calls.increment();
                edge.total.add(total);
            }
        }

        /**
         * Снимает кадр со стека потока.
         * <p>
         * Обычно это вершина: точки съёма закрывают счёт из {@code finally}, то есть
         * строго в обратном порядке. Поиск по стеку оставлен на случай чужого кода,
         * закрывшего счёт руками и не по порядку, — потерять из-за него весь стек
         * потока было бы дороже, чем один обход.
         */
        private void unwind() {
            if (trace.stack.peek() == this) {
                trace.stack.pop();
            } else {
                trace.stack.remove(this);
            }
            depth[0]--;
            if (trace.stack.isEmpty()) {
                // Пустой стек ничего не помнит, а поток может быть из пула, который
                // переживёт запуск: не держим на нём записи.
                traces.remove();
            }
        }
    }

    private static String millis(long nanos) {
        return String.format(RU, "%.2f мс", nanos / 1_000_000.0);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private static String indent(String text, int width) {
        return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
    }

    /** Русское склонение после числа: 1 место, 2 места, 5 мест. */
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
}
