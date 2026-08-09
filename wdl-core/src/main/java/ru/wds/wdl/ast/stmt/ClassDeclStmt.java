package ru.wds.wdl.ast.stmt;

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
 * <b>Тело — только объявления функций.</b> Класс это описание, а не код, который
 * что-то делает в момент объявления, поэтому любая другая инструкция в теле —
 * ошибка разбора. Разложены объявления по трём полям, потому что ведут себя
 * по-разному: {@link #constructor()} выполняется по готовому объекту и в таблицу
 * методов не попадает, {@link #methods()} достаются экземпляру, {@link #factories()}
 * живут на самом классе и экземпляра не имеют.
 *
 * @param parent      родитель или {@code null}; родитель ровно один — из-за конструктора,
 *                    а не из-за конфликтов имён
 * @param constructor тело {@code fun Point()} или {@code null}
 */
public record ClassDeclStmt(
        String name,
        Span nameSpan,
        List<FunctionExpr.Param> params,
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

    public boolean hasConstructor() {
        return constructor != null;
    }

    /**
     * Родитель и аргументы его заголовка: {@code : Shape("круг")}.
     * <p>
     * После двоеточия стоит <b>имя</b>, а не произвольное выражение, — ради этого
     * и разрешено наследоваться от класса, объявленного ниже по тексту: имена можно
     * связать заранее, а вычисление выражения до первой инструкции скрипта было бы
     * выполнением в момент, когда скрипт ещё не начался.
     * <p>
     * Аргументы — обычные выражения, и вычисляются они там, где параметры потомка
     * уже связаны: {@code class Square(side) : Shape("сторона " + side)}.
     */
    public record Superclass(String name, List<Expr> arguments, Span span) {
    }

    /** Подмешанный трейт. Здесь тоже имя, и по той же причине, что у родителя. */
    public record TraitRef(String name, Span span) {
    }

    /**
     * Функция, живущая на классе, а не на экземпляре: {@code fun User.of(...)}.
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
