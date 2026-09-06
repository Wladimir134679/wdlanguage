package ru.wds.wdl.tools.service;

import ru.wds.wdl.source.Span;
import ru.wds.wdl.tools.analysis.SymbolKind;

import java.util.List;
import java.util.Objects;

/**
 * Состав файла деревом: то, что редактор показывает в структуре и в списке
 * «перейти к символу».
 * <p>
 * Два интервала, а не один, потому что вопросов тоже два: {@code span} — сколько
 * места занимает объявление (по нему подсвечивают строку и сворачивают), а
 * {@code nameSpan} — где стоит само имя (по нему ставят курсор).
 *
 * @param name      имя
 * @param kind      чем объявлено
 * @param signature краткая запись объявления
 * @param span      всё объявление целиком
 * @param nameSpan  интервал имени
 * @param children  вложенные объявления: методы класса, функция внутри функции
 */
public record Outline(String name, SymbolKind kind, String signature,
                      Span span, Span nameSpan, List<Outline> children) {

    public Outline {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(nameSpan, "nameSpan");
        signature = signature == null ? name : signature;
        children = children == null ? List.of() : List.copyOf(children);
    }

    @Override
    public String toString() {
        return kind.title() + " " + signature
                + (children.isEmpty() ? "" : " (вложено: " + children.size() + ")");
    }
}
