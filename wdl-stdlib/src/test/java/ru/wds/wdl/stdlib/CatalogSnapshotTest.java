package ru.wds.wdl.stdlib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.Catalogs;
import ru.wds.wdl.tools.catalog.MemberDescriptor;
import ru.wds.wdl.tools.catalog.ModuleDescriptor;
import ru.wds.wdl.tools.catalog.Origin;
import ru.wds.wdl.tools.catalog.SymbolDescriptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Каталог стандартной библиотеки снимается с неё самой.
 * <p>
 * Тест стоит здесь, а не в {@code wdl-tools}: снимок делается с этих модулей,
 * и разойтись он может только с ними. Сама библиотека про инструменты не знает —
 * зависимость тестовая.
 */
class CatalogSnapshotTest {

    private static final Catalog CATALOG = Catalogs.ofModules(Sys.registry());

    @Test
    @DisplayName("Реестр модулей перечисляется целиком и по алфавиту")
    void registryIsListed() {
        List<String> keys = List.copyOf(CATALOG.moduleKeys());

        assertTrue(keys.containsAll(List.of("std", "sys/io", "sys/json", "sys/net/http",
                "sys/net/socket", "sys/thread", "sys/time", "sys/meta")), keys.toString());
        assertEquals(keys.stream().sorted().toList(), keys, "порядок обязан быть устойчивым");
    }

    @Test
    @DisplayName("Состав sys.io снимается установкой: имена, сигнатуры, описания")
    void ioIsSnapshotted() {
        ModuleDescriptor io = CATALOG.module("sys/io");

        assertNotNull(io);
        assertEquals("io", io.name());
        assertTrue(io.hasDocumentation(), "у модуля есть своё описание");

        SymbolDescriptor read = io.get("read");
        assertNotNull(read, "функции модуля перечисляются: " + io.names());
        assertEquals(SymbolKind.FUNCTION, read.kind());
        assertEquals("read(path)", read.signature(), "имя параметра берётся из сигнатуры");
        assertEquals("читает файл целиком в строку", read.documentation());
        assertEquals(Origin.MODULE, read.origin());

        SymbolDescriptor write = io.get("write");
        assertEquals("write(path, text)", write.signature());

        // Функции, заведённые куском кода в install(...), видны наравне с остальными:
        // каталог читает область, а не список звеньев построителя.
        SymbolDescriptor open = io.get("open");
        assertNotNull(open, "open заводится install(...)");
        assertEquals("open(path)", open.signature());
        assertTrue(open.hasDocumentation());
    }

    @Test
    @DisplayName("Константа модуля идёт со значением, класс — с членами")
    void constantsAndClassesAreDescribed() {
        ModuleDescriptor io = CATALOG.module("sys/io");

        SymbolDescriptor separator = io.get("SEPARATOR");
        assertEquals(SymbolKind.CONSTANT, separator.kind());
        assertTrue(separator.signature().startsWith("SEPARATOR = "), separator.signature());
        assertEquals("разделитель имён в пути этой машины", separator.documentation());

        SymbolDescriptor file = io.get("File");
        assertEquals(SymbolKind.CLASS, file.kind());
        assertTrue(file.signature().startsWith("class File("), file.signature());
        assertEquals("файл на диске: чтение, запись, дозапись по одному пути", file.documentation());

        MemberDescriptor read = file.member("read");
        assertNotNull(read, "члены класса перечисляются вместе с ним: " + file.members());
        assertEquals(MemberDescriptor.Kind.METHOD, read.kind());

        MemberDescriptor temp = file.staticMember("temp");
        assertNotNull(temp, "фабрики живут на самом классе, а не на экземпляре");
        assertEquals(MemberDescriptor.Kind.METHOD, temp.kind());
        assertTrue(file.members().stream().noneMatch(member -> member.name().equals("temp")));
    }

    @Test
    @DisplayName("std снимается тем же способом, что и модули sys")
    void standardLibraryIsSnapshotted() {
        ModuleDescriptor std = CATALOG.module("std");

        SymbolDescriptor pow = std.get("pow");
        assertEquals("pow(base, exponent)", pow.signature());
        assertTrue(pow.hasDocumentation());
    }

    @Test
    @DisplayName("Имена std, положенные в корень, описаны там же")
    void standardLibraryInRootScopeKeepsItsDocumentation() {
        ExecutionContext context = ExecutionContext.fresh();
        try {
            Std.install(context.scope());
            Catalog root = Catalogs.of(context.scope(), Origin.LIBRARY);

            SymbolDescriptor sqrt = root.root("sqrt");
            assertNotNull(sqrt);
            assertEquals("sqrt(value)", sqrt.signature());
            assertTrue(sqrt.hasDocumentation(), "описание доехало до значения, а не осталось"
                    + " в построителе");

            // Встроенное языка видно в той же области — каталог снимает её целиком.
            assertNotNull(root.root("println"));
        } finally {
            context.closeRun();
        }
    }

    @Test
    @DisplayName("Каталог модуля не зависит от того, сколько раз его спросили")
    void snapshotIsStable() {
        assertEquals(CATALOG.module("sys/json").names().size(),
                CATALOG.module("sys/json").names().size());
        assertNotNull(CATALOG.module("sys/json").get("parse"));
        assertEquals("stringify(value, indent?)", CATALOG.module("sys/json")
                .get("stringify").signature());
    }
}
