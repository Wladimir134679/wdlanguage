package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.value.PropertyRequirement;

import java.util.Objects;

/**
 * Свойство в плоской таблице класса или трейта.
 * <p>
 * Собирается там же и тем же правилом, что поля и методы: «родитель → трейты слева
 * направо → сам класс», побеждает последний. Поиска по цепочке во время выполнения
 * нет — обращение к свойству стоит столько же, сколько к полю, плюс сам вызов.
 * <p>
 * <b>Свойство и поле занимают одну ячейку имени.</b> Это не упрощение, а то же
 * правило, по которому в языке одно пространство имён: и поле, и свойство — «место,
 * которое читают и пишут», отличается только то, стоит ли за ним значение или код.
 * Поэтому потомок вправе заменить унаследованное поле вычисляемым свойством и
 * наоборот — ровно как он вправе переопределить метод. А вот свойство и <b>метод</b>
 * под одним именем — конфликт: это разные виды членов, и молчаливый выбор одного
 * из двух не значил бы ничего хорошего. См. {@link Linker}.
 *
 * @param owner      чьё объявление задаёт свойство
 * @param declaredIn где написан код аксессоров. Та же разница, что у
 *                   {@link MethodSlot#declaredIn()}: унаследованное свойство остаётся
 *                   кодом того класса, где оно написано, и его {@code super} — родитель
 *                   этого класса, а не класса экземпляра
 */
public record PropertySlot(String name, PropertyDecl declaration, Shape owner) {

    public PropertySlot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(owner, "owner");
    }

    /** Есть ли у свойства своё место для значения: {@code property x = 0}. */
    public boolean hasBackingField() {
        return declaration.hasBackingField();
    }

    /** Можно ли читать. У класса всегда да — свойство без чтения не заводится. */
    public boolean readable() {
        return declaration.getter() != null && !declaration.getter().isRequirement();
    }

    /** Можно ли писать: объявлен ли {@code def set(value)} с телом. */
    public boolean writable() {
        return declaration.setter() != null && !declaration.setter().isRequirement();
    }

    /** Чего не хватает до обещанного — для сообщения о невыполненном требовании. */
    public String missingFor(boolean read, boolean write) {
        return new PropertyRequirement(name, read, write).missing(readable(), writable());
    }
}
