package ru.wds.wdl.debug;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Где стоят точки останова: смещения начала инструкций, по файлу.
 * <p>
 * <b>Ключ — смещение, а не строка.</b> Строка переводится в смещение один раз,
 * при установке точки, и делает это тот, у кого есть дерево файла
 * ({@code tools.BreakpointPlaces}). Иначе каждый шаг платил бы за
 * {@code Source.positionOf} — пересчёт смещения в строку и столбец, — а шагов
 * в горячем цикле миллионы.
 * <p>
 * Смещение — начало инструкции ({@code stmt.span().start()}), в единицах UTF-16:
 * та же мера, которой пользуются {@code Span}, LSP-адаптер и отчёт профиля.
 * <p>
 * Файл назван строкой, а не {@code Source}: точки ставят до запуска, когда исходник
 * ещё не прочитан, и приходят они от редактора путём. Сверяется имя так же, как его
 * видит {@code Source.name()}, — сравнением строк, поэтому нормализовать путь обязан
 * тот, кто точку ставит.
 *
 * <h2>Потоки</h2>
 * Точки меняются из потока отладчика, а читаются из потоков скрипта — на каждом шаге.
 * Отсюда {@link ConcurrentHashMap} и неизменяемые множества внутри: чтение не берёт
 * замка вовсе, а замена набора в файле атомарна. Обещание при этом ровно то же, что
 * у языка: шаг видит либо старый набор точек, либо новый, но не половину.
 */
public final class Breakpoints {

    /** Файл — множество смещений. Пустой файл из карты убирается, а не хранится пустым. */
    private final Map<String, Set<Integer>> points = new ConcurrentHashMap<>();

    /**
     * Заводит сессия, а не приложение: набор точек принадлежит ей и берётся у неё
     * ({@code DebugSession.breakpoints()}). Отдельный набор, не подключённый ни к какой
     * сессии, ничего не значит.
     */
    Breakpoints() {
    }

    /**
     * Есть ли хоть одна точка. Спрашивается на каждом шаге <b>перед</b> походом в карту:
     * сессия, у которой точек нет вовсе (шаг по кнопке, пауза), не должна платить
     * за поиск.
     */
    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** Стоит ли точка на этом месте. */
    public boolean has(String file, int offset) {
        if (file == null) {
            return false;
        }
        Set<Integer> known = points.get(file);
        return known != null && known.contains(offset);
    }

    /**
     * Заменяет все точки файла разом.
     * <p>
     * Именно заменяет, а не добавляет: редактор присылает состояние файла целиком
     * ({@code setBreakpoints} в DAP устроен так же), и «добавить» без «снять»
     * оставляло бы точки, которых в редакторе уже нет.
     *
     * @return сколько точек теперь стоит в этом файле
     */
    public int set(String file, Collection<Integer> offsets) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(offsets, "offsets");
        if (offsets.isEmpty()) {
            points.remove(file);
            return 0;
        }
        Set<Integer> copy = Set.copyOf(offsets);
        points.put(file, copy);
        return copy.size();
    }

    /** Снимает все точки во всех файлах. */
    public void clear() {
        points.clear();
    }

    /** Точки этого файла; пусто, если их нет. */
    public Set<Integer> of(String file) {
        Set<Integer> known = points.get(file);
        return known == null ? Set.of() : known;
    }

    /** Файлы, в которых есть хоть одна точка. */
    public Set<String> files() {
        return Set.copyOf(points.keySet());
    }

    @Override
    public String toString() {
        return "Breakpoints" + points;
    }
}
