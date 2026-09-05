package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.Fragment;
import ru.wds.wdl.ast.expr.Annotations;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Свойство класса или трейта: {@code property area => this.w * this.h}.
 * <p>
 * Не {@link Stmt} и не {@link ru.wds.wdl.ast.expr.Expr}, а просто запись в объявлении
 * типа — как {@link ClassDeclStmt.Factory} и {@link ru.wds.wdl.ast.expr.Decorator}.
 * Посетители дерева от этого не меняются: свойство разбирают там же, где читают
 * тело класса, и отдельной точки входа ему не нужно.
 * <p>
 * <b>Свойство — это имя, за которым стоит код, а не ячейка.</b> Читается оно тем же
 * обращением, что поле ({@code r.area} и {@code r["area"]} — одно и то же), но между
 * обращением и значением встаёт вызов. Отсюда всё остальное: свойство не видно
 * перебором и {@code len}, потому что среди пар экземпляра его нет; запись без
 * {@link #setter()} — ошибка, а не заведение поля; и обещание «одно обращение
 * атомарно» на свойство не распространяется — это вызов, а вызов целиком не атомарен.
 * <p>
 * <b>Скрытое поле.</b> {@code property x = 0 { ... }} заводит место для значения,
 * которого нет среди пар экземпляра: иначе правило «сначала собственное поле»
 * побеждало бы и геттер не вызывался никогда, а {@code x} попадал бы в перебор,
 * в {@code len} и в печать. Внутри аксессоров это место видно под именем
 * {@code field} — обычным именем, которое заводит область вызова, как {@code this};
 * ключевым словом его делать нельзя, да и незачем.
 *
 * @param annotations данные, приписанные свойству: {@code @{computed: true}}. Лежат
 *                здесь, а не в аксессоре: аннотируют имя, а не способ его чтения
 * @param initial выражение начального значения скрытого поля или {@code null},
 *                если свойство вычисляемое. Вычисляется при создании экземпляра —
 *                там же и тогда же, когда значения по умолчанию полей трейта
 * @param getter  чтение; {@code null} не бывает у класса — свойство без чтения
 *                запрещено, а у трейта это требование (см. {@link Accessor})
 * @param setter  запись или {@code null}: свойство только для чтения
 */
public record PropertyDecl(String name, Span nameSpan, Annotations annotations, Expr initial,
                           Accessor getter, Accessor setter,
                           PropertyStyle style, Span span) implements Fragment {

    public PropertyDecl {
        Objects.requireNonNull(name, "name");
        annotations = annotations == null ? Annotations.NONE : annotations;
        Objects.requireNonNull(style, "style");
    }

    /** Есть ли у свойства своё место для значения: {@code property x = 0}. */
    public boolean hasBackingField() {
        return initial != null;
    }

    public boolean hasSetter() {
        return setter != null;
    }

    /**
     * Аксессор: тело или требование трейта.
     * <p>
     * {@code function == null} значит «объявлено без тела» — то же самое различие
     * и по той же причине, что у {@link TraitDeclStmt.Requirement}: функции без тела
     * в языке не существует, и {@code null} вместо неё разошёлся бы по всем
     * посетителям дерева. Здесь он заперт внутри одной записи, и спрашивают о нём
     * {@link #isRequirement()}.
     */
    public record Accessor(FunctionExpr function, Span span) implements Fragment {

        public Accessor {
            Objects.requireNonNull(span, "span");
        }

        /** Объявлен без тела: {@code property size { def get() }} в трейте. */
        public boolean isRequirement() {
            return function == null;
        }
    }
}
