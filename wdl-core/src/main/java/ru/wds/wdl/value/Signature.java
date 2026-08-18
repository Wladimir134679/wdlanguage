package ru.wds.wdl.value;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Контракт вызова: имена параметров, их обязательность и, как следствие, {@link Arity}.
 * <p>
 * Заведена ради именованных аргументов. Чтобы разложить {@code f(count: 2)} по позициям,
 * имена нужны у <b>любого</b> вызываемого, включая встроенные функции: иначе своя
 * и встроенная функции перестанут быть одинаковыми значениями, а на этом равенстве
 * держатся вызов и функции высшего порядка. Поэтому контракт живёт здесь, рядом
 * с {@link FunctionValue} и {@link ClassValue}, а не в разборе: у встроенной функции
 * дерева нет вовсе.
 * <p>
 * <b>Имена бывают неизвестны, и это законно.</b> {@link #positional(Arity)} описывает
 * функцию, которая имён не объявила, — {@code println} с любым числом аргументов,
 * функция из приложения, ещё не переведённая библиотека. Такую можно звать только
 * позиционно, и вызывающий узнаёт об этом из {@link #namesKnown()}, а не из падения.
 * <p>
 * <b>Параметры собираются фабриками, а не конструктором.</b> Это не украшение:
 * следом за именами в контракт придут типы ({@code def f(x is int)}), и добавление
 * поля к {@code record} переписало бы каждое место создания параметра. Фабрика
 * добавляет перегрузку и не трогает ни одного существующего вызова.
 */
public final class Signature {

    /**
     * Параметр контракта: имя и то, чем заполняется пропуск.
     * <p>
     * Вариантов четыре, и различает их ровно один вопрос — <b>кто закроет пропуск</b>:
     * <ul>
     *   <li>{@link #required(String) обязательный} — никто, это ошибка вызова;</li>
     *   <li>{@link #optional(String, Value) с готовым значением} — связыватель: поля
     *       встроенного класса и необязательные аргументы библиотек, значение известно
     *       заранее;</li>
     *   <li>{@link #lazy(String) с отложенным значением} — сам вызываемый: у функции
     *       и класса на wdl по умолчанию стоит <b>выражение</b>, которое считается
     *       в области вызова и видит параметры левее себя;</li>
     *   <li>{@link #optional(String) необязательный без значения} — никто, но пропустить
     *       его в хвосте можно: так устроены встроенные функции, которые сами смотрят
     *       на длину списка ({@code window.add(комп)} против {@code add(комп, "North")}).
     *       Пропуск <b>в середине</b> у такого параметра — ошибка вызова, и это честнее
     *       молчаливой подстановки {@code null}: тело отличить одно от другого не сможет.</li>
     * </ul>
     */
    public static final class Param {

        private enum Kind {
            REQUIRED, OPTIONAL, CONSTANT, LAZY
        }

        private final String name;
        private final Kind kind;
        /** Готовое значение по умолчанию; заполнено только у {@link Kind#CONSTANT}. */
        private final Value constant;

        private Param(String name, Kind kind, Value constant) {
            this.name = Objects.requireNonNull(name, "name");
            this.kind = kind;
            this.constant = constant;
        }

        /** Обязательный: аргумент передать придётся. */
        public static Param required(String name) {
            return new Param(name, Kind.REQUIRED, null);
        }

        /** Необязательный без значения по умолчанию: пропустить можно только в хвосте. */
        public static Param optional(String name) {
            return new Param(name, Kind.OPTIONAL, null);
        }

        /** Необязательный с готовым значением: пропуск заполнит связыватель. */
        public static Param optional(String name, Value constant) {
            return new Param(name, Kind.CONSTANT, Objects.requireNonNull(constant, "constant"));
        }

        /** Необязательный с отложенным значением: пропуск заполнит сам вызываемый. */
        public static Param lazy(String name) {
            return new Param(name, Kind.LAZY, null);
        }

        public String name() {
            return name;
        }

        public boolean isRequired() {
            return kind == Kind.REQUIRED;
        }

        /** Готовое значение по умолчанию или {@code null}, если его нет. */
        public Value constant() {
            return constant;
        }

        /** Считает ли значение по умолчанию сам вызываемый. */
        public boolean isLazy() {
            return kind == Kind.LAZY;
        }

        /** Можно ли оставить позицию пустой в середине списка. */
        public boolean skippable() {
            return kind == Kind.LAZY || kind == Kind.CONSTANT;
        }

        @Override
        public String toString() {
            return switch (kind) {
                case REQUIRED -> name;
                case OPTIONAL -> name + "?";
                case CONSTANT -> name + " = " + constant.display();
                case LAZY -> name + " = ...";
            };
        }
    }

    private final List<Param> params;
    private final Arity arity;
    private final boolean namesKnown;
    /** Имя переменной для лишних позиционных аргументов или {@code null}. */
    private final String restName;
    /** Имя переменной для аргументов с неизвестными именами или {@code null}. */
    private final String namedRestName;

    private Signature(List<Param> params, Arity arity, boolean namesKnown,
                      String restName, String namedRestName) {
        this.params = params;
        this.arity = arity;
        this.namesKnown = namesKnown;
        this.restName = restName;
        this.namedRestName = namedRestName;
    }

    /**
     * Контракт с именами.
     * <p>
     * Обязательных столько, сколько их до первого необязательного: обязательный после
     * необязательного запрещён и разбором, и построителем встроенного класса, поэтому
     * арность остаётся отрезком. Здесь это ещё раз проверяется — контракт собирают
     * и приложения, а тихо принятый список сделал бы {@link #arity()} неправдой.
     */
    public static Signature of(Param... params) {
        return of(List.of(params));
    }

    public static Signature of(List<Param> params) {
        return of(params, null, null);
    }

    /**
     * Контракт с остатками: лишние позиционные аргументы собираются в {@code restName},
     * аргументы с неизвестными именами — в {@code namedRestName}.
     * <p>
     * Имена остатков лежат <b>отдельно от параметров</b> и в {@link #indexOf(String)}
     * не участвуют. Причина не в удобстве: остаток не занимает позиции, а
     * {@code f(args: 1)} не должен задавать {@code *args} целиком — иначе один
     * идентификатор значил бы и «положи в остаток», и «положи остаток».
     *
     * @param restName      имя для лишних позиционных или {@code null}
     * @param namedRestName имя для неизвестных имён или {@code null}
     */
    public static Signature of(List<Param> params, String restName, String namedRestName) {
        List<Param> copy = List.copyOf(Objects.requireNonNull(params, "params"));
        List<String> seen = new ArrayList<>(copy.size());
        boolean optionalSeen = false;
        for (Param param : copy) {
            if (seen.contains(param.name())) {
                throw new IllegalArgumentException("параметр '" + param.name() + "' уже объявлен");
            }
            seen.add(param.name());
            if (param.isRequired() && optionalSeen) {
                throw new IllegalArgumentException("параметр '" + param.name()
                        + "' без значения по умолчанию не может идти после параметра со значением");
            }
            optionalSeen |= !param.isRequired();
        }
        int required = 0;
        while (required < copy.size() && copy.get(required).isRequired()) {
            required++;
        }
        // С остатком верхней границы у числа аргументов нет вовсе — и это единственное,
        // что остаток меняет в контракте: имена параметров он не трогает.
        Arity arity = restName == null
                ? Arity.between(required, copy.size())
                : Arity.atLeast(required);
        return new Signature(copy, arity, true, restName, namedRestName);
    }

    /**
     * Контракт без имён: столько-то аргументов, а как их зовут — неизвестно.
     * <p>
     * Именно он достаётся по умолчанию всем, кто объявил одну лишь {@link Arity}, —
     * и это вся миграция для функций, написанных приложением: они продолжают работать,
     * просто не принимают имён.
     */
    public static Signature positional(Arity arity) {
        return new Signature(List.of(), Objects.requireNonNull(arity, "arity"), false, null, null);
    }

    /** Известны ли имена параметров — то есть можно ли звать эту функцию по имени. */
    public boolean namesKnown() {
        return namesKnown;
    }

    /** Собирает ли вызываемый лишние позиционные аргументы. */
    public boolean hasRest() {
        return restName != null;
    }

    /** Собирает ли вызываемый аргументы с неизвестными ему именами. */
    public boolean hasNamedRest() {
        return namedRestName != null;
    }

    /** Имя переменной для лишних позиционных аргументов или {@code null}. */
    public String restName() {
        return restName;
    }

    /** Имя переменной для аргументов с неизвестными именами или {@code null}. */
    public String namedRestName() {
        return namedRestName;
    }

    public List<Param> params() {
        return params;
    }

    public Arity arity() {
        return arity;
    }

    /**
     * Позиция параметра с таким именем или {@code -1}, если его нет.
     * <p>
     * Перебором, а не картой: параметров у функции единицы, и карта на три записи
     * стоит дороже, чем три сравнения строк, — зато создаётся она на каждую функцию,
     * а поиск идёт только при именованном вызове.
     */
    public int indexOf(String name) {
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        if (!namesKnown) {
            return "(" + arity.describe() + ")";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(params.get(i));
        }
        if (restName != null) {
            sb.append(sb.length() > 1 ? ", " : "").append('*').append(restName);
        }
        if (namedRestName != null) {
            sb.append(sb.length() > 1 ? ", " : "").append("**").append(namedRestName);
        }
        return sb.append(')').toString();
    }
}
