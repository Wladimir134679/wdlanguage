package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Расширение типа или класса своими членами:
 * {@code extend Array { property second => this[1] }}.
 * <p>
 * <b>Слова те же, что в теле класса,</b> и это не экономия на синтаксисе: {@code this} —
 * получатель, {@code property} — свойство, {@code def} — метод. Второго имени для того
 * же понятия язык не заводит, поэтому и разбирается тело тем же кодом, что тело класса.
 * Разница ровно одна: скрытого поля у члена типа не бывает — хранить его негде,
 * получатель чужой.
 * <p>
 * <b>Цель — выражение, а не имя.</b> Расширяют то, что стоит слева: дескриптор типа
 * ({@code extend Array}), класс из этого файла ({@code extend Point}) или класс
 * из модуля ({@code extend shapes.Point}). Класс — обычное значение, и требовать
 * здесь имя значило бы заводить исключение из этого правила ради одной конструкции.
 * <p>
 * <b>Только на верхнем уровне файла.</b> Иначе объявление внутри условия или функции
 * меняло бы поведение уже отработавшего кода в середине запуска. Верхний уровень
 * ограничивает место в тексте, но не момент во времени: модуль выполняется тогда,
 * когда его импортировали, — см. {@code Interpreter.visitExtend}.
 *
 * @param target     выражение, дающее тип или класс
 * @param label      как цель записана в исходнике — для сообщений об ошибках
 * @param methods    объявленные методы
 * @param properties объявленные свойства
 */
public record ExtendStmt(Expr target, String label, List<FunctionExpr> methods,
                         List<PropertyDecl> properties, Span span) implements Stmt {

    public ExtendStmt {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(label, "label");
        methods = List.copyOf(methods);
        properties = List.copyOf(properties);
    }
}
