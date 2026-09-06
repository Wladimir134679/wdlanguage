package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolInformation;
import ru.wds.wdl.diagnostic.DiagnosticCode;
import ru.wds.wdl.diagnostic.Severity;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.tools.catalog.Suggestion;
import ru.wds.wdl.tools.service.HighlightToken;
import ru.wds.wdl.tools.service.Hover;
import ru.wds.wdl.tools.service.CallSignature;
import ru.wds.wdl.tools.service.Outline;
import ru.wds.wdl.tools.service.TokenStyle;
import ru.wds.wdl.tools.service.WorkspaceSymbol;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Перевод ответов сервиса в понятия протокола — и обратно.
 * <p>
 * Единственное место в сервере, где вообще есть арифметика. Стоит она на двух
 * совпадениях, о которых стоит помнить: {@link Span} — индексы в {@code String},
 * то есть единицы UTF-16, и позиции LSP по умолчанию считаются в тех же единицах;
 * а нумерация у нас с единицы, в протоколе — с нуля. Больше между этими системами
 * координат ничего нет.
 * <p>
 * Позиции от клиента приводятся к тексту, а не проверяются: редактор вправе прислать
 * столбец за концом строки (курсор в конце), и отвечать на это отказом значило бы
 * ронять запрос там, где ответ очевиден.
 */
final class Protocol {

    /**
     * Виды окрашенных кусков — по порядку: номер в этом списке и есть то, что уходит
     * в поток чисел. Названия взяты из спецификации, свои придумывать нельзя:
     * клиент сопоставляет их со своей темой по имени.
     */
    static final List<String> TOKEN_TYPES = List.of(
            "keyword", "comment", "string", "number", "operator", "variable",
            "parameter", "function", "method", "property", "class", "interface",
            "namespace");

    /** Признаки поверх вида: {@code declaration} — объявление, {@code readonly} — константа. */
    static final List<String> TOKEN_MODIFIERS = List.of("declaration", "readonly");

