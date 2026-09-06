package ru.wds.wdl.tools.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Переход к объявлению и поиск употреблений — в пределах одного файла. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ResolveTest {

    private String code;
    private FileAnalysis analysis;

    private FileAnalysis analyze(String source) {
        code = source;
        analysis = FileAnalysis.of(Source.ofString(source));
        return analysis;
    }

    /** Символ под курсором, поставленным на первое вхождение подстроки. */
    private Symbol at(String marker) {
        return analysis.resolve(code.indexOf(marker)).orElseThrow(
                () -> new AssertionError("под '" + marker + "' ничего не нашлось"));
    }

    private Symbol atLast(String marker) {
        return analysis.resolve(code.lastIndexOf(marker)).orElseThrow(
                () -> new AssertionError("под '" + marker + "' ничего не нашлось"));
    }

    private List<String> usageTexts(Symbol symbol) {
        return analysis.usages(symbol).stream().map(analysis::textOf).toList();
    }

    @Test
    @DisplayName("курсор на имени объявления даёт само объявление")
    void resolveDeclaration() {
        analyze("def total(price) => price * 2");

        assertEquals(SymbolKind.FUNCTION, at("total").kind());
        assertEquals(SymbolKind.PARAMETER, at("price").kind());
    }

    @Test
    @DisplayName("курсор на употреблении ведёт к объявлению")
    void resolveUsage() {
        analyze("""
                price = 120
                count = 2
                println(price * count)
                """);

        Symbol symbol = at("price * count");
        assertEquals(SymbolKind.VARIABLE, symbol.kind());
        assertEquals(new Span(0, 5), symbol.nameSpan(), "ведёт к первому присваиванию");
    }

    @Test
    @DisplayName("вызов функции ведёт к её объявлению, даже если оно ниже")
    void resolveCallBelow() {
        analyze("""
                def main() => helper(2)
                def helper(n) => n * 2
                """);

        Symbol symbol = at("helper(2)");
        assertEquals(SymbolKind.FUNCTION, symbol.kind());
        assertEquals(code.indexOf("helper(n)"), symbol.nameSpan().start());
    }

    @Test
    @DisplayName("одноимённые из разных областей не смешиваются")
    void shadowedNamesStayApart() {
        analyze("""
                value = "снаружи"
                def show(value) {
                    println(value)
                }
                println(value)
                """);

        Symbol parameter = at("value) {");
        Symbol outer = atLast("value");

        assertEquals(SymbolKind.PARAMETER, parameter.kind());
        assertEquals(SymbolKind.VARIABLE, outer.kind());

        assertEquals(List.of("value"), usageTexts(parameter));
        assertEquals(1, analysis.usages(parameter).size(), "внутри функции — одно употребление");
        assertEquals(code.indexOf("println(value)") + 8, analysis.usages(parameter).get(0).start());

        // У внешнего имени их два: сама запись 'value = ...' — тоже употребление,
        // и переименование обязано её задеть.
        assertEquals(List.of(0, code.lastIndexOf("println(value)") + 8),
                analysis.usages(outer).stream().map(span -> span.start()).toList());
    }

    @Test
    @DisplayName("употребления функции собираются по всему файлу")
    void usagesOfFunction() {
        analyze("""
                def double(n) => n * 2
                def quad(n) => double(double(n))
                println(double(3))
                """);

        Symbol symbol = at("double(n)");
        assertEquals(3, analysis.usages(symbol).size(), "два вызова внутри quad и один снаружи");
        assertTrue(usageTexts(symbol).stream().allMatch(text -> text.equals("double")));
    }

    @Test
    @DisplayName("документация над объявлением не мешает находить его употребления")
    void documentedDeclarationKeepsItsUsages() {
        // Символ в области и символ в общем списке файла обязаны быть одним объектом:
        // разрешение возвращает первый, а сравнивают его со вторым — и стоит им
        // разойтись хоть полем документации, как поиск употреблений замолкает.
        analyze("""
                // Складывает два числа.
                def sum(a, b) => a + b
                println(sum(2, 3))
                """);

        Symbol symbol = at("sum(a, b)");
        assertEquals("Складывает два числа.", symbol.documentation());
        assertEquals(1, analysis.usages(symbol).size(), "вызов обязан найтись");
    }

    @Test
    @DisplayName("класс и его поля адресуются по имени")
    void resolveClass() {
        analyze("""
                class Rect(width, height) {
                    def area() => width * height
                }
                square = new Rect(2, 2)
                """);

        assertEquals(SymbolKind.CLASS, at("Rect(width").kind());
        assertEquals(SymbolKind.CLASS, at("Rect(2, 2)").kind());
        assertEquals(SymbolKind.PARAMETER, at("width * height").kind());
        assertEquals(1, analysis.usages(at("Rect(width")).size(), "класс употреблён в 'new'");
    }

    @Test
    @DisplayName("имя без объявления в файле остаётся неразрешённым, и это не ошибка")
    void builtinsStayUnresolved() {
        analyze("println(\"привет\")");

        assertTrue(analysis.resolve(code.indexOf("println")).isEmpty(),
                "встроенные имена придут каталогом, а не отсюда");
        assertTrue(analysis.diagnostics().isEmpty(), "анализ своей диагностики не добавляет");
    }

    @Test
    @DisplayName("разрешение на недописанном тексте не падает")
    void brokenCode() {
        for (String source : List.of("x = obj.", "def f(", "class C(", "value =",
                "for (item in", "use (file =")) {
            FileAnalysis broken = FileAnalysis.of(Source.ofString(source));
            for (int offset = 0; offset <= source.length(); offset++) {
                broken.resolve(offset);
                broken.visibleAt(offset);
            }
        }
    }
}
