package ru.wds.wdl.tools.analysis;

import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.lexer.LexerMode;
import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.lexer.TokenType;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Комментарии файла и их привязка к объявлениям — источник подсказки по наведению.
 * <p>
 * Берутся из потока {@link LexerMode#LOSSLESS}: в обычном режиме комментариев нет
 * вовсе, и это правильно — разбору они не нужны. Второй лексер ради этого не заводится.
 * <p>
 * <b>Правило привязки одно: комментарий принадлежит объявлению, если стоит прямо
 * над ним.</b> Прямо — значит между ними только пробелы и не больше одного перевода
 * строки: пустая строка разрывает связь. Иначе случайный комментарий из середины файла
 * стал бы документацией того, что написано следующим, а это хуже, чем её отсутствие.
 */
public final class Comments {

    private final Source source;
    private final List<Token> tokens;

    private Comments(Source source, List<Token> tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    /** Лексирует текст в режиме с тривией и запоминает поток. */
    public static Comments of(Source source) {
        Objects.requireNonNull(source, "source");
        // Диагностика здесь своя и выбрасывается: об ошибках уже сказано на разборе,
        // второй раз их показывать нельзя.
        List<Token> tokens = Lexer.tokenize(source, new Diagnostics(source), LexerMode.LOSSLESS);
        return new Comments(source, tokens);
    }

    /** Интервалы всех комментариев файла в порядке появления. */
    public List<Span> all() {
        List<Span> found = new ArrayList<>();
        for (Token token : tokens) {
            if (isComment(token)) {
                found.add(token.span());
            }
        }
        return List.copyOf(found);
    }

    /**
     * Документация объявления, начинающегося в этом месте, или {@code null}.
     * <p>
     * Несколько строк подряд склеиваются в одну справку — так их и писали.
     */
    public String before(Span declaration) {
        if (declaration == null || declaration.isNone()) {
            return null;
        }
        List<Token> found = new ArrayList<>();
        for (int i = indexAt(declaration.start()) - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.type() == TokenType.WHITESPACE) {
                if (newlines(token) > 1) {
                    break;      // пустая строка разрывает связь
                }
                continue;
            }
            if (!isComment(token) || !startsItsLine(token)) {
                // Комментарий в конце чужой строки ('price = 120 // цена') принадлежит
                // тому, что слева от него, а не тому, что написано следующим.
                break;
            }
            found.add(0, token);
        }
        return found.isEmpty() ? null : render(found);
    }

    /**
     * Дописывает документацию символам всего дерева областей и возвращает их одним
     * списком. Символы заменяются на месте — см. {@link LexicalScope#replaceSymbols}.
     */
    List<Symbol> documented(LexicalScope root) {
        root.replaceSymbols(symbol -> {
            String documentation = before(symbol.span());
            return documentation == null ? symbol : symbol.withDocumentation(documentation);
        });
        return root.collectSymbols();
    }

    /** Номер первого токена, начинающегося не раньше смещения. */
    private int indexAt(int offset) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).span().start() >= offset) {
                return i;
            }
        }
        return tokens.size();
    }

    /** Стоит ли комментарий в начале своей строки: слева от него только пробелы. */
    private boolean startsItsLine(Token token) {
        int line = source.positionOf(token.span().start()).line();
        int lineStart = source.offsetOf(line, 1);
        return source.text().substring(lineStart, token.span().start()).isBlank();
    }

    private static boolean isComment(Token token) {
        return token.type() == TokenType.LINE_COMMENT || token.type() == TokenType.BLOCK_COMMENT;
    }

    private int newlines(Token token) {
        String text = textOf(token);
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private String textOf(Token token) {
        return source.text().substring(token.span().start(), token.span().end());
    }

    /** Снимает маркеры комментария и лишние отступы: в подсказке нужен текст, а не запись. */
    private String render(List<Token> comments) {
        List<String> lines = new ArrayList<>();
        for (Token token : comments) {
            String text = textOf(token);
            if (token.type() == TokenType.LINE_COMMENT) {
                lines.add(strip(text.substring(2)));
                continue;
            }
            String inner = text.substring(2, Math.max(2, text.length() - 2));
            for (String line : inner.split("\n", -1)) {
                lines.add(strip(line.stripLeading().startsWith("*")
                        ? line.stripLeading().substring(1) : line));
            }
        }
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }

    private static String strip(String line) {
        return line.strip();
    }
}
