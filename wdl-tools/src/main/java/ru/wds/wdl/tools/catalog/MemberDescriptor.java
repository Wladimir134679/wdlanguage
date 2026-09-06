package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.value.Arity;
import ru.wds.wdl.value.Member;

import java.util.Objects;

/**
 * Член значения или класса в каталоге: {@code a.size}, {@code text.upper()},
 * {@code f.read()}.
 * <p>
 * Вид здесь не два, а три, и третий — не педантизм. {@link Kind#SNAPSHOT} — это
 * свойство, которое собирает новое значение на каждое чтение ({@link Member#snapshot()}),
 * и подсказка обязана это показывать: иначе автор положит снимок в переменную
 * и удивится, что она не меняется, либо оставит его в условии цикла и заплатит
 * за пересборку на каждом шаге.
 *
 * @param name          имя члена
 * @param kind          свойство, снимок или метод
 * @param arity         сколько аргументов принимает метод; у свойства — ноль
 * @param signature     краткая запись для подсказки: {@code size}, {@code push(…)}
 * @param documentation описание или {@code null}
 * @param resultClass   известный класс результата вызова/чтения или {@code null}
 */
public record MemberDescriptor(String name, Kind kind, Arity arity,
                               String signature, String documentation, String resultClass) {

    /** Что именно за член — от этого зависит и запись, и цена чтения. */
    public enum Kind {

        /** Читается без скобок и отвечает тем, что и так лежит в значении. */
        PROPERTY("свойство"),

        /** Читается без скобок, но собирает новое значение на каждое чтение. */
        SNAPSHOT("снимок"),

        /** Зовётся со скобками. */
        METHOD("метод");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public MemberDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        arity = arity == null ? Arity.exactly(0) : arity;
        signature = signature == null ? render(name, kind, arity) : signature;
    }

    public MemberDescriptor(String name, Kind kind, Arity arity, String signature,
                            String documentation) {
        this(name, kind, arity, signature, documentation, null);
    }

    /** Свойство или снимок — по ответу самого члена, а не по догадке об имени. */
    public static MemberDescriptor property(String name, boolean snapshot, String documentation) {
        return new MemberDescriptor(name, snapshot ? Kind.SNAPSHOT : Kind.PROPERTY,
                Arity.exactly(0), null, documentation);
    }

    /** Метод с известной арностью, но без имён параметров: так устроены члены типов. */
    public static MemberDescriptor method(String name, Arity arity, String documentation) {
        return new MemberDescriptor(name, Kind.METHOD, arity, null, documentation);
    }

    /** Метод с известными именами параметров: так устроены методы классов. */
    public static MemberDescriptor method(String name, Arity arity, String signature,
                                          String documentation) {
        return new MemberDescriptor(name, Kind.METHOD, arity, signature, documentation);
    }

    /** Метод, чья декларация точно называет класс возвращаемого значения. */
    public static MemberDescriptor method(String name, Arity arity, String signature,
                                          String documentation, String resultClass) {
        return new MemberDescriptor(name, Kind.METHOD, arity, signature, documentation,
                resultClass);
    }

    /** Член значения как есть — вид, арность и цена спрашиваются у него самого. */
    public static MemberDescriptor of(Member member, String documentation) {
        Objects.requireNonNull(member, "member");
        if (member.isProperty()) {
            return property(member.name(), member.snapshot(), documentation);
        }
        return method(member.name(), member.arity(), documentation);
    }

    public boolean hasDocumentation() {
        return documentation != null && !documentation.isBlank();
    }

    /** Зовётся ли член со скобками — вопрос, который задаёт дополнение. */
    public boolean isCallable() {
        return kind == Kind.METHOD;
    }

    /**
     * Имён параметров у члена типа нет и взять их негде, поэтому и не выдумываются:
     * многоточие честно говорит «сюда что-то идёт», а число аргументов лежит рядом,
     * в {@link #arity()}.
     */
    private static String render(String name, Kind kind, Arity arity) {
        if (kind != Kind.METHOD) {
            return name;
        }
        return arity.max() == 0 ? name + "()" : name + "(…)";
    }

    @Override
    public String toString() {
        return kind.title() + " '" + signature + "'";
    }
}
