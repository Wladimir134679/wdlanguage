package ru.wds.wdl.idea.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Точки останова: те, что человек поставил в редакторе, — и то, что с ними сделал
 * адаптер.
 *
 * <h2>Почему нужен свой учёт</h2>
 * Точка, поставленная на комментарий или на пустую строку, <b>уезжает вниз</b>,
 * к ближайшей инструкции: так решает дерево файла, и решает оно на стороне адаптера.
 * Значит, строка, на которой поток встанет, не равна строке, на которой точка
 * нарисована. Чтобы при остановке сказать «сработала вот эта точка», нужен обратный
 * перевод — от строки, которую назвал адаптер, к точке, которую видит человек.
 * Он и живёт здесь.
 *
 * <h2>Порядок</h2>
 * Платформа заводит точки <b>до</b> того, как отладчик договорился с адаптером:
 * сеанс ещё только начинается. Поэтому до готовности они только копятся
 * ({@link #snapshot()} отдаёт их одним куском началу сеанса), а с этой минуты каждое
 * изменение уходит адаптеру сразу — файл за файлом, заменой набора.
 */
final class WdlBreakpoints extends XBreakpointHandler<XLineBreakpoint<XBreakpointProperties>> {

    private final XDebugSession session;

    /** Точки по файлам: строка редактора (с единицы) — сама точка. */
    private final Map<String, Map<Integer, XLineBreakpoint<XBreakpointProperties>>> requested =
            new LinkedHashMap<>();

    /** Куда они встали на самом деле: строка адаптера — точка человека. */
    private final Map<String, Map<Integer, XLineBreakpoint<XBreakpointProperties>>> placed =
            new LinkedHashMap<>();

    /** Строки «до курсора»: временные, свои на файл; снимаются первой же остановкой. */
    private final Map<String, List<Integer>> temporary = new LinkedHashMap<>();

    private volatile DapSession dap;

    WdlBreakpoints(XDebugSession session) {
        super(WdlBreakpointType.class);
        this.session = session;
    }

    /** С этой минуты изменения уходят адаптеру сразу. */
    void ready(DapSession value) {
        this.dap = value;
    }

    /** Точки, накопленные до начала сеанса: путь — строки. */
    synchronized Map<String, List<Integer>> snapshot() {
        Map<String, List<Integer>> all = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Integer, XLineBreakpoint<XBreakpointProperties>>> file
                : requested.entrySet()) {
            all.put(file.getKey(), new ArrayList<>(file.getValue().keySet()));
        }
        return all;
    }

    @Override
    public void registerBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint) {
        String file = fileOf(breakpoint);
        if (file == null) {
            return;
        }
        synchronized (this) {
            requested.computeIfAbsent(file, key -> new LinkedHashMap<>())
                    .put(SourcePositions.protocolLine(breakpoint.getLine()), breakpoint);
        }
        send(file);
    }

    @Override
    public void unregisterBreakpoint(@NotNull XLineBreakpoint<XBreakpointProperties> breakpoint,
                                     boolean temporary) {
        String file = fileOf(breakpoint);
        if (file == null) {
            return;
        }
        synchronized (this) {
            Map<Integer, XLineBreakpoint<XBreakpointProperties>> lines = requested.get(file);
            if (lines != null) {
                lines.remove(SourcePositions.protocolLine(breakpoint.getLine()));
                if (lines.isEmpty()) {
                    requested.remove(file);
                }
            }
        }
        send(file);
    }

    /**
     * Просит адаптер остановиться на этой строке один раз — «Выполнить до курсора».
     * <p>
     * Отдельной временной точкой, а не особым запросом: в протоколе такого запроса нет,
     * а точка — уже есть. Снимается она первой же остановкой, чем бы та ни была вызвана:
     * человек, нажавший «до курсора» и вставший раньше на своей точке, не должен
     * получить лишнюю остановку следом.
     */
    void runTo(String file, int line) {
        synchronized (this) {
            temporary.computeIfAbsent(file, key -> new ArrayList<>()).add(line);
        }
        send(file);
    }

    /** Снимает временные точки; возвращает файлы, которые из-за этого надо переслать. */
    synchronized List<String> dropTemporary() {
        if (temporary.isEmpty()) {
            return List.of();
        }
        List<String> files = new ArrayList<>(temporary.keySet());
        temporary.clear();
        return files;
    }

    /** Точка, которая сработала на этом месте, или {@code null}, если встали не на ней. */
    synchronized @Nullable XLineBreakpoint<XBreakpointProperties> at(String file, int line) {
        Map<Integer, XLineBreakpoint<XBreakpointProperties>> lines = placed.get(file);
        return lines == null ? null : lines.get(line);
    }

    /** Отправляет адаптеру все точки одного файла и запоминает, куда они встали. */
    void send(String file) {
        DapSession known = dap;
        if (known == null) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<XLineBreakpoint<XBreakpointProperties>> order;
            List<Integer> lines;
            synchronized (this) {
                Map<Integer, XLineBreakpoint<XBreakpointProperties>> known2 =
                        requested.getOrDefault(file, Map.of());
                order = new ArrayList<>(known2.values());
                lines = new ArrayList<>(known2.keySet());
                lines.addAll(temporary.getOrDefault(file, List.of()));
            }
            try {
                List<Breakpoint> answer = known.setBreakpoints(file, lines);
                remember(file, order, answer);
            } catch (DapFailure failed) {
                session.reportMessage("Не удалось поставить точки в " + file + ": "
                        + failed.getMessage(), com.intellij.openapi.ui.MessageType.ERROR);
            }
        });
    }

    /**
     * Запоминает ответ адаптера и говорит человеку, что с его точками стало.
     * <p>
     * Точка, которой не нашлось инструкции, помечается недействительной с объяснением:
     * молча не сработать — худшее, что может сделать отладчик.
     */
    private void remember(String file, List<XLineBreakpoint<XBreakpointProperties>> order,
                          List<Breakpoint> answer) {
        Map<Integer, XLineBreakpoint<XBreakpointProperties>> known = new LinkedHashMap<>();
        for (int index = 0; index < order.size() && index < answer.size(); index++) {
            XLineBreakpoint<XBreakpointProperties> breakpoint = order.get(index);
            Breakpoint placedAt = answer.get(index);
            if (placedAt.isVerified()) {
                if (placedAt.getLine() != null) {
                    known.put(placedAt.getLine(), breakpoint);
                }
                session.setBreakpointVerified(breakpoint);
            } else {
                session.setBreakpointInvalid(breakpoint, placedAt.getMessage());
            }
        }
        synchronized (this) {
            placed.put(file, known);
        }
    }

    /** Путь файла в том виде, в котором его понимает адаптер. */
    private static @Nullable String fileOf(XLineBreakpoint<XBreakpointProperties> breakpoint) {
        var position = breakpoint.getSourcePosition();
        return position == null ? null : SourcePositions.protocolPath(position.getFile());
    }
}
