package ru.wds.wdl.tools.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.source.Span;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.Catalogs;
import ru.wds.wdl.tools.catalog.Origin;
import ru.wds.wdl.tools.catalog.Suggestion;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Языковой сервис отвечает про открытый файл — и не отвечает отказом ни на что.
 */
class LanguageServiceTest {

    private static final DocumentId ID = DocumentId.of("file:///tmp/order.wdl");

    private static final String TEXT = """
            // цена одной штуки
            price = 120

            // сумма заказа
            def total(count) {
                return price * count;
            }

            class Rect(width, height) {
                def area() => width * height
            }

            println(total(3))
            """;

    private static LanguageService opened() {
        LanguageService service = LanguageService.of(Catalogs.builtins());
        service.open(ID, 1, TEXT);
        return service;
    }

    private static int at(String fragment) {
        int index = TEXT.indexOf(fragment);
        if (index < 0) {
            throw new AssertionError("нет фрагмента '" + fragment + "' в тексте");
        }
        return index;
    }

    @Test
    @DisplayName("Открытый документ разобран сразу, вместе с диагностикой")
    void openParsesAtOnce() {
        LanguageService service = opened();

        Document document = service.document(ID);
        assertNotNull(document);
        assertEquals(1, document.version());
        assertEquals(TEXT, document.text());
        assertEquals("order.wdl", document.source().name(), "имя источника — для строк диагностики");
        assertTrue(service.diagnostics(ID).isEmpty(), "текст примера разбирается без ошибок");
    }

    @Test
    @DisplayName("Ошибка разбора приходит с интервалом и не мешает отвечать дальше")
    void brokenTextStillAnswers() {
        LanguageService service = LanguageService.of(Catalogs.builtins());
        service.open(ID, 1, "def total(count) {\n    return price *\n}\n");

        List<Diagnostic> diagnostics = service.diagnostics(ID);
        assertFalse(diagnostics.isEmpty(), "недописанное выражение обязано быть замечено");
        assertFalse(diagnostics.get(0).span().isNone(), "интервал ошибки — то, что подчёркивают");
        assertFalse(service.outline(ID).isEmpty(), "структура файла собирается и по битому дереву");
    }

    @Test
    @DisplayName("Устаревшая правка не заменяет свежую")
    void staleChangeIsIgnored() {
        LanguageService service = opened();

        Document third = service.change(ID, 3, "a = 3\n");
        Document late = service.change(ID, 2, "a = 2\n");

        assertSame(third, late, "правка версии 2 пришла после третьей и уже никому не нужна");
        assertEquals("a = 3\n", service.document(ID).text());
    }

    @Test
    @DisplayName("Дополнение складывает имена файла и каталога")
    void completionMergesFileAndCatalog() {
        LanguageService service = opened();

        List<String> names = service.complete(ID, at("println(total(3))")).stream()
                .map(Suggestion::name)
                .toList();

        assertTrue(names.contains("total"), "своя функция видна: " + names);
        assertTrue(names.contains("Rect"));
        assertTrue(names.contains("println"), "встроенное приходит из каталога");
        assertEquals(names.indexOf("total") < names.indexOf("println"), true,
                "имена файла идут первыми");
    }

    @Test
    @DisplayName("Наведение показывает сигнатуру и комментарий перед объявлением")
    void hoverShowsSignatureAndComment() {
        LanguageService service = opened();

        Hover hover = service.hover(ID, at("total(3)"));

        assertNotNull(hover);
        assertEquals("total(count)", hover.signature());
        assertEquals(SymbolKind.FUNCTION, hover.kind());
        assertEquals(Origin.FILE, hover.origin());
        assertEquals("сумма заказа", hover.documentation());
        assertEquals("total", TEXT.substring(hover.span().start(), hover.span().end()),
                "подсвечивается имя, а не всё выражение");
    }

    @Test
    @DisplayName("Наведение на встроенное имя приходит из каталога")
    void hoverFallsBackToCatalog() {
        LanguageService service = opened();

        Hover hover = service.hover(ID, at("println("));

        assertNotNull(hover);
        assertEquals(Origin.BUILTIN, hover.origin());
        assertEquals("println(…)", hover.signature());
    }

    @Test
    @DisplayName("Подсказка сигнатуры берётся у известного вызова без выполнения")
    void signatureHelpUsesKnownCallable() {
        LanguageService service = opened();

        CallSignature signature = service.signatureHelp(ID, at("total(3)") + "total(".length());

        assertNotNull(signature);
        assertEquals("total(count)", signature.label());
    }

    @Test
    @DisplayName("Переход ведёт к объявлению; у встроенного имени места в тексте нет")
    void definitionStaysInsideTheFile() {
        LanguageService service = opened();

        Location declaration = service.definition(ID, at("price * count"));
        assertNotNull(declaration);
        assertEquals(ID, declaration.document());
        assertEquals(at("price = 120"), declaration.span().start());

        assertNull(service.definition(ID, at("println(")),
                "println объявлен движком, а не файлом");
    }

