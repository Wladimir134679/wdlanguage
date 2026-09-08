package ru.wds.wdl.dap;

import ru.wds.wdl.value.Value;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Числа, которыми протокол называет потоки, кадры и раскрываемые значения.
 * <p>
 * У DAP все ссылки — {@code int}, и придумывает их адаптер. У движка адреса другие:
 * поток — {@code long} ({@code Thread.threadId()}), кадр — глубина в потоке, значение
 * — сам объект. Перевод живёт здесь, потому что у него есть <b>время жизни</b>,
 * и оно разное:
 * <ul>
 *   <li><b>поток</b> называется числом раз и навсегда: клиент рисует его в списке
 *       потоков, и переименовать его между двумя остановками нельзя;</li>
 *   <li><b>кадр и раскрытое значение</b> живут от остановки до возобновления. После
 *       {@code continue} кадр — это уже другой кадр, а значение в переменной другое,
 *       и отвечать на старую ссылку новыми данными значило бы врать. Поэтому
 *       {@link #invalidate(long)} на каждом возобновлении, а счётчик — общий и только
 *       растущий: старая ссылка не находится, а не попадает в чужие данные.</li>
 * </ul>
 */
final class References {

    /** Что стоит за {@code variablesReference}. */
    sealed interface Node {
    }

    /** Локальные имена кадра: параметры и переменные самой этой области. */
    record Locals(long threadId, int depth) implements Node {
    }

    /** Всё, что кадру видно снаружи: внешние области, включая корневую. */
    record Visible(long threadId, int depth) implements Node {
    }

    /** Раскрытое значение: элементы массива, поля объекта. */
    record Children(long threadId, Value value) implements Node {
    }

    /** Кадр: поток и глубина в нём — то, чем адресует кадр сама сессия. */
    record Frame(long threadId, int depth) {
    }

    private final Map<Long, Integer> threadNumbers = new ConcurrentHashMap<>();
    private final Map<Integer, Long> threadsByNumber = new ConcurrentHashMap<>();
    private final AtomicInteger nextThread = new AtomicInteger(1);

    private final Map<Integer, Frame> frames = new ConcurrentHashMap<>();
    private final Map<Integer, Node> nodes = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger(1);

    /** Номер потока для протокола; один и тот же для одного и того же потока. */
    int threadNumber(long threadId) {
        return threadNumbers.computeIfAbsent(threadId, id -> {
            int number = nextThread.getAndIncrement();
            threadsByNumber.put(number, id);
            return number;
        });
    }

    /**
     * Поток по номеру из протокола.
     *
     * @throws DapError если такого номера адаптер не выдавал
     */
    long threadOf(int number) {
        Long known = threadsByNumber.get(number);
        if (known == null) {
            throw new DapError("неизвестный поток: " + number);
        }
        return known;
    }

    /** Ссылка на кадр. */
    int frame(long threadId, int depth) {
        int reference = next.getAndIncrement();
        frames.put(reference, new Frame(threadId, depth));
        return reference;
    }

    /**
     * Кадр по ссылке.
     *
     * @throws DapError если ссылка устарела — кадра из прошлой остановки уже нет
     */
    Frame frameOf(int reference) {
        Frame known = frames.get(reference);
        if (known == null) {
            throw new DapError("кадр " + reference + " устарел: поток пошёл дальше");
        }
        return known;
    }

    /** Ссылка на узел панели переменных. */
    int node(Node node) {
        int reference = next.getAndIncrement();
        nodes.put(reference, node);
        return reference;
    }

    /**
     * Узел по ссылке.
     *
     * @throws DapError если ссылка устарела
     */
    Node nodeOf(int reference) {
        Node known = nodes.get(reference);
        if (known == null) {
            throw new DapError("значение " + reference + " устарело: поток пошёл дальше");
        }
        return known;
    }

    /**
     * Забывает кадры и значения <b>одного</b> потока: всё, что было названо до его
     * возобновления.
     * <p>
     * Именно одного, а не всех. В скрипте с {@code th.spawn} потоки встают и идут
     * дальше независимо, и общий сброс на каждом {@code continue} гасил бы панель
     * соседнего потока, который в этот момент стоит и которого никто не трогал.
     * <p>
     * Номера потоков при этом остаются: поток тот же, и список потоков у клиента
     * не должен перерисовываться заново на каждом шаге.
     */
    void invalidate(long threadId) {
        frames.values().removeIf(frame -> frame.threadId() == threadId);
        nodes.entrySet().removeIf(entry -> threadOf(entry.getValue()) == threadId);
    }

    private static long threadOf(Node node) {
        return switch (node) {
            case Locals locals -> locals.threadId();
            case Visible visible -> visible.threadId();
            case Children children -> children.threadId();
        };
    }
}
