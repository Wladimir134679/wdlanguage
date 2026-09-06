package ru.wds.wdl.tools.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.Catalogs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Главная проверка слоя: <b>один и тот же скрипт исполняется и анализируется</b>,
 * и то, что нашёл анализ, сверяется с тем, что реально завёл запуск.
 * <p>
 * Без такой сверки модель имён живёт своей жизнью: тесты на выдуманных фрагментах
 * проходят, а редактор прячет имя, которое в запуске есть, или предлагает то, чего
 * там нет. Здесь ошибка такого рода видна сразу — либо скрипт не выполнится, либо
 * останется имя, которому неоткуда взяться.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AnalysisAndRuntimeTest {

    /** Имена, которые заводит не файл: получатель, родитель и скрытое поле свойства. */
    private static final Set<String> SELF_NAMES = Set.of("this", "super", "field");

    private static final String FIXTURE = """
            // Заказ и его стоимость: файл, где встречается всё, что заводит имена.
            const RATE = 2

            prices = [10, 20, 30]
            total = 0

            def sum(values, start = 0) {
                result = start
                for (value in values) {
                    result = result + value
                }
                return result;
            }

            def even(n) => n == 0 ? true : odd(n - 1)
            def odd(n) => n == 0 ? false : even(n - 1)

            class Order(items, rate = RATE) {
                def cost() => sum(items) * rate
                property size => len(items)
            }

            trait Named {
                def label()
            }

            order = new Order(prices)
            total = order.cost()

            counter = 0
            for (i = 0; i < 3; i = i + 1) {
                counter = counter + i
            }

            doubled = def(n) => n * 2
            names = {first: "заказ", second: "итог"}
            first, second = *[1, 2]

            caught = ""
            try {
                throw new Exception("проверка");
            } catch (failure) {
                caught = failure.message
            }

            println(total, " ", counter, " ", doubled(RATE), " ", even(4), " ",
                    order.size, " ", caught, " ", first + second, " ", names.first)
            """;

    @Test
    @DisplayName("что нашёл анализ, то и заводит запуск: имена сходятся")
    void analysisMatchesRuntime() {
        Source source = Source.ofString(FIXTURE);
        FileAnalysis analysis = FileAnalysis.of(source);
        assertFalse(analysis.diagnostics().hasErrors(),
                () -> "фикстур обязан разбираться:\n" + analysis.diagnostics().renderAll());

        StringBuilder printed = new StringBuilder();
        Set<String> rootNames = run(source, printed::append);

        assertEquals("120 3 4 true 3 проверка 3 заказ", printed.toString().strip(),
                "фикстур обязан выполняться, иначе сверять нечего");

        List<String> unresolved = unresolved(analysis, rootNames);
        assertTrue(unresolved.isEmpty(),
                () -> "анализ потерял имена, которые в запуске есть: " + unresolved);

        // Второе полукольцо той же сверки: имя, не найденное анализом, обязано найтись
        // в каталоге. Раньше здесь стоял просто набор имён корня — он говорил, что имя
        // где-то есть, но не что редактору будет что о нём показать.
        Catalog language = Catalogs.builtins();
        List<String> unknown = rootNames.stream()
                .filter(name -> language.root(name) == null)
                .toList();
        assertTrue(unknown.isEmpty(),
                () -> "каталог языка не знает имён, которые кладёт в корень сам запуск: " + unknown);

        List<String> undescribed = unresolvedByCatalog(analysis, language);
        assertTrue(undescribed.isEmpty(),
                () -> "имена, которых нет ни в файле, ни в каталоге: " + undescribed);
    }

    @Test
    @DisplayName("на примерах языка анализ не теряет ни одного объявления файла")
    void allExamples() {
        List<String> complaints = new ArrayList<>();

        for (Path file : exampleFiles()) {
            Source source = sourceOf(file);
            FileAnalysis analysis = FileAnalysis.of(source);
            if (analysis.diagnostics().hasErrors()) {
                continue;   // файл с нарочными ошибками разбирать нечего
            }
            List<String> lost = lostDeclarations(analysis);
            if (!lost.isEmpty()) {
                complaints.add(file.getFileName() + ": " + lost);
            }
        }

        assertTrue(complaints.isEmpty(), () -> String.join("\n", complaints));
    }

    /**
     * Имена, объявленные в этом же файле, но не найденные разрешением.
     * <p>
     * Проверка именно такая, потому что «разрешилось всё» на примерах недостижимо
     * и не должно быть достижимо: {@code println} приходит встроенным, {@code sqrt}
     * и {@code File} кладёт в область хозяин движка, развёрнутый импорт приносит имена
     * другого файла, а член, доставшийся классу от трейта или родителя, ищется
     * по таблице членов, а не по областям. Всё это — следующие этапы. А вот имя,
     * которое <b>объявлено прямо здесь</b> и всё равно не нашлось, — потеря анализа.
     */
    private static List<String> lostDeclarations(FileAnalysis analysis) {
        Set<String> lost = new LinkedHashSet<>();
        for (Reference reference : analysis.references()) {
            if (!declaredAbove(analysis, reference)
                    || insideType(analysis, reference)
                    || analysis.declarationOf(reference) != null) {
                continue;
            }
            lost.add(reference.name());
        }
        return List.copyOf(lost);
    }

    /**
     * Есть ли в файле одноимённое объявление <b>выше</b> этого места.
     * <p>
     * Выше — потому что ниже оно и не должно быть видно: {@code types.wdl} сначала
     * пользуется встроенным дескриптором {@code Number}, а в конце заводит своё имя
     * с тем же написанием. Первое употребление ведёт наружу, и это правильный ответ.
     */
    private static boolean declaredAbove(FileAnalysis analysis, Reference reference) {
        return analysis.symbols().stream()
                .anyMatch(symbol -> symbol.name().equals(reference.name())
                        && symbol.nameSpan().start() < reference.span().start());
    }

    /** Внутри тела типа имя может быть членом — от родителя или трейта, из другого файла. */
    private static boolean insideType(FileAnalysis analysis, Reference reference) {
        return analysis.scopeAt(reference.span().start()).enclosingType() != null;
    }

    /**
     * Имена, которые не нашлись ни в файле, ни в каталоге языка.
     * <p>
     * Это тот же вопрос, что и у {@link #unresolved}, но заданный тому, кто отвечает
     * редактору: каталог обязан знать всё, чего нет в файле, — иначе подсказка молчит
     * там, где имя на самом деле есть.
     */
    private static List<String> unresolvedByCatalog(FileAnalysis analysis, Catalog catalog) {
        Set<String> found = new LinkedHashSet<>();
        for (Reference reference : analysis.references()) {
            if (SELF_NAMES.contains(reference.name())
                    || catalog.root(reference.name()) != null
                    || analysis.declarationOf(reference) != null) {
                continue;
            }
            found.add(reference.name());
        }
        return List.copyOf(found);
    }

    /** Имена, которые употреблены в файле, но не нашлись ни в нём, ни в корне запуска. */
    private static List<String> unresolved(FileAnalysis analysis, Set<String> known) {
        Set<String> found = new LinkedHashSet<>();
        for (Reference reference : analysis.references()) {
            if (SELF_NAMES.contains(reference.name()) || known.contains(reference.name())) {
                continue;
            }
            if (analysis.declarationOf(reference) == null) {
                found.add(reference.name());
            }
        }
        return List.copyOf(found);
    }

    /** Выполняет скрипт и возвращает имена, которые к концу запуска лежат в корне. */
    private static Set<String> run(Source source, Output output) {
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> diagnostics.renderAll());

        ExecutionContext context = ExecutionContext.fresh(output);
        Set<String> before = Set.copyOf(context.scope().names());
        new Interpreter().run(program, context);
        return before;
    }

    /** Имена корня до первой строки скрипта: встроенные функции и классы ошибок. */
    private static Set<String> rootNames() {
        return Set.copyOf(ExecutionContext.fresh().scope().names());
    }

    private static boolean hasWildcardImport(Program program) {
        return program.statements().stream()
                .anyMatch(statement -> statement instanceof ImportStmt module && !module.hasAlias());
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
