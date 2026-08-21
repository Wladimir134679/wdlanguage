package ru.wds.wdl.value;

import java.util.Objects;

/**
 * Требование трейта к свойству: имя и то, чтение или запись обещаны.
 * <p>
 * Ради этого свойства в трейтах и заведены. Требование выражено <b>через
 * возможность, а не через способ хранения</b>, поэтому его закрывает и обычное
 * изменяемое поле, и вычисляемое свойство:
 * <ul>
 *   <li>поле закрывает и {@code get}, и {@code set} — читать и писать его можно;</li>
 *   <li>свойство закрывает те аксессоры, что у него объявлены;</li>
 *   <li>метод {@code size()} не закрывает ничего: его читают именем, а зовут скобками,
 *       и это другая операция.</li>
 * </ul>
 * Отсюда главное следствие: поле можно заменить вычисляемым свойством и наоборот,
 * не сломав ни одного контракта.
 * <p>
 * Рядом с {@link Requirement}, а не в разборе, и по той же причине: понятие общее
 * для трейта на wdl и трейта от приложения, а у второго дерева нет вовсе.
 *
 * @param read  обязано ли имя читаться
 * @param write обязано ли имя записываться
 */
public record PropertyRequirement(String name, boolean read, boolean write) {

    public PropertyRequirement {
        Objects.requireNonNull(name, "name");
        if (!read && !write) {
            throw new IllegalArgumentException(
                    "требование к свойству '" + name + "' не требует ничего");
        }
    }

    /** {@code property size { def get() }} — обязано читаться. */
    public static PropertyRequirement readable(String name) {
        return new PropertyRequirement(name, true, false);
    }

    /** {@code property size { def get() def set(value) }} — обязано читаться и писаться. */
    public static PropertyRequirement mutable(String name) {
        return new PropertyRequirement(name, true, true);
    }

    /** Закрывает ли требование то, что умеет читать и писать. */
    public boolean satisfiedBy(boolean canRead, boolean canWrite) {
        return (!read || canRead) && (!write || canWrite);
    }

    /** Чего именно не хватило — для сообщения о невыполненном требовании. */
    public String missing(boolean canRead, boolean canWrite) {
        if (read && !canRead && write && !canWrite) {
            return "чтения и записи";
        }
        return read && !canRead ? "чтения" : "записи";
    }

    @Override
    public String toString() {
        return "property " + name + (write ? " { get set }" : " { get }");
    }
}
