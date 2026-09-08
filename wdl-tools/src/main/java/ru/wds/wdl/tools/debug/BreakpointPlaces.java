package ru.wds.wdl.tools.debug;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.ErrorStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.source.Position;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Куда в этом файле можно поставить точку останова.
 * <p>
 * Отладчик останавливается перед <b>инструкцией</b>, и ключом точки служит смещение
 * её начала ({@code DebugSession.breakpoints()}). Редактор же знает только строку,
 * на которой человек щёлкнул мышью. Перевод одного в другое живёт здесь — в
 * инструментах, рядом с остальным, что знает про дерево, — и делается <b>один раз,
 * при установке точки</b>: если бы строку в смещение переводили на каждом шаге,
 * горячий цикл платил бы за {@code Source.positionOf} миллионы раз.
 *
 * <h2>Точка уезжает вниз, а не пропадает</h2>
 * Щелчок по пустой строке, по комментарию, по закрывающей скобке — обычное дело,
 * и отвечать на него «здесь нельзя» неправильно: человек показал <i>примерно</i>
 * туда, куда хочет. Поэтому берётся ближайшая инструкция, начинающаяся на этой
 * строке или ниже, — ровно так ведут себя все отладчики, и ровно это возвращает
 * DAP в {@code Breakpoint.line}, сообщая клиенту, куда точка встала на самом деле.
 *
 * <h2>Что инструкцией не считается</h2>
 * <ul>
 *   <li>{@link BlockStmt} — точка на открывающей скобке ушла бы на весь блок целиком,
 *       а человек, ткнув в строку с {@code {}, почти всегда имеет в виду первую
 *       инструкцию внутри;</li>
 *   <li>{@link ErrorStmt} — место, где разбор не удался: ставить туда точку значит
 *       обещать останов там, где выполнения не будет вовсе.</li>
 * </ul>
 * Всё остальное — включая объявления — местом останова считается: объявление
 * выполняется тогда, когда до него дошло выполнение, и остановиться на нём законно.
 */
public final class BreakpointPlaces {

    private BreakpointPlaces() {
    }

    /**
     * Место останова: смещение для сессии, строка и столбец для редактора.
     *
     * @param offset смещение начала инструкции в единицах UTF-16
     * @param line   строка, нумерация с единицы
     * @param column столбец, нумерация с единицы
     */
    public record Place(int offset, int line, int column) {

        @Override
        public String toString() {
            return line + ":" + column + " (+" + offset + ")";
        }
    }

    /**
     * Все места, где в этом файле можно остановиться, — по возрастанию смещения.
     * <p>
     * Отвечает на запрос {@code breakpointLocations}: редактор рисует по нему
     * доступные для точки строки, не спрашивая по одной.
     */
    public static List<Place> all(Program program, Source source) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(source, "source");
        List<Place> places = new ArrayList<>();
        Nodes.walk(program, node -> {
            if (isPlace(node)) {
                places.add(placeOf(node.span(), source));
            }
        });
        places.sort(Comparator.comparingInt(Place::offset));
        return List.copyOf(places);
    }

    /**
     * Куда встанет точка, поставленная на эту строку, или {@code null}, если ниже
     * инструкций больше нет.
     * <p>
     * Из нескольких инструкций, начинающихся на одной строке, берётся самая левая:
     * {@code if (x) { y = 1 }} в одну строку — это точка на {@code if}, а не на
     * присваивании внутри. Так же читается и сама строка.
     *
     * @param line строка редактора, нумерация с единицы
     */
    public static Place at(Program program, Source source, int line) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(source, "source");
        if (line < 1) {
            return null;
        }
        Place best = null;
        for (Place place : all(program, source)) {
            if (place.line() < line) {
                continue;
            }
            if (best == null || place.line() < best.line()
                    || (place.line() == best.line() && place.offset() < best.offset())) {
                best = place;
            }
            if (best.line() == line) {
                // Ниже искать нечего: точнее, чем «та самая строка», уже не будет.
                break;
            }
        }
        return best;
    }

    /**
     * Смещения для набора строк — то, чем отвечают на {@code setBreakpoints}.
     * <p>
     * Порядок ответа совпадает с порядком запроса, и {@code null} на месте строки,
     * для которой места не нашлось, сохраняется: клиент сопоставляет ответ со своим
     * списком по индексу, и молча выкинутая строка сдвинула бы все остальные.
     */
    public static List<Place> at(Program program, Source source, List<Integer> lines) {
        Objects.requireNonNull(lines, "lines");
        List<Place> found = new ArrayList<>(lines.size());
        for (Integer line : lines) {
            found.add(line == null ? null : at(program, source, line));
        }
        return found;
    }

    private static boolean isPlace(Node node) {
        return node instanceof Stmt statement
                && !(statement instanceof BlockStmt)
                && !(statement instanceof ErrorStmt)
                && !statement.span().isNone();
    }

    private static Place placeOf(Span span, Source source) {
        int offset = Math.min(span.start(), source.length());
        Position at = source.positionOf(offset);
        return new Place(offset, at.line(), at.column());
    }
}
