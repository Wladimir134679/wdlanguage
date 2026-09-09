package ru.wds.wdl.tools.service;

import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.LexerMode;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.lexer.TokenType;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.analysis.FileAnalysis;
import ru.wds.wdl.tools.analysis.Reference;
import ru.wds.wdl.tools.analysis.Symbol;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.SymbolDescriptor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Подсветка: поток токенов плюс то, что о именах знает анализ.
 * <p>
 * Второго лексера здесь нет и не будет — {@link LexerMode#LOSSLESS} покрывает
 * документ встык, и этого достаточно на всё, кроме имён. Имя же само по себе цвета
 * не имеет: {@code total} — функция или переменная в зависимости от того, что о нём
 * написано выше, и отвечает на это {@link FileAnalysis}, а не написание.
 * <p>
 * <b>Незнакомое имя не красится вовсе.</b> Цвет наугад хуже отсутствия цвета: автор
 * поверит, что имя нашлось, а его нет.
 */
final class Highlights {

    /** Пунктуация: цвета не несёт, и в теме редактора для неё нет своего вида. */
    private static final Set<TokenType> PUNCTUATION = EnumSet.of(
            TokenType.LPAREN, TokenType.RPAREN, TokenType.LBRACE, TokenType.RBRACE,
            TokenType.LBRACKET, TokenType.RBRACKET, TokenType.COMMA, TokenType.SEMICOLON,
            TokenType.COLON, TokenType.DOT);

    private Highlights() {
    }

    static List<HighlightToken> of(FileAnalysis analysis, Catalog catalog) {
        Source source = analysis.source();
        // Диагностика лексера здесь уже собрана разбором: этот проход нужен ради тривии.
        List<Token> tokens = Lexer.tokenize(source, new Diagnostics(source), LexerMode.LOSSLESS);

        Map<Integer, Symbol> declarations = new HashMap<>();
        for (Symbol symbol : analysis.symbols()) {
            if (!symbol.nameSpan().isNone()) {
                declarations.putIfAbsent(symbol.nameSpan().start(), symbol);
            }
        }
        Map<Integer, Reference> references = new HashMap<>();
        for (Reference reference : analysis.references()) {
            references.putIfAbsent(reference.span().start(), reference);
        }

        List<HighlightToken> painted = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            if (token.span().length() == 0) {
                continue;       // EOF и пустые заглушки красить нечем
            }
            if (token.type() == TokenType.WORD) {
                Symbol declared = declarations.get(token.span().start());
                if (declared != null) {
                    painted.add(new HighlightToken(token.span(), styleOf(declared.kind()), true));
                    continue;
                }
                TokenStyle style = styleOfUse(analysis, catalog,
                        references.get(token.span().start()));
                if (style != null) {
                    painted.add(new HighlightToken(token.span(), style));
                }
                continue;
            }
            TokenStyle style = styleOf(token.type());
            if (style != null) {
                painted.add(new HighlightToken(token.span(), style));
            }
        }
        return List.copyOf(painted);
    }

    /** Употребление: сначала объявление в файле, затем каталог, иначе — никак. */
    private static TokenStyle styleOfUse(FileAnalysis analysis, Catalog catalog,
                                         Reference reference) {
        if (reference == null) {
            return null;
        }
        Symbol declaration = analysis.declarationOf(reference);
        if (declaration != null) {
            return styleOf(declaration.kind());
        }
        SymbolDescriptor known = catalog.root(reference.name());
        return known == null ? null : styleOf(known.kind());
    }

    private static TokenStyle styleOf(TokenType type) {
        // Куски строки с подстановкой красятся как строка; код внутри подстановки —
        // обычными токенами, поэтому имя в ней подсвечивается по своей роли.
        if (type == TokenType.STRING || type.isStringPiece()) {
            return TokenStyle.STRING;
        }
        if (type.isNumber()) {
            return TokenStyle.NUMBER;
        }
        if (type.isKeyword()) {
            return TokenStyle.KEYWORD;
        }
        if (type == TokenType.LINE_COMMENT || type == TokenType.BLOCK_COMMENT) {
            return TokenStyle.COMMENT;
        }
        if (type == TokenType.BAD_CHARACTER) {
            return TokenStyle.BAD;
        }
        if (type.isOperator() && !PUNCTUATION.contains(type)) {
            return TokenStyle.OPERATOR;
        }
        return null;
    }

    private static TokenStyle styleOf(SymbolKind kind) {
        return switch (kind) {
            case CONSTANT -> TokenStyle.CONSTANT;
            case FUNCTION -> TokenStyle.FUNCTION;
            case PARAMETER, REST -> TokenStyle.PARAMETER;
            case CLASS -> TokenStyle.CLASS;
            case TRAIT -> TokenStyle.TRAIT;
            case METHOD, FACTORY, REQUIREMENT -> TokenStyle.METHOD;
            case PROPERTY, FIELD -> TokenStyle.PROPERTY;
            case MODULE -> TokenStyle.MODULE;
            case VARIABLE, LOOP_VARIABLE, CATCH_VARIABLE, RESOURCE -> TokenStyle.VARIABLE;
        };
    }
}
