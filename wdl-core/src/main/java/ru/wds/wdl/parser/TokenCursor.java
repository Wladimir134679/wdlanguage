package ru.wds.wdl.parser;

import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.lexer.TokenType;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Курсор по потоку токенов: всё, что парсер делает с текстом, ещё не строя дерево.
 * <p>
 * Отделено от {@link Parser} потому, что зависимость здесь односторонняя: курсор знает
 * про токены и диагностику и ничего не знает ни про грамматику, ни про AST. Правила
 * языка сюда не попадают — кроме одного места, {@link #synchronize()}: восстановление
 * после ошибки обязано знать, за какие слова заходить нельзя, иначе оно съело бы
 * следующую конструкцию целиком.
 * <p>
 * Экземпляр одноразовый, как и сам парсер.
 */
final class TokenCursor {

    private final List<Token> tokens;
    private final Diagnostics diagnostics;
    private int index;

    TokenCursor(List<Token> tokens, Diagnostics diagnostics) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    Token peek() {
        return peek(0);
    }

    Token peek(int offset) {
        int at = index + offset;
        // Последний токен потока — всегда EOF, и «выйти за конец» значит остаться на нём.
        return at < tokens.size() ? tokens.get(at) : tokens.get(tokens.size() - 1);
    }

    boolean check(TokenType type) {
        return peek().type() == type;
    }

    Token advance() {
        Token token = peek();
        if (index < tokens.size() - 1) {
            index++;
        }
        return token;
    }

    boolean match(TokenType type) {
        if (!check(type)) {
            return false;
        }
        advance();
        return true;
    }

    /**
     * Требует токен и возвращает его. Если токена нет — сообщает об ошибке и отдаёт
     * текущий, не сдвигаясь: пропущенная скобка не должна съедать то, что за ней,
     * иначе одна ошибка порождает цепочку следующих.
     */
    Token expect(TokenType type, String what) {
        Token token = peek();
        if (token.type() == type) {
            advance();
            return token;
        }
        diagnostics.error(token.span(), "ожидалось " + what + ", найдено " + describe(token));
        return token;
    }

    /** Текущее место в потоке — только чтобы сравнить его с прежним в {@link #ensureProgress(int)}. */
    int position() {
        return index;
    }

    /**
     * Страховка от вечного цикла в разборе списков: если после неудачной итерации
     * позиция не сдвинулась, двигаем её принудительно. Без этого мусорный токен
     * внутри {@code [ ... ]} завесил бы парсер намертво.
     */
    void ensureProgress(int positionBefore) {
        if (index == positionBefore) {
            advance();
        }
    }

    /** Место уже разобранного токена — им заканчивается объявление класса или трейта. */
    Span lastSpan() {
        return tokens.get(Math.max(0, index - 1)).span();
    }

    /** Место всего потока целиком — им отмечается {@link ru.wds.wdl.ast.Program}. */
    Span wholeSpan() {
        return tokens.isEmpty()
                ? Span.NONE
                : tokens.get(0).span().to(tokens.get(tokens.size() - 1).span());
    }

    /** Точка с запятой — необязательный разделитель, и подряд их может быть сколько угодно. */
    void skipSeparators() {
        while (match(TokenType.SEMICOLON)) {
            // пропускаем
        }
    }

    /**
     * Паническое восстановление: пропустить испорченную инструкцию и продолжить
     * со следующей.
     * <p>
     * Границей служит точка с запятой или начало новой строки — здесь и пригождается
     * {@link Token#afterNewline()}. Перевод строки не влияет на грамматику и не даёт
     * токена, но человек всё же пишет инструкции по строкам, и восстанавливаться
     * разумнее по тому, как текст выглядит, а не по тому, как он разбирается.
     * <p>
     * Третья граница — токены, за которые заходить нельзя ни при каких переносах строк:
     * закрывающая фигурная скобка и слова, начинающие следующую конструкцию. Без этого
     * опечатка в теле цикла съедала бы {@code &#125;}, и одна ошибка разваливала бы
     * разбор всего оставшегося файла. {@code case} и {@code else} в этом списке
     * работают ещё и на {@code match}: ошибка в одной ветке не съедает остальные.
     */
    void synchronize() {
        while (!check(TokenType.EOF)) {
            if (check(TokenType.SEMICOLON)) {
                advance();
                return;
            }
            if (peek().afterNewline() || isStatementBoundary(peek().type())) {
                return;
            }
            advance();
        }
    }

    /** Токен, дальше которого паническое восстановление не идёт. */
    private static boolean isStatementBoundary(TokenType type) {
        return switch (type) {
            case RBRACE, IF, ELSE, WHILE, FOR, BREAK, CONTINUE, CONST, DEF, CLASS, TRAIT,
                 IMPORT, RETURN, THROW, TRY, CATCH, FINALLY, DEFER, USE, MATCH, CASE -> true;
            default -> false;
        };
    }

    void expectEnd() {
        if (check(TokenType.EOF)) {
            return;
        }
        diagnostics.error(peek().span(), "лишнее после выражения: " + describe(peek())
                + ". Пока скрипт — это одно выражение; инструкции появятся дальше");
    }

    /** Как назвать токен в сообщении об ошибке. */
    static String describe(Token token) {
        return switch (token.type()) {
            case EOF -> "конец файла";
            case WORD -> "имя '" + token.text() + "'";
            case STRING -> "строку";
            case INT, FLOAT, HEX, BIN -> "число " + token.text();
            default -> token.type().describe();
        };
    }
}
