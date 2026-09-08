package ru.wds.wdl.debug;

import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.source.Position;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.value.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Кадр остановленного потока: где стоим и что отсюда видно.
 * <p>
 * Строится в момент остановки, по одному на глубину вызова, и живёт ровно до
 * возобновления потока. Значения из него читать можно из любого потока — область
 * видимости конкурентна, — но <b>смысл</b> они имеют, только пока поток стоит:
 * возобновлённый скрипт меняет те же ячейки.
 *
 * <h2>Свойства здесь не читаются</h2>
 * За именем {@linkplain ru.wds.wdl.value.Property свойства} стоит вызов, и прочитать
 * его значит выполнить код скрипта — в остановленном потоке, из потока отладчика,
 * посреди панели переменных. Поэтому {@link #value(String)} спрашивает область
 * видимости <b>без контекста</b>: такой поиск свойства пропускает, и это не оговорка,
 * а тот самый путь, который в {@code InstanceScope} заведён для мест, где выполнять
 * чужой код нельзя. Свойство читается по-настоящему только по явной просьбе —
 * через {@code DebugSession.evaluate}, где вычислением занимается сам остановленный
 * поток.
 */
public final class DebugFrame {

    private final int depth;
    private final String function;
    private final Source source;
    private final Span span;
    private final ExecutionContext context;

    DebugFrame(int depth, String function, Source source, Span span, ExecutionContext context) {
        this.depth = depth;
        this.function = Objects.requireNonNull(function, "function");
        this.source = source;
        this.span = Objects.requireNonNull(span, "span");
        this.context = Objects.requireNonNull(context, "context");
    }

    /**
     * Глубина кадра: {@code 0} — верхний уровень файла, дальше по вызову на единицу.
     * Она же служит адресом кадра в {@code DebugSession}.
     */
    public int depth() {
        return depth;
    }

    /** Имя функции этого кадра или имя файла на верхнем уровне. */
    public String function() {
        return function;
    }

    /** Файл кадра или пустая строка, если его нет (строка REPL, {@code eval}). */
    public String file() {
        return source == null ? "" : source.name();
    }

    /** Смещение начала текущей инструкции в единицах UTF-16. */
    public int offset() {
        return span.start();
    }

    /** Место текущей инструкции. */
    public Span span() {
        return span;
    }

    /** Строка текущей инструкции, нумерация с единицы; {@code 0}, если файла нет. */
    public int line() {
        Position at = position();
        return at == null ? 0 : at.line();
    }

    /** Столбец текущей инструкции, нумерация с единицы; {@code 0}, если файла нет. */
    public int column() {
        Position at = position();
        return at == null ? 0 : at.column();
    }

    private Position position() {
        if (source == null || span.isNone() || span.start() > source.length()) {
            return null;
        }
        return source.positionOf(span.start());
    }

    /**
     * Имена, заведённые в самой этой области: локальные переменные и параметры,
     * без того, что видно снаружи.
     */
    public Set<String> namesHere() {
        return context.scope().namesHere();
    }

    /** Все имена, видимые отсюда: свои плюс внешние области, включая корневую. */
    public Set<String> names() {
        return context.scope().names();
    }

    /**
     * Значение имени или {@code null}, если имени нет отсюда видно.
     * <p>
     * Кода скрипта не выполняет: свойство по этому пути пропускается — см. javadoc
     * класса.
     */
    public Value value(String name) {
        return context.scope().lookup(Objects.requireNonNull(name, "name"));
    }

    /**
     * Локальные имена этого кадра со значениями, в порядке объявления.
     * <p>
     * Готовый ответ панели переменных: перечислять имена и спрашивать каждое по
     * отдельности пришлось бы всё равно, а порядок {@code namesHere()} — тот же,
     * в котором их заводил скрипт.
     */
    public Map<String, Value> locals() {
        Map<String, Value> values = new LinkedHashMap<>();
        for (String name : namesHere()) {
            Value value = value(name);
            if (value != null) {
                values.put(name, value);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * Место выполнения этого кадра — для того, кому нужно ядро напрямую.
     * <p>
     * Отдаётся затем, что вычисление выражения в кадре ({@code DebugSession.evaluate})
     * происходит именно здесь: контекст несёт область, файл, кадр вызова и сеанс.
     */
    public ExecutionContext context() {
        return context;
    }

    /** {@code total (script.wdl:12)} — как в трассировке ошибки и в отчёте профиля. */
    @Override
    public String toString() {
        int line = line();
        return line == 0 ? function : function + " (" + file() + ":" + line + ")";
    }
}
