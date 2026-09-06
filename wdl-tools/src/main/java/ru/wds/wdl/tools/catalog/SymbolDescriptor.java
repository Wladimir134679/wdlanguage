package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.tools.analysis.SymbolKind;

import java.util.List;
import java.util.Objects;

/**
 * Имя, которого в файле нет: встроенная функция, класс библиотеки, имя модуля.
 * <p>
 * Это <b>тот же ответ, что и {@link ru.wds.wdl.tools.analysis.Symbol}</b>, только
 * без места в тексте: перейти к объявлению {@code println} некуда, а показать
 * сигнатуру и описание — надо. Поэтому и вид берётся тем же {@link SymbolKind}:
 * словарь один, иначе редактор нарисовал бы два разных значка для функции —
 * смотря откуда она пришла.
 * <p>
 * В отличие от символа файла, вид здесь берётся из <b>значения</b>, а не из записи:
 * каталог снимается с живой области, где имя уже чем-то стало.
 *
 * @param name          имя
 * @param kind          чем имя оказалось: функция, класс, трейт, модуль, константа
 * @param signature     краткая запись для подсказки: {@code pow(a, b)}, {@code class File(path)}
 * @param documentation описание, объявленное рядом с именем, или {@code null}
 * @param origin        откуда имя взялось
 * @param members       экземплярные члены, если это класс или трейт; иначе пусто
 * @param staticMembers члены самого класса ({@code File.temp}); иначе пусто
 */
public record SymbolDescriptor(String name, SymbolKind kind, String signature,
                               String documentation, Origin origin,
                               List<MemberDescriptor> members,
                               List<MemberDescriptor> staticMembers) {

    public SymbolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(origin, "origin");
        signature = signature == null ? name : signature;
        members = members == null ? List.of() : List.copyOf(members);
        staticMembers = staticMembers == null ? List.of() : List.copyOf(staticMembers);
    }

    public SymbolDescriptor(String name, SymbolKind kind, String signature,
                            String documentation, Origin origin) {
        this(name, kind, signature, documentation, origin, List.of(), List.of());
    }

    public SymbolDescriptor(String name, SymbolKind kind, String signature,
                            String documentation, Origin origin,
                            List<MemberDescriptor> members) {
        this(name, kind, signature, documentation, origin, members, List.of());
    }

    public boolean hasDocumentation() {
        return documentation != null && !documentation.isBlank();
    }

    /** Зовётся ли имя как функция — вопрос дополнения; ответ тот же, что у символа файла. */
    public boolean isCallable() {
        return kind.isCallable();
    }

    /** Член с таким именем или {@code null}: {@code File.read} без запуска. */
    public MemberDescriptor member(String memberName) {
        for (MemberDescriptor member : members) {
            if (member.name().equals(memberName)) {
                return member;
            }
        }
        return null;
    }

    /** Статический член класса или {@code null}: {@code File.temp} без запуска. */
    public MemberDescriptor staticMember(String memberName) {
        for (MemberDescriptor member : staticMembers) {
            if (member.name().equals(memberName)) {
                return member;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return kind.title() + " '" + signature + "' (" + origin.title() + ")";
    }
}