    static final SemanticTokensLegend LEGEND =
            new SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS);

    private static final int DECLARATION = 1;
    private static final int READONLY = 2;

    /**
     * Чем красить: наш вид — имя из спецификации. {@link TokenStyle#BAD} здесь нет
     * намеренно — вида «мусор» в протоколе не существует, и такой кусок просто
     * не красится: о нём уже сказано диагностикой.
     */
    private static final Map<TokenStyle, Integer> TOKEN_TYPE_INDEX = typeIndex();

    private Protocol() {
    }

    // --- позиции ------------------------------------------------------------

    static Position position(Source source, int offset) {
        int clamped = Math.max(0, Math.min(offset, source.length()));
        ru.wds.wdl.source.Position position = source.positionOf(clamped);
        return new Position(position.line() - 1, position.column() - 1);
    }

    static Range range(Source source, Span span) {
        if (span == null || span.isNone()) {
            return new Range(position(source, 0), position(source, 0));
        }
        return new Range(position(source, span.start()), position(source, span.end()));
    }

    /** Позиция клиента в смещение. Строка и столбец за концом текста прижимаются к нему. */
    static int offset(Source source, Position position) {
        int line = position.getLine() + 1;
        if (line < 1) {
            return 0;
        }
        if (line > source.lineCount()) {
            return source.length();
        }
        int lineStart = source.offsetOf(line, 1);
        int lineEnd = lineStart + source.lineText(line).length();
        return Math.min(lineEnd, lineStart + Math.max(0, position.getCharacter()));
    }

    // --- ответы -------------------------------------------------------------

    static org.eclipse.lsp4j.Diagnostic diagnostic(Source source,
                                                   ru.wds.wdl.diagnostic.Diagnostic diagnostic) {
        org.eclipse.lsp4j.Diagnostic result = new org.eclipse.lsp4j.Diagnostic(
                range(source, diagnostic.span()), diagnostic.message(),
                severity(diagnostic.severity()), "wdl");
        if (diagnostic.code() != DiagnosticCode.NONE) {
            result.setCode(diagnostic.code().name());
        }
        return result;
    }

    static DiagnosticSeverity severity(Severity severity) {
        return severity == Severity.WARNING ? DiagnosticSeverity.Warning : DiagnosticSeverity.Error;
    }

    static CompletionItem completion(Suggestion suggestion) {
        CompletionItem item = new CompletionItem(suggestion.name());
        item.setKind(completionKind(suggestion.kind()));
        item.setDetail(suggestion.signature());
        if (suggestion.hasDocumentation()) {
            item.setDocumentation(new MarkupContent(MarkupKind.PLAINTEXT,
                    suggestion.documentation()));
        }
        // Имена файла идут выше имён каталога: своё ближе, чем встроенное.
        item.setSortText((suggestion.isDeclaredHere() ? "0" : "1") + suggestion.name());
        return item;
    }

    static org.eclipse.lsp4j.Hover hover(Source source, Hover hover) {
        StringBuilder text = new StringBuilder(hover.signature());
        text.append("\n\n").append(hover.kind().title())
                .append(", ").append(hover.origin().title());
        if (hover.hasDocumentation()) {
            text.append("\n\n").append(hover.documentation());
        }
        return new org.eclipse.lsp4j.Hover(
                new MarkupContent(MarkupKind.PLAINTEXT, text.toString()),
                range(source, hover.span()));
    }

    static SignatureHelp signature(CallSignature signature) {
        SignatureInformation information = new SignatureInformation(signature.label());
        if (signature.documentation() != null && !signature.documentation().isBlank()) {
            information.setDocumentation(signature.documentation());
        }
        return new SignatureHelp(List.of(information), 0, signature.activeParameter());
    }

    static Location location(String uri, Source source, Span span) {
        return new Location(uri, range(source, span));
    }

    static SymbolInformation workspaceSymbol(Source source, WorkspaceSymbol symbol) {
        return new SymbolInformation(symbol.name(), symbolKind(symbol.kind()),
                location(symbol.document().uri(), source, symbol.span()), symbol.container());
    }

    static DocumentSymbol documentSymbol(Source source, Outline outline) {
        DocumentSymbol symbol = new DocumentSymbol(outline.name(), symbolKind(outline.kind()),
                range(source, outline.span()), range(source, outline.nameSpan()),
                outline.signature());
        if (!outline.children().isEmpty()) {
            List<DocumentSymbol> children = new ArrayList<>(outline.children().size());
            outline.children().forEach(child -> children.add(documentSymbol(source, child)));
            symbol.setChildren(children);
        }
        return symbol;
    }

    /**
     * Окрашенные куски в поток чисел: пятёрка на кусок, каждое число — разница
     * с предыдущим.
     * <p>
     * Кусок, пересекающий перевод строки (блочный комментарий), режется по строкам:
     * протокол считает строку и столбец, и токен через границу строки в нём выразить
     * нечем. Резать приходится здесь, а не в сервисе: во встроенном редакторе
     * комментарий — один кусок, и делить его там значило бы делить ради чужого
     * ограничения.
     */
    static SemanticTokens semanticTokens(Source source, List<HighlightToken> tokens) {
        List<Integer> data = new ArrayList<>(tokens.size() * 5);
        int lastLine = 0;
        int lastStart = 0;
        for (HighlightToken token : tokens) {
            Integer type = TOKEN_TYPE_INDEX.get(token.style());
            if (type == null) {
                continue;
            }
            int modifiers = (token.declaration() ? DECLARATION : 0)
                    | (token.style() == TokenStyle.CONSTANT ? READONLY : 0);
            for (int[] piece : byLines(source, token.span())) {
                int line = piece[0];
                int start = piece[1];
                data.add(line - lastLine);
                data.add(line == lastLine ? start - lastStart : start);
                data.add(piece[2]);
                data.add(type);
                data.add(modifiers);
                lastLine = line;
                lastStart = start;
            }
        }
        return new SemanticTokens(data);
    }

    /** Интервал по строкам: {@code {строка с нуля, столбец с нуля, длина}}. */
    private static List<int[]> byLines(Source source, Span span) {
        if (span.isNone() || span.length() == 0) {
            return List.of();
        }
        List<int[]> pieces = new ArrayList<>(1);
        int firstLine = source.positionOf(span.start()).line();
        int lastLine = source.positionOf(Math.min(span.end(), source.length())).line();
        for (int line = firstLine; line <= lastLine; line++) {
            int lineStart = source.offsetOf(line, 1);
            int lineEnd = lineStart + source.lineText(line).length();
            int from = Math.max(span.start(), lineStart);
            int to = Math.min(span.end(), lineEnd);
            if (to > from) {
                pieces.add(new int[]{line - 1, from - lineStart, to - from});
            }
        }
        return pieces;
    }

    // --- виды ---------------------------------------------------------------

    static CompletionItemKind completionKind(ru.wds.wdl.tools.analysis.SymbolKind kind) {
        return switch (kind) {
            case FUNCTION -> CompletionItemKind.Function;
            case METHOD, FACTORY, REQUIREMENT -> CompletionItemKind.Method;
            case CLASS -> CompletionItemKind.Class;
            case TRAIT -> CompletionItemKind.Interface;
            case PROPERTY, FIELD -> CompletionItemKind.Property;
            case CONSTANT -> CompletionItemKind.Constant;
            case MODULE -> CompletionItemKind.Module;
            case PARAMETER, REST -> CompletionItemKind.Variable;
            case VARIABLE, LOOP_VARIABLE, CATCH_VARIABLE, RESOURCE -> CompletionItemKind.Variable;
        };
    }

    static SymbolKind symbolKind(ru.wds.wdl.tools.analysis.SymbolKind kind) {
        return switch (kind) {
            case FUNCTION -> SymbolKind.Function;
            case METHOD, FACTORY, REQUIREMENT -> SymbolKind.Method;
            case CLASS -> SymbolKind.Class;
            case TRAIT -> SymbolKind.Interface;
            case PROPERTY, FIELD -> SymbolKind.Property;
            case CONSTANT -> SymbolKind.Constant;
            case MODULE -> SymbolKind.Module;
            case PARAMETER, REST -> SymbolKind.Variable;
            case VARIABLE, LOOP_VARIABLE, CATCH_VARIABLE, RESOURCE -> SymbolKind.Variable;
        };
    }

    private static Map<TokenStyle, Integer> typeIndex() {
        Map<TokenStyle, Integer> index = new EnumMap<>(TokenStyle.class);
        index.put(TokenStyle.KEYWORD, TOKEN_TYPES.indexOf("keyword"));
        index.put(TokenStyle.COMMENT, TOKEN_TYPES.indexOf("comment"));
        index.put(TokenStyle.STRING, TOKEN_TYPES.indexOf("string"));
        index.put(TokenStyle.NUMBER, TOKEN_TYPES.indexOf("number"));
        index.put(TokenStyle.OPERATOR, TOKEN_TYPES.indexOf("operator"));
        index.put(TokenStyle.VARIABLE, TOKEN_TYPES.indexOf("variable"));
        index.put(TokenStyle.CONSTANT, TOKEN_TYPES.indexOf("variable"));
        index.put(TokenStyle.PARAMETER, TOKEN_TYPES.indexOf("parameter"));
        index.put(TokenStyle.FUNCTION, TOKEN_TYPES.indexOf("function"));
        index.put(TokenStyle.METHOD, TOKEN_TYPES.indexOf("method"));
        index.put(TokenStyle.PROPERTY, TOKEN_TYPES.indexOf("property"));
        index.put(TokenStyle.CLASS, TOKEN_TYPES.indexOf("class"));
        index.put(TokenStyle.TRAIT, TOKEN_TYPES.indexOf("interface"));
        index.put(TokenStyle.MODULE, TOKEN_TYPES.indexOf("namespace"));
        return Map.copyOf(index);
    }
}
