package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.AccessStyle;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.analysis.FileAnalysis;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.Catalogs;
import ru.wds.wdl.tools.catalog.ModuleDescriptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог сверяется с настоящими скриптами: всё, что примеры берут у модуля,
 * каталог обязан знать.
 * <p>
 * Проверка сформулирована от потери, а не от полноты. «Разрешилось всё» на примерах
 * недостижимо: имена приходят и от хозяина движка, и от развёрнутого импорта. А вот
 * {@code io.read}, написанное в работающем примере и потерянное каталогом, — это
 * ровно то место, где подсказка редактора замолчит.
 */
class CatalogExamplesTest {

    private static final Catalog CATALOG = Catalogs.ofModules(Sys.registry());

    @Test
    @DisplayName("всё, что примеры берут у модулей sys.*, есть в каталоге")
    void examplesUseOnlyKnownModuleNames() {
        List<String> complaints = new ArrayList<>();
        int checked = 0;

        for (Path file : exampleFiles()) {
            FileAnalysis analysis = FileAnalysis.of(sourceOf(file));
            if (analysis.diagnostics().hasErrors()) {
                continue;   // файл с нарочными ошибками разбирать нечего
            }
            Map<String, String> aliases = moduleAliases(analysis);
            if (aliases.isEmpty()) {
                continue;
            }
            for (Node node : nodes(analysis)) {
                if (!(node instanceof AccessExpr access) || access.style() != AccessStyle.DOT) {
                    continue;
                }
                String field = access.fieldName();
                if (field == null || !(access.target() instanceof VariableExpr target)) {
                    continue;
                }
                String key = aliases.get(target.name());
                ModuleDescriptor module = key == null ? null : CATALOG.module(key);
                if (module == null || module.isEmpty()) {
                    continue;   // модуль этой машине недоступен — снимок пуст
                }
                checked++;
                if (module.get(field) == null) {
                    complaints.add(file.getFileName() + ": каталог не знает " + key + "." + field);
                }
            }
        }

        assertTrue(complaints.isEmpty(), () -> String.join("\n", complaints));
        assertFalse(checked == 0, "примеры обязаны хоть где-то обращаться к модулям");
    }

    /** Псевдоним → ключ модуля, но только для модулей, которые есть в реестре. */
    private static Map<String, String> moduleAliases(FileAnalysis analysis) {
        Map<String, String> aliases = new HashMap<>();
        for (Node node : nodes(analysis)) {
            if (node instanceof ImportStmt statement && statement.hasAlias()
                    && CATALOG.module(statement.path()) != null) {
                aliases.put(statement.alias(), statement.path());
            }
        }
        return aliases;
    }

    private static List<Node> nodes(FileAnalysis analysis) {
        List<Node> all = new ArrayList<>();
        Nodes.walk(analysis.program(), all::add);
        return all;
    }

    private static List<Path> exampleFiles() {
        Path directory = Path.of(System.getProperty("wdl.examples", "../examples"));
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".wdl"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("не обойти " + directory, e);
        }
    }

    private static Source sourceOf(Path file) {
        try {
            return new Source(file.getFileName().toString(),
                    Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("не прочитать " + file, e);
        }
    }
}
