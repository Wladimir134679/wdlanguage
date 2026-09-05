package ru.wds.wdl.lexer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.Corpus;
import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Режим {@link LexerMode#LOSSLESS}: поток покрывает документ целиком, а значимые
 * токены остаются теми же, что и в режиме разбора.
 * <p>
 * Второе важнее первого: без него «второй режим» незаметно превратился бы во второй
 * лексер со своими правилами, и подсветка начала бы расходиться с выполнением.
 */
class LosslessLexerTest {

    private static List<Token> lex(String code, LexerMode mode) {
        Source source = Source.ofString(code);
        return Lexer.tokenize(source, new Diagnostics(source), mode);
    }

    @Test
    @DisplayName("поток с тривией покрывает документ встык, без дыр и пересечений")
    void coversDocument() {
        for (Path file : Corpus.all()) {
            assertCovers(Corpus.sourceOf(file));
        }
    }

    @Test
    @DisplayName("значимые токены в обоих режимах одни и те же")
    void sameSignificantTokens() {
        for (Path file : Corpus.all()) {
            Source source = Corpus.sourceOf(file);
            List<Token> runtime = Lexer.tokenize(source, new Diagnostics(source), LexerMode.RUNTIME);
            List<Token> lossless = Lexer.tokenize(source, new Diagnostics(source), LexerMode.LOSSLESS)
                    .stream()
                    .filter(token -> !token.type().isTrivia())
                    .toList();

            assertEquals(runtime, lossless, () -> "потоки разошлись на " + source.name());
        }
    }

    @Test
    @DisplayName("диагностика от режима не зависит")
    void sameDiagnostics() {
        for (Path file : Corpus.all()) {
            Source source = Corpus.sourceOf(file);
            Diagnostics runtime = new Diagnostics(source);
            Diagnostics lossless = new Diagnostics(source);
            Lexer.tokenize(source, runtime, LexerMode.RUNTIME);
            Lexer.tokenize(source, lossless, LexerMode.LOSSLESS);

            assertEquals(runtime.all().stream().map(Diagnostic::toString).toList(),
                    lossless.all().stream().map(Diagnostic::toString).toList(),
                    () -> "сообщения разошлись на " + source.name());
        }
    }

    @Test
    @DisplayName("комментарии приходят токенами, а текст берётся по интервалу")
    void comments() {
        String code = "count = 1 // счётчик\n/* и блок */ name = \"мир\"";
        List<Token> tokens = lex(code, LexerMode.LOSSLESS);

        Token line = tokens.stream().filter(t -> t.type() == TokenType.LINE_COMMENT).findFirst().orElseThrow();
        Token block = tokens.stream().filter(t -> t.type() == TokenType.BLOCK_COMMENT).findFirst().orElseThrow();

        assertEquals("// счётчик", slice(code, line.span()));
        assertEquals("/* и блок */", slice(code, block.span()));
        assertEquals("", line.text(), "текст тривии пуст: содержимое читается по span");
    }

    @Test
    @DisplayName("незакрытый блочный комментарий доходит до конца документа")
    void unclosedBlockComment() {
        String code = "count = 1\n/* хвост файла";
        List<Token> tokens = lex(code, LexerMode.LOSSLESS);

        Token comment = tokens.stream().filter(t -> t.type() == TokenType.BLOCK_COMMENT)
                .findFirst().orElseThrow();
        assertEquals(new Span(code.indexOf("/*"), code.length()), comment.span());
    }

    @Test
    @DisplayName("незакрытая строка остаётся токеном до конца строки")
    void unclosedString() {
        String code = "name = \"мир\ncount = 1";
        List<Token> tokens = lex(code, LexerMode.LOSSLESS);

        Token string = tokens.stream().filter(t -> t.type() == TokenType.STRING)
                .findFirst().orElseThrow();
        assertEquals(new Span(code.indexOf('"'), code.indexOf('\n')), string.span());
    }

    @Test
    @DisplayName("неизвестный символ виден токеном только в режиме с тривией")
    void badCharacter() {
        String code = "count = 1 # 2";

        assertTrue(lex(code, LexerMode.LOSSLESS).stream().anyMatch(t -> t.type().isBad()),
                "подсветке нужен токен: до диагностики она не дотягивается");
        assertFalse(lex(code, LexerMode.RUNTIME).stream().anyMatch(t -> t.type().isBad()),
                "парсер не должен получать второй повод сказать про ту же беду");
    }

    @Test
    @DisplayName("маркер кодировки в потоке с тривией виден, и покрытие начинается с нуля")
    void byteOrderMark() {
        String code = "﻿count = 1";
        List<Token> tokens = lex(code, LexerMode.LOSSLESS);

        assertEquals(new Span(0, 1), tokens.get(0).span());
        assertEquals(TokenType.WHITESPACE, tokens.get(0).type());
        assertCovers(Source.ofString(code));
    }

    @Test
    @DisplayName("тривия не забирает у следующего токена признак переноса строки")
    void triviaKeepsNewlineFlag() {
        String code = "count = 1 // счётчик\nname = 2";
        List<Token> tokens = lex(code, LexerMode.LOSSLESS);

        Token name = tokens.stream()
                .filter(token -> token.type() == TokenType.WORD && token.text().equals("name"))
                .findFirst().orElseThrow();
        assertTrue(name.afterNewline(), "перенос принадлежит значимому токену");
        assertTrue(tokens.stream().filter(token -> token.type().isTrivia())
                .noneMatch(Token::afterNewline), "тривия признака не несёт");
    }

    @Test
    @DisplayName("новые виды токенов не попали ни в ключевые слова, ни в операторы")
    void triviaIsNeitherKeywordNorOperator() {
        for (TokenType type : List.of(TokenType.WHITESPACE, TokenType.LINE_COMMENT,
                TokenType.BLOCK_COMMENT, TokenType.BAD_CHARACTER)) {
            assertTrue(type.isTrivia(), () -> type + " — тривия");
            assertFalse(type.isKeyword(), () -> type + " не ключевое слово");
            assertFalse(type.isOperator(), () -> type + " не оператор");
            assertEquals("", type.text(), () -> "у " + type + " нет фиксированной лексемы");
        }
    }

    /** Интервалы идут подряд от нуля до конца текста, последним — пустой EOF. */
    private static void assertCovers(Source source) {
        List<Token> tokens = Lexer.tokenize(source, new Diagnostics(source), LexerMode.LOSSLESS);
        int expected = 0;
        for (Token token : tokens) {
            assertEquals(expected, token.span().start(),
                    () -> "разрыв или пересечение перед " + token + " в " + source.name());
            expected = token.span().end();
        }
        Token last = tokens.get(tokens.size() - 1);
        assertEquals(TokenType.EOF, last.type(), () -> "последний токен " + source.name());
        assertEquals(source.length(), last.span().start(), () -> "EOF в конце " + source.name());
        assertEquals(source.length(), last.span().end(), () -> "EOF пуст в " + source.name());
    }

    private static String slice(String code, Span span) {
        return code.substring(span.start(), span.end());
    }
}