    @Test
    @DisplayName("Употребления находятся и с объявлением, и без него")
    void referencesFollowTheDeclaration() {
        LanguageService service = opened();
        int use = at("price * count");

        List<Location> without = service.references(ID, use, false);
        List<Location> with = service.references(ID, use, true);

        // 'price = 120' — объявление и запись сразу; в ответе оно появляется один раз
        // и только тогда, когда объявление просили.
        assertEquals(List.of(new Span(use, use + "price".length())),
                without.stream().map(Location::span).toList());
        assertEquals(2, with.size(), "объявление добавляется первым: " + with);
        assertEquals(at("price = 120"), with.get(0).span().start());
    }

    @Test
    @DisplayName("Структура файла вложена: методы стоят внутри класса")
    void outlineIsNested() {
        LanguageService service = opened();

        List<Outline> outline = service.outline(ID);
        List<String> names = outline.stream().map(Outline::name).toList();
        assertEquals(List.of("price", "total", "Rect"), names,
                "переменная верхнего уровня — тоже состав файла");

        Outline rect = outline.get(2);
        assertEquals(SymbolKind.CLASS, rect.kind());
        assertEquals("class Rect(width, height)", rect.signature());
        assertEquals(List.of("area"), rect.children().stream().map(Outline::name).toList());
        assertEquals(SymbolKind.METHOD, rect.children().get(0).kind());

        Outline total = outline.get(1);
        assertTrue(total.children().isEmpty(), "параметры и локальные — не состав файла");
        assertEquals("total", TEXT.substring(total.nameSpan().start(), total.nameSpan().end()));
    }

    @Test
    @DisplayName("Подсветка красит ключевые слова, комментарии и имена по видам")
    void highlightKnowsWhatEachNameIs() {
        LanguageService service = opened();
        List<HighlightToken> painted = service.highlight(ID);

        assertEquals(TokenStyle.COMMENT, styleAt(painted, at("// цена одной штуки")));
        assertEquals(TokenStyle.KEYWORD, styleAt(painted, at("def total")));
        assertEquals(TokenStyle.NUMBER, styleAt(painted, at("120")));

        HighlightToken declaration = tokenAt(painted, at("total(count)"));
        assertEquals(TokenStyle.FUNCTION, declaration.style());
        assertTrue(declaration.declaration(), "здесь функция объявляется");

        assertEquals(TokenStyle.FUNCTION, styleAt(painted, at("total(3)")));
        assertFalse(tokenAt(painted, at("total(3)")).declaration(), "а здесь зовётся");
        assertEquals(TokenStyle.VARIABLE, styleAt(painted, at("price * count")));
        assertEquals(TokenStyle.PARAMETER, styleAt(painted, at("count)")));
        assertEquals(TokenStyle.CLASS, styleAt(painted, at("Rect(width")));
        assertEquals(TokenStyle.FUNCTION, styleAt(painted, at("println(")),
                "имя из каталога тоже знает свой вид");
    }

    @Test
    @DisplayName("Незнакомое имя не красится: цвет наугад хуже отсутствия цвета")
    void unknownNameStaysUnpainted() {
        LanguageService service = LanguageService.of(Catalogs.builtins());
        service.open(ID, 1, "unknownName(1)\n");

        assertNull(styleAt(service.highlight(ID), 0));
    }

    @Test
    @DisplayName("Скобки и пробелы цвета не несут")
    void punctuationIsNotPainted() {
        LanguageService service = LanguageService.of(Catalog.empty());
        service.open(ID, 1, "a = (1 + 2)\n");

        List<HighlightToken> painted = service.highlight(ID);
        assertNull(styleAt(painted, "a = ".length()), "открывающая скобка");
        assertEquals(TokenStyle.OPERATOR, styleAt(painted, "a = (1 ".length()), "плюс");
        assertEquals(TokenStyle.OPERATOR, styleAt(painted, "a ".length()), "присваивание");
    }

    @Test
    @DisplayName("Закрытый документ отвечает пустым, а не отказом")
    void closedDocumentAnswersEmpty() {
        LanguageService service = opened();
        assertNotNull(service.close(ID));

        assertNull(service.document(ID));
        assertFalse(service.isOpen(ID));
        assertTrue(service.diagnostics(ID).isEmpty());
        assertTrue(service.complete(ID, 0).isEmpty());
        assertTrue(service.references(ID, 0, true).isEmpty());
        assertTrue(service.outline(ID).isEmpty());
        assertTrue(service.highlight(ID).isEmpty());
        assertNull(service.hover(ID, 0));
        assertNull(service.definition(ID, 0));
        assertNull(service.lookup(ID));
        assertNull(service.close(ID), "второе закрытие — тоже не ошибка");
    }

    @Test
    @DisplayName("Открытые документы независимы")
    void documentsAreIndependent() {
        LanguageService service = opened();
        DocumentId other = DocumentId.of("file:///tmp/other.wdl");
        service.open(other, 1, "def hello() => 1\n");

        assertEquals(2, service.openDocuments().size());
        assertEquals(List.of("hello"), service.outline(other).stream().map(Outline::name).toList());
        assertEquals(3, service.outline(ID).size(), "чужая правка соседа не трогает");

        service.closeAll();
        assertTrue(service.openDocuments().isEmpty());
    }

    private static HighlightToken tokenAt(List<HighlightToken> painted, int offset) {
        return painted.stream()
                .filter(token -> token.span().start() == offset)
                .findFirst()
                .orElse(null);
    }

    private static TokenStyle styleAt(List<HighlightToken> painted, int offset) {
        HighlightToken token = tokenAt(painted, offset);
        return token == null ? null : token.style();
    }
}
