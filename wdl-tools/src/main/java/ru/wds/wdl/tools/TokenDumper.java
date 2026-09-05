package ru.wds.wdl.tools;

import ru.wds.wdl.lexer.Token;
import ru.wds.wdl.lexer.TokenType;
import ru.wds.wdl.source.Source;

import java.util.List;

/**
 * Печать потока токенов в читаемую таблицу.
 * <p>
 * Пока нет ни парсера, ни интерпретатора, это единственный способ посмотреть, что
 * лексер на самом деле увидел в скрипте, — и главный инструмент отладки грамматики.
 */
public final class TokenDumper {

    /** Сколько символов тривии показывать в таблице. */
    private static final int TRIVIA_LIMIT = 30;

    private TokenDumper() {
    }

    /** Таблица «позиция — вид токена — значение», по строке на токен. */
    public static String dump(Source source, List<Token> tokens) {
        int typeWidth = tokens.stream()
                .map(token -> token.type().name().length())
                .max(Integer::compare)
                .orElse(4);

        StringBuilder sb = new StringBuilder(tokens.size() * 40);
        for (Token token : tokens) {
            String place = token.span().isNone() ? "-" : source.positionOf(token.span().start()).toString();
            String value = displayValue(source, token);
            sb.append(String.format("%8s  ", place));
            if (value.isEmpty()) {
                sb.append(token.type().name());
            } else {
                sb.append(String.format("%-" + typeWidth + "s  %s", token.type().name(), value));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Для строк показываем экранированный вид: иначе перевод строки внутри литерала
     * разъедет всю таблицу, а именно на таких значениях и ловятся ошибки лексера.
     * <p>
     * Имя в обратных кавычках печатается с ними: текст токена кавычек не содержит,
     * и без них {@code --tokens} на объявлении оператора показывал бы обычное имя —
     * то есть врал бы ровно про то, ради чего дамп и смотрят.
     */
    private static String displayValue(Source source, Token token) {
        if (token.type() == TokenType.STRING) {
            return '"' + escape(token.text()) + '"';
        }
        // У тривии текста нет по построению — она хранит только интервал. Показываем
        // сам исходник: без этого поток с '--trivia' состоял бы из безымянных строк.
        if (token.type().isTrivia()) {
            return escape(shorten(source.text().substring(token.span().start(), token.span().end())));
        }
        return token.quoted() ? '`' + token.text() + '`' : token.text();
    }

    /** Длинный комментарий разъехал бы таблицу — а смотрят в неё ради видов и позиций. */
    private static String shorten(String value) {
        return value.length() <= TRIVIA_LIMIT ? value : value.substring(0, TRIVIA_LIMIT) + "…";
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
