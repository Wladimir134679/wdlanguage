package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.Fragment;
import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Одна цель распаковки: {@code x}, {@code p.y}, {@code _}, {@code *rest}, {@code **rest}.
 * <p>
 * <b>Запись, а не просто {@link Expr}.</b> Пропуску и безымянному остатку выражение
 * не нужно вовсе, а звёздочку иначе пришлось бы кодировать соглашением поверх дерева —
 * скажем, обёрткой {@code UnaryExpr}, которой в языке нет. Вид записан перечислением
 * по той же причине, по которой он записан перечислением у
 * {@linkplain ru.wds.wdl.ast.expr.Argument аргумента}: {@code switch} без {@code default}
 * компилятор проверяет на полноту, и когда видов станет пять — привязка в {@code match},
 * {@code case is Rect as (w, h)}, — он сам покажет каждое место, где новый вид надо учесть.
 * <p>
 * Та же запись служит и переменными перебора: {@code for (i, tile in row)}. Там годятся
 * только {@link Kind#VALUE} с именем и {@link Kind#HOLE} — остаток перебору не нужен,
 * а имя переменной цикла заводится, а не присваивается.
 *
 * @param target выражение цели — {@link VariableExpr} или {@link AccessExpr};
 *               {@code null} у пропуска и у безымянного остатка {@code *_}
 * @param kind   вид цели
 * @param span   место в исходнике вместе со звёздочкой, если она есть
 */
public record UnpackTarget(Expr target, Kind kind, Span span) implements Fragment {

    /** Вид цели. */
    public enum Kind {
        /** Обычная цель: имя или обращение. Пишет ровно одно значение. */
        VALUE,
        /** Пропуск {@code _}: значение считается по позиции и выбрасывается. */
        HOLE,
        /** Остаток по позициям {@code *rest} — или {@code *_}, если он безымянный. */
        REST,
        /** Остаток по именам {@code **rest}. Безымянным не бывает: см. {@code docs/statements.md}. */
        REST_NAMED
    }

    public UnpackTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        if (target == null && kind == Kind.VALUE) {
            throw new IllegalArgumentException("у обычной цели распаковки должно быть выражение");
        }
    }

    /** Обычная цель: {@code x}, {@code p.y}, {@code grid[0]}. */
    public static UnpackTarget value(Expr target) {
        return new UnpackTarget(target, Kind.VALUE, target.span());
    }

    /** Пропуск {@code _}. */
    public static UnpackTarget hole(Span span) {
        return new UnpackTarget(null, Kind.HOLE, span);
    }

    /** Пишет ли эта цель хоть что-нибудь — то есть надо ли для неё искать место записи. */
    public boolean writes() {
        return target != null;
    }

    /** Занимает ли цель одну позицию источника. Остаток забирает хвост, а не позицию. */
    public boolean positional() {
        return kind == Kind.VALUE || kind == Kind.HOLE;
    }

    public boolean isRest() {
        return kind == Kind.REST || kind == Kind.REST_NAMED;
    }

    /**
     * Хвостовое имя цели — оно же ключ в {@code **}-форме, — или {@code null},
     * если имени у цели нет.
     * <p>
     * У {@code x} это {@code x}, у {@code p.x} и {@code p["x"]} — {@code x}: обращение
     * в языке одно, и скобки от точки отличает только форма записи. У {@code grid[i]}
     * имени нет — ключ там вычисляется, и в тексте распаковки уже не прочитать,
     * что именно из источника берут.
     */
    public String trailingName() {
        return switch (target) {
            case VariableExpr variable -> variable.name();
            case AccessExpr access -> access.literalKey();
            case null, default -> null;
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case VALUE -> target.toString();
            case HOLE -> "_";
            case REST -> "*" + (target != null ? target : "_");
            case REST_NAMED -> "**" + (target != null ? target : "_");
        };
    }
}
