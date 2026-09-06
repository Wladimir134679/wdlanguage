package ru.wds.wdl.tools.analysis;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Объявленное имя: что объявлено, где написано имя и где стоит всё объявление.
 * <p>
 * Символ — <b>объявление, а не значение</b>. Чем имя окажется в момент выполнения,
 * анализ не решает: он читает текст. Отсюда и {@link #kind()} по форме записи —
 * {@code f = def(x) => x} даёт переменную, а не функцию.
 * <p>
 * Два интервала, и оба нужны разным вопросам. {@link #nameSpan()} — то, к чему ведёт
 * переход к объявлению и что меняет переименование; {@link #span()} — объявление
 * целиком, им подсвечивают и сворачивают.
 *
 * @param name          имя, как оно написано
 * @param kind          чем объявлено
 * @param nameSpan      место имени
 * @param span          место объявления целиком
 * @param declaration   узел объявления: по нему берут подробности, которых нет здесь
 * @param documentation комментарий над объявлением или {@code null}
 */
public record Symbol(String name, SymbolKind kind, Span nameSpan, Span span,
                     Node declaration, String documentation) {

    public Symbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nameSpan, "nameSpan");
        span = span == null ? nameSpan : span;
        Objects.requireNonNull(declaration, "declaration");
    }

    public Symbol(String name, SymbolKind kind, Span nameSpan, Span span, Node declaration) {
        this(name, kind, nameSpan, span, declaration, null);
    }

    /** Тот же символ с привязанной документацией: комментарии находятся отдельным проходом. */
    public Symbol withDocumentation(String documentation) {
        return new Symbol(name, kind, nameSpan, span, declaration, documentation);
    }

    public boolean hasDocumentation() {
        return documentation != null && !documentation.isBlank();
    }

    /**
     * Краткая запись объявления для подсказки: {@code total(price, count = 1)},
     * {@code class Rect(width, height)}.
     * <p>
     * Собирается из дерева, а не из исходника: заголовок может быть разбит переносами
     * и комментариями, а в подсказке нужна одна строка. Значения по умолчанию
     * печатаются так, как записаны, — вычислять их анализ не вправе.
     */
    public String signature() {
        return switch (declaration) {
            case FunctionExpr function -> name + parameters(function.params(),
                    function.rest(), function.namedRest());
            case ClassDeclStmt type -> "class " + name + parameters(type.params(),
                    type.rest(), type.namedRest());
            case TraitDeclStmt type -> "trait " + name + parameters(type.params(), null, null);
            case TraitDeclStmt.Requirement requirement -> name
                    + parameters(requirement.params(), null, null);
            default -> name;
        };
    }

    private static String parameters(List<FunctionExpr.Param> params,
                                     FunctionExpr.Rest rest, FunctionExpr.Rest namedRest) {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");
        params.forEach(param -> joiner.add(param.toString()));
        if (rest != null) {
            joiner.add("*" + rest.name());
        }
        if (namedRest != null) {
            joiner.add("**" + namedRest.name());
        }
        return joiner.toString();
    }

    @Override
    public String toString() {
        return kind.title() + " '" + name + "' " + nameSpan;
    }
}
