package ru.wds.wdl.tools.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.module.Library;
import ru.wds.wdl.runtime.BuiltinFunction;
import ru.wds.wdl.runtime.Environment;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.analysis.FileAnalysis;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.value.Signature;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сложение файла и каталога: что видно в точке и что это за имя.
 */
class LookupTest {

    private static final Catalog CATALOG = Catalog.merged(
            Catalogs.builtins(),
            Catalogs.ofModules(Map.of("test/lib", LookupTest::library)));

    @Test
    @DisplayName("Имена файла идут раньше встроенных")
    void fileNamesComeFirst() {
        String text = """
                price = 120
                def total(count) => price * count
                """;
        List<Suggestion> names = lookup(text).namesAt(text.indexOf("price * count"));

        // Ближнее имя раньше дальнего, имя файла — раньше любого внешнего.
        assertEquals(List.of("count", "price", "total"), names.stream()
                .filter(name -> name.origin() == Origin.FILE)
                .map(Suggestion::name).toList());
        assertEquals(Origin.FILE, names.get(0).origin());
        assertTrue(names.get(0).isDeclaredHere());
        assertEquals(Origin.FILE, names.get(2).origin(), "три своих имени идут подряд");
        assertNotEquals(Origin.FILE, names.get(3).origin(), "дальше только внешние");

        Suggestion println = find(names, "println");
        assertEquals(Origin.BUILTIN, println.origin());
        assertFalse(println.isDeclaredHere(), "у встроенного имени места в тексте нет");
        assertTrue(println.hasDocumentation());
    }

    @Test
    @DisplayName("Своё имя затеняет встроенное и встречается один раз")
    void ownNameShadowsBuiltin() {
        String text = """
                def println(text) => text
                println("эй")
                """;
        List<Suggestion> names = lookup(text).namesAt(text.indexOf("println(\"эй\")"));

        assertEquals(1, names.stream().filter(name -> name.name().equals("println")).count());
        assertEquals(Origin.FILE, find(names, "println").origin());
    }

    @Test
    @DisplayName("После import предлагаются пути модулей")
    void importSuggestsModulePaths() {
        String text = "import test.lib as lib\n";
        List<Suggestion> paths = lookup(text).completeAt(text.indexOf("test.lib") + 4);

        assertEquals(List.of("test/lib"), paths.stream().map(Suggestion::name).toList());
        assertEquals(SymbolKind.MODULE, paths.get(0).kind());
        assertEquals("модуль для проверки", paths.get(0).documentation());
    }

    @Test
    @DisplayName("После псевдонима модуля предлагаются его имена")
    void moduleAliasSuggestsItsNames() {
        String text = """
                import test.lib as lib
                lib.
                """;
        List<Suggestion> names = lookup(text).completeAt(text.indexOf("lib.") + 4);

        assertEquals(List.of("SEPARATOR", "read"), names.stream().map(Suggestion::name).toList());
        assertEquals("read(path)", find(names, "read").signature());
        assertEquals("читает файл целиком", find(names, "read").documentation());
        assertEquals(Origin.MODULE, find(names, "read").origin());
    }

    @Test
    @DisplayName("После точки у однозначно присвоенной строки видны её члены")
    void inferredStringSuggestsBuiltinMembers() {
        String text = """
                text = "эй"
                text.
                """;
        List<Suggestion> members = lookup(text).completeAt(text.indexOf("text.") + 5);

        assertTrue(members.stream().anyMatch(member -> member.name().equals("size")));
        assertEquals(SymbolKind.PROPERTY, find(members, "size").kind());
        assertEquals(Origin.BUILTIN, find(members, "size").origin());
    }

    @Test
    @DisplayName("Локальный класс различает члены экземпляра и фабрики")
    void localClassSeparatesInstanceAndStaticMembers() {
        String prefix = """
                class Box(value) {
                    property label => value
                    def open() => value
                    def Box.empty() => new Box("")
                }
                """;
        String instanceText = prefix + "new Box(\"x\").\n";
        String staticText = prefix + "Box.\n";
        List<Suggestion> instance = lookup(instanceText).completeAt(instanceText.indexOf("new Box(\"x\").")
                + "new Box(\"x\").".length());
        List<Suggestion> statics = lookup(staticText).completeAt(staticText.lastIndexOf("Box.") + 4);

        assertTrue(instance.stream().anyMatch(member -> member.name().equals("value")));
        assertTrue(instance.stream().anyMatch(member -> member.name().equals("label")));
        assertTrue(instance.stream().anyMatch(member -> member.name().equals("open")));
        assertFalse(instance.stream().anyMatch(member -> member.name().equals("empty")));
        assertTrue(statics.stream().anyMatch(member -> member.name().equals("empty")), statics::toString);
        assertFalse(statics.stream().anyMatch(member -> member.name().equals("open")));
    }

