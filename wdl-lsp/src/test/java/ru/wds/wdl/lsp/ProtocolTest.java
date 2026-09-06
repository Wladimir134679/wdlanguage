package ru.wds.wdl.lsp;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.diagnostic.DiagnosticCode;
import ru.wds.wdl.diagnostic.Severity;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.tools.service.HighlightToken;
import ru.wds.wdl.tools.service.TokenStyle;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перевод в понятия протокола: вся арифметика сервера собрана здесь, и ошибиться
 * в ней — значит подчеркнуть не то место.
 */
class ProtocolTest {

    private static final Source SOURCE = new Source("t.wdl", """
            price = 120
            имя = "город"
            def total(count) => price * count
            """);

    @Test
    @DisplayName("Смещение и позиция переводятся друг в друга на всём тексте")
    void positionsAreReversible() {
        for (int offset = 0; offset <= SOURCE.length(); offset++) {
            Position position = Protocol.position(SOURCE, offset);
            assertEquals(offset, Protocol.offset(SOURCE, position),
                    "смещение " + offset + " → " + position.getLine() + ":"
                            + position.getCharacter());
        }
    }

    @Test
    @DisplayName("Нумерация протокола идёт с нуля, наша — с единицы")
    void lspCountsFromZero() {
        Position start = Protocol.position(SOURCE, 0);
        assertEquals(0, start.getLine());
        assertEquals(0, start.getCharacter());

        // Кириллица — обычные единицы UTF-16, пересчёта не требует.
        int offset = SOURCE.text().indexOf("\"город\"");
        Position quote = Protocol.position(SOURCE, offset);
        assertEquals(1, quote.getLine());
        assertEquals("имя = ".length(), quote.getCharacter());
    }

    @Test
    @DisplayName("Позиция за концом строки и за концом текста прижимается к тексту")
    void positionsAreClamped() {
        assertEquals(SOURCE.text().indexOf('\n'), Protocol.offset(SOURCE, new Position(0, 500)),
                "курсор за концом первой строки");
        assertEquals(SOURCE.length(), Protocol.offset(SOURCE, new Position(500, 0)),
                "строки за концом текста нет вовсе");
        assertEquals(0, Protocol.offset(SOURCE, new Position(-1, -1)));
    }

    @Test
    @DisplayName("Отсутствующий интервал не ломает диапазон")
    void noneSpanBecomesEmptyRange() {
        Range range = Protocol.range(SOURCE, Span.NONE);
        assertEquals(new Position(0, 0), range.getStart());
        assertEquals(range.getStart(), range.getEnd());
    }

    @Test
    @DisplayName("Диагностика переводится с уровнем, источником и кодом")
    void diagnosticKeepsItsCode() {
        Span span = new Span(0, 5);
        org.eclipse.lsp4j.Diagnostic error = Protocol.diagnostic(SOURCE,
                new Diagnostic(Severity.ERROR, DiagnosticCode.EXPECTED_TOKEN, "ожидалось ';'", span));

        assertEquals(DiagnosticSeverity.Error, error.getSeverity());
        assertEquals("wdl", error.getSource());
        assertEquals("EXPECTED_TOKEN", error.getCode().getLeft());
        assertEquals(Protocol.range(SOURCE, span), error.getRange());

        org.eclipse.lsp4j.Diagnostic plain = Protocol.diagnostic(SOURCE,
                new Diagnostic(Severity.WARNING, "подозрительно", span));
        assertEquals(DiagnosticSeverity.Warning, plain.getSeverity());
        assertNull(plain.getCode(), "кода нет — и поля быть не должно");
    }

    @Test
    @DisplayName("Числа подсветки — разницы с предыдущим куском, а не позиции")
    void semanticTokensAreDeltas() {
        SemanticTokens tokens = Protocol.semanticTokens(SOURCE, List.of(
                new HighlightToken(new Span(0, 5), TokenStyle.VARIABLE, true),
                new HighlightToken(new Span(8, 11), TokenStyle.NUMBER),
                new HighlightToken(span("def"), TokenStyle.KEYWORD)));

        assertEquals(15, tokens.getData().size(), "по пятёрке на кусок");
        // строка, столбец, длина, вид, признаки
        assertEquals(List.of(0, 0, 5, index("variable"), 1), tokens.getData().subList(0, 5),
                "первый кусок считается от начала документа и помечен объявлением");
        assertEquals(List.of(0, 8, 3, index("number"), 0), tokens.getData().subList(5, 10),
                "тот же ряд — разница в столбцах");
        assertEquals(List.of(2, 0, 3, index("keyword"), 0), tokens.getData().subList(10, 15),
                "новый ряд — столбец считается заново");
    }

    @Test
    @DisplayName("Кусок через перевод строки режется по строкам")
    void multilineTokenIsSplit() {
        Source source = new Source("t.wdl", "a = 1\n/* два\nслова */\nb = 2\n");
        Span comment = new Span(source.text().indexOf("/*"), source.text().indexOf("*/") + 2);

        SemanticTokens tokens = Protocol.semanticTokens(source,
                List.of(new HighlightToken(comment, TokenStyle.COMMENT)));

        assertEquals(10, tokens.getData().size(), "комментарий занимает две строки");
        assertEquals(List.of(1, 0, "/* два".length(), index("comment"), 0),
                tokens.getData().subList(0, 5));
        assertEquals(List.of(1, 0, "слова */".length(), index("comment"), 0),
                tokens.getData().subList(5, 10));
    }

    @Test
    @DisplayName("Константа помечается признаком readonly")
    void constantIsReadonly() {
        SemanticTokens tokens = Protocol.semanticTokens(SOURCE,
                List.of(new HighlightToken(new Span(0, 5), TokenStyle.CONSTANT, true)));

        assertEquals(index("variable"), tokens.getData().get(3), "своего вида у константы нет");
        assertEquals(3, tokens.getData().get(4), "объявление и readonly сразу");
    }

    @Test
    @DisplayName("Мусор не красится: такого вида в протоколе нет")
    void badCharacterIsNotPainted() {
        SemanticTokens tokens = Protocol.semanticTokens(SOURCE,
                List.of(new HighlightToken(new Span(0, 1), TokenStyle.BAD)));

        assertTrue(tokens.getData().isEmpty());
    }

    @Test
    @DisplayName("Все виды подсветки, кроме мусора, названы именами из спецификации")
    void everyStyleIsMapped() {
        for (TokenStyle style : TokenStyle.values()) {
            SemanticTokens tokens = Protocol.semanticTokens(SOURCE,
                    List.of(new HighlightToken(new Span(0, 1), style)));
            if (style == TokenStyle.BAD) {
                continue;
            }
            assertEquals(5, tokens.getData().size(), style.name());
            assertTrue(tokens.getData().get(3) >= 0
                    && tokens.getData().get(3) < Protocol.TOKEN_TYPES.size(), style.name());
        }
    }

    private static int index(String type) {
        return Protocol.TOKEN_TYPES.indexOf(type);
    }

    private static Span span(String fragment) {
        int start = SOURCE.text().indexOf(fragment);
        return new Span(start, start + fragment.length());
    }
}
