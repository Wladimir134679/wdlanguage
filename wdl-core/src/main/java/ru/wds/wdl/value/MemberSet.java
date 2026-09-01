package ru.wds.wdl.value;

import ru.wds.wdl.source.Span;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Набор членов одного типа или одного класса: имя — {@link Member}.
 * <p>
 * Неизменяем и собирается построителем, а не картой снаружи: набор ядра обязан быть
 * неперекрываемым, и отдать его изменяемым значило бы разрешить одной строке скрипта
 * поменять поведение всех значений этого типа во всём процессе.
 * <p>
 * Тем же построителем набор собирает и приложение — приём тот же, что у
 * {@code bridge.NativeClass}: имена и арность видны в одном месте, опечатку ловит
 * компилятор, рефлексия не нужна.
 *
 * <pre>{@code
 * MemberSet.builder()
 *         .property("size", (receiver, context, span) -> IntValue.of(((ArrayValue) receiver).size()))
 *         .method("push", Arity.exactly(1), (receiver, context, arguments, span) -> { ... })
 *         .build();
 * }</pre>
 */
public final class MemberSet {

    public static final MemberSet EMPTY = new MemberSet(Map.of());

    private final Map<String, Member> members;

    private MemberSet(Map<String, Member> members) {
        this.members = members;
    }

    /** Член с таким именем или {@code null}. */
    public Member get(String name) {
        return members.get(name);
    }

    public boolean has(String name) {
        return members.containsKey(name);
    }

    /** Имена в порядке объявления — для сообщения «а есть вот что». */
    public Set<String> names() {
        return members.keySet();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Чтение свойства: получателем служит само значение, а не экземпляр класса. */
    @FunctionalInterface
    public interface Getter {
        Value get(Value receiver, CallContext context, Span span);
    }

    /**
     * Тело метода. Число аргументов уже проверено вызывающим по {@link Member#arity()} —
     * то же правило, что у функции и у класса: сообщение одинаково для всех, а тело
     * начинается с дела, а не с проверок.
     */
    @FunctionalInterface
    public interface Body {
        Value call(Value receiver, CallContext context, List<Value> arguments, Span span);
    }

    /** Построитель набора: свойства, свойства-снимки и методы. */
    public static final class Builder {

        private final Map<String, Member> members = new LinkedHashMap<>();

        private Builder() {
        }

        /** Свойство: читается без скобок, получателя не меняет. */
        public Builder property(String name, Getter getter) {
            return add(new PropertyMember(name, getter, false));
        }

        /**
         * Свойство-снимок: то же чтение, но новое значение собирается заново каждый раз.
         * Помечено не ради оптимизации в ядре, а ради линтера — см. {@link Member#snapshot()}.
         */
        public Builder snapshot(String name, Getter getter) {
            return add(new PropertyMember(name, getter, true));
        }

        /** Метод: вызывается со скобками, получатель приходит первым в тело. */
        public Builder method(String name, Arity arity, Body body) {
            return add(new MethodMember(name, arity, body));
        }

        /**
         * Добавляет всё из готового набора — тем, кто достраивает основание, а не
         * заводит своё: универсальный член «тип» одинаков у всех типов, и писать его
         * десять раз в десяти наборах значило бы десять раз ошибиться.
         */
        public Builder include(MemberSet set) {
            for (Member member : set.members.values()) {
                add(member);
            }
            return this;
        }

        private Builder add(Member member) {
            if (members.putIfAbsent(member.name(), member) != null) {
                throw new IllegalArgumentException("член '" + member.name() + "' уже объявлен");
            }
            return this;
        }

        public MemberSet build() {
            return members.isEmpty() ? EMPTY : new MemberSet(Collections.unmodifiableMap(new LinkedHashMap<>(members)));
        }
    }

    /** Свойство набора: {@link Property} без записи — сеттеров у встроенных членов нет. */
    private record PropertyMember(String name, Getter getter, boolean snapshot)
            implements Member, Property {

        private PropertyMember {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(getter, "getter");
        }

        @Override
        public Property property() {
            return this;
        }

        @Override
        public FunctionValue bind(Value receiver) {
            return null;
        }

        @Override
        public Arity arity() {
            return Arity.exactly(0);
        }

        @Override
        public boolean readable() {
            return true;
        }

        /**
         * Записи у члена-свойства нет, и это следует из правила, а не из лени:
         * присваивание в свойство — действие, замаскированное под имя, а имя обещает,
         * что за ним ничего не происходит.
         */
        @Override
        public boolean writable() {
            return false;
        }

        @Override
        public Value read(Value receiver, CallContext context, Span span) {
            return getter.get(receiver, context, span);
        }

        @Override
        public void write(Value receiver, Value value, CallContext context, Span span) {
            throw new IllegalStateException("вызывающий обязан проверить writable() до записи");
        }
    }

    /** Метод набора: {@link #bind} даёт обычную функцию, замкнутую на получателя. */
    private record MethodMember(String name, Arity arity, Body body) implements Member {

        private MethodMember {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arity, "arity");
            Objects.requireNonNull(body, "body");
        }

        @Override
        public Property property() {
            return null;
        }

        @Override
        public FunctionValue bind(Value receiver) {
            return new BoundMethod(name, arity, body, receiver);
        }

        @Override
        public boolean snapshot() {
            return false;
        }
    }

    /**
     * Метод, связанный с получателем.
     * <p>
     * Обычное значение-функция: {@code push = a.push} кладёт в переменную именно её,
     * и массив не теряется. Своё значение на каждое чтение, и сравнивается оно
     * по ссылке — поэтому {@code a.push == a.push} ложь, ровно как у метода класса.
     * <p>
     * Классом, а не {@code record}, именно из-за этого: {@code record} сравнивал бы
     * обёртки по содержимому, и два чтения одного метода у одного значения оказались бы
     * равны, тогда как у метода класса — нет. Одно понятие не должно вести себя
     * по-разному в зависимости от того, где объявлен член.
     */
    private static final class BoundMethod implements FunctionValue {

        private final String name;
        private final Arity arity;
        private final Body body;
        private final Value receiver;

        private BoundMethod(String name, Arity arity, Body body, Value receiver) {
            this.name = name;
            this.arity = arity;
            this.body = body;
            this.receiver = receiver;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Arity arity() {
            return arity;
        }

        @Override
        public Value call(CallContext context, List<Value> arguments, Span span) {
            return body.call(receiver, context, arguments, span);
        }

        @Override
        public String display() {
            return "def " + name;
        }

        @Override
        public String toString() {
            return display();
        }
    }
}