    @Test
    @DisplayName("Несколько присваиваний не выдают ложный точный список")
    void ambiguousAssignmentsSuggestNothing() {
        String text = """
                value = "строка"
                value = [1]
                value.
                """;

        assertTrue(lookup(text).completeAt(text.indexOf("value.\n") + 6).isEmpty());
    }

    @Test
    @DisplayName("Параметр, затенивший псевдоним, не приносит имён модуля")
    void shadowedAliasSuggestsNothing() {
        String text = """
                import test.lib as lib
                def f(lib) {
                    lib.
                }
                """;
        assertTrue(lookup(text).completeAt(text.indexOf("lib.\n") + 4).isEmpty(),
                "параметр с тем же именем — другое имя, и модуля за ним нет");
    }

    @Test
    @DisplayName("Наведение: имя файла, встроенное имя и член модуля")
    void hoverAnswersAllThree() {
        String text = """
                import test.lib as lib
                // цена без скидки
                price = 120
                println(lib.read("a.txt"), price)
                """;
        Lookup lookup = lookup(text);

        Suggestion own = lookup.describeAt(text.indexOf(", price)") + 2);
        assertEquals("price", own.name());
        assertEquals(Origin.FILE, own.origin());
        assertEquals("цена без скидки", own.documentation());

        Suggestion builtin = lookup.describeAt(text.indexOf("println(lib"));
        assertEquals(Origin.BUILTIN, builtin.origin());
        assertEquals("println(…)", builtin.signature());

        Suggestion member = lookup.describeAt(text.indexOf("read(\"a.txt\")"));
        assertNotNull(member, "член модуля описан в самом модуле");
        assertEquals("read(path)", member.signature());
        assertEquals("читает файл целиком", member.documentation());

        Suggestion module = lookup.describeAt(text.indexOf("test.lib") + 2);
        assertNotNull(module);
        assertEquals(SymbolKind.MODULE, module.kind());
        assertEquals("модуль для проверки", module.documentation());
    }

    @Test
    @DisplayName("Неизвестное имя — не ошибка, а пустой ответ")
    void unknownNameIsNotAnError() {
        String text = "unknownName()\n";

        assertNull(lookup(text).describeAt(text.indexOf("unknownName")));
    }

    @Test
    @DisplayName("Без каталога всё работает по-прежнему, только молча")
    void emptyCatalogStillWorks() {
        String text = "price = 120\nprintln(price)\n";
        Lookup lookup = Lookup.of(FileAnalysis.of(Source.ofString(text)), Catalog.empty());

        assertEquals(List.of("price"), lookup.namesAt(text.indexOf("println")).stream()
                .map(Suggestion::name).toList());
        assertNull(lookup.describeAt(text.indexOf("println")));
    }

    private static Lookup lookup(String text) {
        return Lookup.of(FileAnalysis.of(Source.ofString(text)), CATALOG);
    }

    private static Suggestion find(List<Suggestion> names, String name) {
        return names.stream()
                .filter(suggestion -> suggestion.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет имени '" + name + "' среди " + names));
    }

    /** Библиотека, которая умеет рассказать о себе: то же, что делает {@code Module}. */
    private interface DocumentedLibrary extends Library, ru.wds.wdl.value.Documented {
    }

    /** Модуль на одних типах ядра: каталогу большего и не нужно. */
    private static DocumentedLibrary library() {
        return new DocumentedLibrary() {

            @Override
            public String name() {
                return "test/lib";
            }

            @Override
            public String documentation() {
                return "модуль для проверки";
            }

            @Override
            public Environment installTo(Environment scope) {
                scope.define("read", BuiltinFunction.of("read",
                                Signature.of(Signature.Param.required("path")),
                                (context, arguments, span) -> NullValue.NULL)
                        .documented("читает файл целиком"));
                scope.defineConstant("SEPARATOR", StringValue.of("/"));
                return scope;
            }
        };
    }
}
