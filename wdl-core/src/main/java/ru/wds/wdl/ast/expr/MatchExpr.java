package ru.wds.wdl.ast.expr;

import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Ветвление по одному предмету: {@code match (x) { case ... => ... else => ... }}.
 * <p>
 * <b>Предмет обязателен всегда.</b> Формы без него ({@code match { case cpu > 90 => ... }})
 * в языке нет: она почти всегда оказывается {@code match}, у которого поленились
 * вынести заголовок, — предмет виден повтором в каждой ветке; а когда предмет в ветках
 * действительно разный, это {@code if / else if}, и второго лица под чужим именем ему
 * не нужно.
 * <p>
 * <b>Предмет вычисляется один раз</b> — это и есть главное отличие от цепочки
 * {@code if}: {@code match (order.total())} не зовёт {@code total()} на каждую ветку.
 * Образцы при этом вычисляются лениво, сверху вниз, до первого совпадения; порядок
 * веток — часть смысла, и движок их не переставляет. Побочный эффект в образце
 * случится, только если до этой ветки дошла очередь.
 * <p>
 * <b>Провала нет.</b> В отличие от {@code switch} в Си и Java, выполненная ветка
 * не продолжается в следующую, и {@code break} в теле ветки относится к объемлющему
 * циклу, а не к {@code match}. Сказать это вслух надо именно потому, что привычка
 * из Си говорит обратное. Группировка даётся перечислением образцов
 * ({@code case 1, 2, 3}), а не пустой веткой.
 * <p>
 * <b>Один узел на обе позиции, но позиция в нём записана.</b> {@code match}
 * из {@code statement()} — инструкция, {@code match} из {@code primary()} — выражение;
 * тем же приёмом {@code &#123;} в начале инструкции всегда блок, а в позиции выражения
 * всегда объект. Определяет позицию парсер — он один её знает, — но запомнить её
 * приходится в узле ({@link #asValue()}): без значения осталась ветка или так
 * и задумано, выясняется только при выполнении, когда ветка уже отработала
 * и {@code yield} не сделала.
 * <p>
 * В позиции выражения {@code else} обязателен: исчерпаемость в динамическом языке
 * не доказать, а тихий {@code null} из непопавшего {@code match} — ровно тот класс
 * ошибок, который язык обещает ломать там, где написано. Ветка при этом бывает
 * и стрелочной ({@code case 1 => "one"}), и блоком, отдающим значение через
 * {@code yield}.
 *
 * @param subject   предмет; никогда не {@code null}
 * @param cases     ветки в порядке записи
 * @param otherwise ветка {@code else} или {@code null}, если её нет
 * @param asValue   стоит ли этот {@code match} там, где ждут значение
 * @param span      место в исходнике целиком
 */
public record MatchExpr(Expr subject, List<MatchCase> cases, MatchCase otherwise,
                        boolean asValue, Span span) implements Expr {

    public MatchExpr {
        Objects.requireNonNull(subject, "subject");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        Objects.requireNonNull(span, "span");
        // otherwise намеренно может быть null: «else не написан» — не пустая ветка,
        // и инструментам разницу видеть нужно, как и у IfStmt.
    }

    public boolean hasOtherwise() {
        return otherwise != null;
    }

    @Override
    public String toString() {
        return "match (" + subject + ") { " + cases.size() + " ветк(и) }";
    }
}
