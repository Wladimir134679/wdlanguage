package ru.wds.wdl.tools.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.source.Source;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Дерево областей и состав имён: то, что заводит выполнение, и ничего сверх того. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ScopeTreeTest {

    private static FileAnalysis analyze(String code) {
        return FileAnalysis.of(Source.ofString(code));
    }

    /** Имена области, накрывающей это место в тексте. */
    private static List<String> namesAt(FileAnalysis analysis, String marker) {
        LexicalScope scope = analysis.scopeAt(analysis.source().text().indexOf(marker));
        return scope.symbols().stream().map(Symbol::name).toList();
    }

    /** Имена, видимые в этом месте, — включая внешние области. */
    private static List<String> visibleAt(FileAnalysis analysis, String marker) {
        return analysis.visibleAt(analysis.source().text().indexOf(marker))
                .stream().map(Symbol::name).toList();
    }

    private static Symbol symbol(FileAnalysis analysis, String name) {
        return analysis.symbols().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("нет символа '" + name + "'"));
    }

    @Test
    @DisplayName("объявления верхнего уровня попадают в область файла")
    void topLevel() {
        FileAnalysis analysis = analyze("""
                import lib.math as math
                const LIMIT = 10
                price = 120
                def total(count) => price * count
                class Rect(width) { }
                trait Printable { }
                """);

        assertEquals(List.of("math", "LIMIT", "price", "total", "Rect", "Printable"),
                analysis.scopes().symbols().stream().map(Symbol::name).toList());
        assertEquals(SymbolKind.MODULE, symbol(analysis, "math").kind());
        assertEquals(SymbolKind.CONSTANT, symbol(analysis, "LIMIT").kind());
        assertEquals(SymbolKind.VARIABLE, symbol(analysis, "price").kind());
        assertEquals(SymbolKind.FUNCTION, symbol(analysis, "total").kind());
        assertEquals(SymbolKind.CLASS, symbol(analysis, "Rect").kind());
        assertEquals(SymbolKind.TRAIT, symbol(analysis, "Printable").kind());
    }

    @Test
    @DisplayName("параметры живут в теле функции, а не снаружи")
    void functionBody() {
        FileAnalysis analysis = analyze("""
                def send(message, retries = 3, *rest, **named) {
                    attempt = 0
                }
                """);

        assertEquals(List.of("send"), analysis.scopes().symbols().stream().map(Symbol::name).toList());
        assertEquals(List.of("message", "retries", "rest", "named"), namesAt(analysis, "retries = 3"));
        assertEquals(List.of("attempt"), namesAt(analysis, "attempt = 0"));
        assertEquals(SymbolKind.PARAMETER, symbol(analysis, "message").kind());
        assertEquals(SymbolKind.REST, symbol(analysis, "rest").kind());
    }

    @Test
    @DisplayName("блок, циклы, use и catch заводят свои области")
    void nestedScopes() {
        FileAnalysis analysis = analyze("""
                items = [1, 2]
                for (i = 0; i < 2; i = i + 1) {
                    doubled = i * 2
                }
                for (key, item in items) {
                    println(key, item)
                }
                use (file = open("data.txt")) {
                    println(file)
                }
                try {
                    println(items)
                } catch (e) {
                    println(e)
                }
                """);

        assertEquals(List.of("i"), namesAt(analysis, "i < 2"));
        assertEquals(List.of("doubled"), namesAt(analysis, "doubled = i"));
        // Тело цикла — блок, и своих имён у него нет: имена прохода лежат областью выше.
        assertTrue(visibleAt(analysis, "println(key").containsAll(List.of("key", "item")));
        assertTrue(visibleAt(analysis, "println(file)").contains("file"));
        assertTrue(visibleAt(analysis, "println(e)").contains("e"));
        assertTrue(visibleAt(analysis, "items = [1").isEmpty(), "до объявления не видно ничего");

        assertEquals(SymbolKind.LOOP_VARIABLE, symbol(analysis, "item").kind());
        assertEquals(SymbolKind.RESOURCE, symbol(analysis, "file").kind());
        assertEquals(SymbolKind.CATCH_VARIABLE, symbol(analysis, "e").kind());
    }

    @Test
    @DisplayName("тело класса держит поля, методы, свойства и фабрики")
    void classBody() {
        FileAnalysis analysis = analyze("""
                class Rect(width, height) {
                    def Rect() { this.area = 0 }
                    def grow(factor) => width * factor
                    def Rect.square(side) => new Rect(side, side)
                    property title => "прямоугольник"
                    property scale = 1 {
                        def get() => field
                        def set(value) { field = value }
                    }
                }
                """);

        // Точка внутри заголовка класса: тело метода — уже своя область. 'area' в списке
        // потому, что 'this.area = 0' в конструкторе — это объявление поля класса.
        assertEquals(List.of("width", "height", "Rect", "area", "grow", "square", "title", "scale"),
                namesAt(analysis, "width, height"));
        assertEquals(SymbolKind.METHOD, symbol(analysis, "grow").kind());
        assertEquals(SymbolKind.FACTORY, symbol(analysis, "square").kind());
        assertEquals(SymbolKind.PROPERTY, symbol(analysis, "title").kind());
    }

    @Test
    @DisplayName("'field' существует только в аксессорах свойства со скрытым полем")
    void backingField() {
        FileAnalysis analysis = analyze("""
                class Counter() {
                    property scale = 1 {
                        def get() => field
                        def set(value) { field = value }
                    }
                    property plain => 2
                }
                """);

        assertTrue(namesAt(analysis, "=> field").contains("field"), "у свойства с полем field есть");
        assertEquals(ScopeKind.ACCESSOR,
                analysis.scopeAt(analysis.source().text().indexOf("=> field")).kind());
        assertTrue(analysis.visibleAt(analysis.source().text().indexOf("=> 2")).stream()
                .noneMatch(symbol -> symbol.name().equals("field")),
                "у свойства без поля field взяться неоткуда");
    }

    @Test
    @DisplayName("трейт и расширение заводят свои тела")
    void traitAndExtension() {
        FileAnalysis analysis = analyze("""
                trait Printable {
                    def print()
                    def label() => "печатное"
                }
                extend Array {
                    def second() => this[1]
                }
                """);

        assertEquals(List.of("print", "label"), namesAt(analysis, "trait Printable"));
        assertEquals(SymbolKind.REQUIREMENT, symbol(analysis, "print").kind());
        assertEquals(ScopeKind.EXTENSION_BODY,
                analysis.scopeAt(analysis.source().text().indexOf("extend Array")).kind());
        assertEquals(SymbolKind.METHOD, symbol(analysis, "second").kind());
    }

    @Test
    @DisplayName("анализ недописанного текста не падает и даёт дерево")
    void brokenCode() {
        for (String code : List.of("", "x = obj.", "def", "class Point(", "trait T {",
                "if (x) }", "first = )\nsecond = 2", "// только комментарий")) {
            FileAnalysis analysis = FileAnalysis.of(Source.ofString(code));
            assertNotNull(analysis.scopes(), () -> "нет дерева областей для: " + code);
            assertNotNull(analysis.visibleAt(Math.max(0, code.length() - 1)));
        }
    }
}
