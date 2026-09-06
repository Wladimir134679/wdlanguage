package ru.wds.wdl.tools.service;

import ru.wds.wdl.tools.analysis.FileAnalysis;
import ru.wds.wdl.tools.analysis.LexicalScope;
import ru.wds.wdl.tools.analysis.ScopeKind;
import ru.wds.wdl.tools.analysis.Symbol;
import ru.wds.wdl.tools.analysis.SymbolKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Состав файла: дерево областей, свёрнутое до того, что стоит показывать.
 * <p>
 * Строится из областей, а не обходом дерева заново: области уже знают, что кому
 * принадлежит — методы классу, вложенная функция внешней, — и второй обход
 * с теми же правилами разошёлся бы с первым на первой же правке языка.
 * <p>
 * Показывается не всё. Параметр, переменная цикла и локальная переменная — детали
 * одного объявления, а не состав файла; в структуре они превратили бы список
 * из десяти строк в список из двухсот. Переменная верхнего уровня — исключение:
 * там она и есть состав файла.
 */
final class Outlines {

    /** Что показывать на любой глубине. */
    private static final Set<SymbolKind> SHOWN = EnumSet.of(
            SymbolKind.FUNCTION, SymbolKind.CLASS, SymbolKind.TRAIT, SymbolKind.METHOD,
            SymbolKind.PROPERTY, SymbolKind.FACTORY, SymbolKind.REQUIREMENT,
            SymbolKind.CONSTANT, SymbolKind.MODULE);

    private Outlines() {
    }

    static List<Outline> of(FileAnalysis analysis) {
        return from(analysis.scopes());
    }

    private static List<Outline> from(LexicalScope scope) {
        boolean file = scope.kind() == ScopeKind.FILE;
        List<Outline> items = new ArrayList<>();
        for (Symbol symbol : scope.symbols()) {
            if (!SHOWN.contains(symbol.kind()) && !(file && symbol.kind() == SymbolKind.VARIABLE)) {
                continue;
            }
            items.add(new Outline(symbol.name(), symbol.kind(), symbol.signature(),
                    symbol.span(), symbol.nameSpan(), childrenOf(scope, symbol)));
        }
        return List.copyOf(items);
    }

    /**
     * Вложенное — это область, которой владеет само объявление: тело класса
     * у класса, тело функции у функции. Сравнение по тождеству узла, а не по
     * интервалу: интервалы объявления и его тела совпадают у выражения-функции.
     */
    private static List<Outline> childrenOf(LexicalScope scope, Symbol symbol) {
        for (LexicalScope child : scope.children()) {
            if (child.owner() == symbol.declaration()) {
                return from(child);
            }
        }
        return List.of();
    }
}
