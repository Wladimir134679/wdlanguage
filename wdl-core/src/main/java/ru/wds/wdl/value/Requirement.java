package ru.wds.wdl.value;

import java.util.Objects;

/**
 * Требование трейта к методу: имя и сколько аргументов метод обязан принимать.
 * <p>
 * Требование — это то, ради чего трейты заведены, и проверяется оно при объявлении
 * класса. Понятие общее для трейта на wdl ({@code def report()} без тела) и трейта
 * от приложения ({@code .requireMethod("report", Arity.exactly(0))}), поэтому и живёт
 * здесь, рядом с {@link Arity}, а не в разборе: у второго дерева нет вовсе.
 *
 * @param name  имя метода
 * @param arity сколько аргументов обязан принимать
 */
public record Requirement(String name, Arity arity) {

    public Requirement {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arity, "arity");
    }

    public static Requirement of(String name, Arity arity) {
        return new Requirement(name, arity);
    }

    /**
     * Годится ли объявленный метод.
     * <p>
     * Правило — «принимает всё, что обещано»: метод вправе принимать больше
     * необязательных аргументов, чем требует трейт, но обязан принять и минимум,
     * и максимум требования. Иначе вызов, законный по трейту, упал бы на арности.
     */
    public boolean satisfiedBy(Arity actual) {
        return actual.accepts(arity.min()) && actual.accepts(arity.max());
    }

    @Override
    public String toString() {
        return "def " + name + arity;
    }
}
