package ru.wds.wdl.tools.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.Lookup;
import ru.wds.wdl.tools.catalog.Suggestion;
import ru.wds.wdl.tools.service.Document;
import ru.wds.wdl.tools.service.DocumentId;
import ru.wds.wdl.tools.service.LanguageService;
import ru.wds.wdl.tools.service.Location;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Пользовательские файлы индексируются так же безопасно, как один открытый документ. */
class WorkspaceIndexTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("Экспорт соседнего модуля виден в completion и ведёт к исходному URI")
    void importedModuleSuppliesCompletionAndDefinition() throws Exception {
        Path shapes = root.resolve("app/shapes.wdl");
        Files.createDirectories(shapes.getParent());
        Files.writeString(shapes, """
                class Circle(radius) {
                    def area() => radius * radius
                    def Circle.unit() => new Circle(1)
                }
                """);
        Path main = root.resolve("main.wdl");
        String text = """
                import app.shapes as shapes
                shapes.Circle.
                """;

        LanguageService service = LanguageService.withoutCatalog();
        service.configureWorkspace(List.of(root));
        DocumentId mainId = DocumentId.of(main.toUri().toString());
        service.open(mainId, 1, text);

        List<Suggestion> suggestions = service.complete(mainId, text.indexOf("Circle.") + 7);
        assertTrue(suggestions.stream().anyMatch(item -> item.name().equals("unit")), suggestions::toString);

        Location definition = service.definition(mainId, text.indexOf("Circle"));
        assertNotNull(definition);
        assertEquals(shapes.toUri().toString(), definition.document().uri());
        Source source = service.source(definition.document());
        assertEquals("Circle", source.text().substring(definition.span().start(), definition.span().end()));
    }

    @Test
    @DisplayName("Открытый несохранённый модуль заменяет disk snapshot в импортёре")
    void openOverlayWinsOverDisk() throws Exception {
        Path shapes = root.resolve("app/shapes.wdl");
        Files.createDirectories(shapes.getParent());
        Files.writeString(shapes, "class Circle() { def Circle.old() => new Circle() }\n");
        Path main = root.resolve("main.wdl");
        String text = "import app.shapes as shapes\nshapes.Circle.\n";

        LanguageService service = LanguageService.withoutCatalog();
        service.configureWorkspace(List.of(root));
        DocumentId mainId = DocumentId.of(main.toUri().toString());
        service.open(mainId, 1, text);
        DocumentId shapesId = DocumentId.of(shapes.toUri().toString());
        service.open(shapesId, 7, "class Circle() { def Circle.fresh() => new Circle() }\n");

        List<String> names = service.complete(mainId, text.indexOf("Circle.") + 7).stream()
                .map(Suggestion::name).toList();
        assertEquals(List.of("fresh"), names);
    }

    @Test
    @DisplayName("Отсутствующий импорт и цикл дают диагностику без рекурсии")
    void missingAndCyclicImportsAreDiagnostics() throws Exception {
        Path a = root.resolve("a.wdl");
        Path b = root.resolve("b.wdl");
        Files.writeString(a, "import b as b\nimport missing as missing\nclass A() {}\n");
        Files.writeString(b, "import a as a\nclass B() {}\n");

        WorkspaceIndex index = new WorkspaceIndex();
        index.configure(List.of(root));

        DocumentId aId = DocumentId.of(a.toUri().toString());
        List<String> messages = index.diagnostics(aId, Catalog.empty()).stream()
                .map(diagnostic -> diagnostic.message()).toList();
        assertTrue(messages.stream().anyMatch(message -> message.contains("циклический")), messages::toString);
        assertTrue(messages.stream().anyMatch(message -> message.contains("не найден")), messages::toString);
    }

    @Test
    @DisplayName("Workspace symbol возвращает только экспорт и фильтрует по имени")
    void workspaceSymbolsAreExportedAndSearchable() throws Exception {
        Path file = root.resolve("app/shapes.wdl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                class Circle() {}
                def circumference(radius) => radius
                def local() { hidden = 1 }
                """);
        WorkspaceIndex index = new WorkspaceIndex();
        index.configure(List.of(root));

        assertEquals(List.of("Circle", "circumference"), index.symbols("circ").stream()
                .map(ru.wds.wdl.tools.service.WorkspaceSymbol::name).toList());
        assertTrue(index.symbols("hidden").isEmpty(), "локальная переменная не экспортируется");
    }
}
