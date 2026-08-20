package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Объявление класса: {@code class Point(x = 0, y = 0) : Shape("точка") with Printable { ... }}.
 * <p>
 * <b>Заголовок — это поля.</b> Список параметров задаёт и параметры создания,
 * и набор полей; отдельного объявления полей нет. Поэтому здесь тот же
 * {@link FunctionExpr.Param}, что у функции, — со значениями по умолчанию и теми же
 * правилами. Поля, разбросанные по конструктору, нельзя перечислить, не выполнив его;
 * заголовок известен движку до всякого выполнения, отсюда и проверка числа аргументов
 * до входа в конструктор, и внятная печать.
 * <p>
 * <b>Полями становятся только позиционные параметры.</b> Остаток — {@code *args}
 * и {@code **named} — позиции не занимает, а список полей читается как список позиций;
 * ровно та же причина, по которой остаток лежит отдельно и у {@link FunctionExpr}.
 * В экземпляр он не пишется: иначе прокси-класс, собранный декоратором, подмешивал бы
 * каждому объекту два лишних поля, и обёртка перестала бы быть незаметной — их увидели
 * бы и перебор по объекту, и {@code len}, и печать. Виден остаток там, где выполняется
 * заголовок: в аргументах родителю ({@code class Head(n, *rest) : Base(rest, n)})
 * и в конструкторе своего класса.
 * <p>
 * <b>Тело — только объявления функций.</b> Класс это описание, а не код, который
 * что-то делает в момент объявления, поэтому любая другая инструкция в теле —
 * ошибка разбора. Разложены объявления по трём полям, потому что ведут себя
 * по-разному: {@link #constructor()} выполняется по готовому объекту и в таблицу
 * методов не попадает, {@link #methods()} достаются экземпляру, {@link #factories()}
 * живут на самом классе и экземпляра не имеют.
 *
 * @param params      позиционные параметры заголовка, они же поля
 * @param rest        остаточный параметр {@code *args} или {@code null}
 * @param namedRest   именованный остаток {@code **named} или {@code null}
 * @param parent      родитель или {@code null}; родитель ровно один — из-за конструктора,
 *                    а не из-за конфликтов имён
 * @param constructor тело {@code def Point()} или {@code null}
 */
public record ClassDeclStmt(
        String name,
        Span nameSpan,
        List<FunctionExpr.Param> params,
        FunctionExpr.Rest rest,
        FunctionExpr.Rest namedRest,
        Superclass parent,
        List<TraitRef> traits,
        FunctionExpr constructor,
        List<FunctionExpr> methods,
        List<Factory> factories,
        Span span) implements Stmt {

    public ClassDeclStmt {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(traits, "traits");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(factories, "factories");
    }

    public boolean hasParent() {
        return parent != null;
    }

    /** Собирает ли заголовок лишние аргументы — то есть безгранично ли их число. */
    public boolean isVariadic() {
        return rest != null;
    }

    public boolean hasConstructor() {
        return constructor != null;
    }

    /**
     * Родитель и аргументы его заголовка: {@code : Shape("круг")}, {@code : m.Shape()},
     * {@code : registry.classes["Shape"]}.
     * <p>
     * После двоеточия стоит <b>выражение</b> — цепочка обращений и вызовов, дающая
     * класс. Простое имя ({@code Shape}) — частный случай и разбирается тем же кодом:
     * родитель ищется среди значений, видимых в этой точке, поэтому объявление родителя
     * обязано стоять <b>выше</b> объявления потомка. Правило одно на все формы записи —
     * {@code Shape}, {@code m.Shape}, {@code registry["Shape"]} — и то же самое, что
     * у переменной: имя существует с той строки, где его завели.
     * <p>
     * Отсюда же и цена, которую язык платит осознанно: <b>последние скобки — это
     * аргументы заголовка, а не вызов</b>. {@code : make()} означает «родитель
     * {@code make}, аргументов нет», а не «вызвать {@code make} и наследоваться
     * от результата». Иначе {@code : Shape("круг")} перестало бы значить то,
     * что значит, — а это основная форма записи. Вызов внутри цепочки при этом
     * обычный: у {@code : registry.all()["Shape"]} последняя операция — обращение,
     * и скобки достаются {@code all}.
     * <p>
     * Аргументы вычисляются там, где параметры потомка уже связаны:
     * {@code class Square(side) : Shape("сторона " + side)}.
     *
     * @param type выражение, дающее класс; чаще всего {@code VariableExpr}
     */
    public record Superclass(Expr type, List<Argument> arguments, Span span) {

        public Superclass {
            Objects.requireNonNull(type, "type");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }

        /** Запись так, как она стоит в исходнике: {@code Shape}, {@code m.Shape}. */
        public String title() {
            return type.toString();
        }
    }

    /**
     * Подмешанный трейт. Здесь тоже выражение, и по той же причине, что у родителя.
     * <p>
     * Разница с родителем ровно одна: скобки после трейта — обычный вызов, а не
     * аргументы. Трейту передавать нечего — конструктора у него нет, — поэтому
     * {@code with make()} однозначно означает «вызвать и подмешать результат».
     */
    public record TraitRef(Expr type, Span span) {

        public TraitRef {
            Objects.requireNonNull(type, "type");
        }

        public String title() {
            return type.toString();
        }
    }

    /**
     * Функция, живущая на классе, а не на экземпляре: {@code def User.of(...)}.
     * <p>
     * Не отдельный вид члена, как {@code static} в Java, а <b>место записи</b>:
     * {@code User.of} — то же самое обращение по ключу, что {@code user.name},
     * и ровно то же самое можно написать снаружи присваиванием. Форма нужна затем,
     * чтобы способ создания читался вместе с классом.
     *
     * @param name короткое имя ({@code of}); полное — в {@code function.name()}
     */
    public record Factory(String name, FunctionExpr function, Span span) {
    }
}
