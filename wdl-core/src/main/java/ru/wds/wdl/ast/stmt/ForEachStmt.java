package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Перебор: {@code for (item in cart) ...} и {@code for (i, tile in row) ...}.
 * <p>
 * Та же {@code for}, что и со счётчиком, — форму парсер различает по тому, что стоит
 * после открывающей скобки. Отдельного ключевого слова {@code foreach} нет: незачем
 * резервировать второе слово ради конструкции, которую и так ни с чем не спутать.
 * <p>
 * <b>Имён бывает одно или два, и второе имя ничего не распаковывает.</b> Первое из двух —
 * то, <b>чем обращаются</b>: {@code row[i]}, {@code config[k]}. Отсюда массив и строка
 * дают номер, объект и экземпляр — ключ, а диапазон не даёт ничего: читать его
 * по ключу нельзя, и номер прохода у него и есть значение. Разложить сам элемент —
 * отдельное дело и отдельная строка в теле: {@code for (p in points) { x, y = *p }}.
 * Так один и тот же список имён нигде не значит двух разных вещей.
 * <p>
 * Имена — {@link UnpackTarget} и по единственной причине: пропуск {@code _} уже описан
 * там, и {@code for (_, v in config)} получается даром. Годятся здесь только
 * {@link UnpackTarget.Kind#VALUE} с именем и {@link UnpackTarget.Kind#HOLE} — остальное
 * не пропускает разбор.
 * <p>
 * Переменные цикла — <b>свои на каждый проход</b>, а не одни общие: они заводятся
 * в области видимости итерации, поэтому замыкание из тела захватывает значение своего
 * прохода, а не последнее — ровно те грабли, на которые JavaScript наступал
 * до появления {@code let}.
 *
 * @param names    имена переменных цикла: одно (значение) или два (ключ и значение)
 * @param iterable выражение, дающее то, что перебираем
 * @param body     тело цикла
 * @param span     место в исходнике целиком
 */
public record ForEachStmt(List<UnpackTarget> names, Expr iterable, Stmt body, Span span)
        implements Stmt {

    /** Сколько имён бывает у перебора: значение — или ключ и значение. */
    public static final int MAX_NAMES = 2;

    public ForEachStmt {
        names = List.copyOf(Objects.requireNonNull(names, "names"));
        if (names.isEmpty() || names.size() > MAX_NAMES) {
            throw new IllegalArgumentException("в 'for' одно имя или два, а здесь " + names.size());
        }
        Objects.requireNonNull(iterable, "iterable");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(span, "span");
    }

    /** Перебор с одним именем: {@code for (item in cart)}. */
    public ForEachStmt(UnpackTarget name, Expr iterable, Stmt body, Span span) {
        this(List.of(name), iterable, body, span);
    }

    /** Просят ли у перебора ключ — то есть написано ли второе имя. */
    public boolean withKey() {
        return names.size() == 2;
    }

    /** Имя, в которое кладут ключ, или {@code null}, если ключ не просили. */
    public UnpackTarget key() {
        return withKey() ? names.get(0) : null;
    }

    /** Имя, в которое кладут элемент. Оно есть всегда — и всегда последнее. */
    public UnpackTarget value() {
        return names.get(names.size() - 1);
    }

    @Override
    public String toString() {
        return "for (" + names.stream().map(UnpackTarget::toString).collect(Collectors.joining(", "))
                + " in " + iterable + ") ...";
    }
}
