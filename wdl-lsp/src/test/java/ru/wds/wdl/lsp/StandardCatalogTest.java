package ru.wds.wdl.lsp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.stdlib.Sys;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.ModuleDescriptor;
import ru.wds.wdl.tools.catalog.Origin;
import ru.wds.wdl.tools.catalog.SymbolDescriptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сервер знает ровно ту конфигурацию, которую соберёт запуск: каталог снят
 * с движка, а не написан рядом.
 */
class StandardCatalogTest {

    @Test
    @DisplayName("Каталог сервера — это язык, стандартная библиотека и встроенные модули")
    void catalogRepeatsTheRuntime() {
        Catalog catalog = StandardCatalog.create();

        SymbolDescriptor println = catalog.root("println");
        assertNotNull(println, "встроенное в язык");
        assertEquals(Origin.BUILTIN, println.origin(),
                "язык идёт первым в сложении и потому не выглядит библиотекой");

        SymbolDescriptor math = catalog.root("sqrt");
        assertNotNull(math, "std кладётся в корень запуском, а не языком");
        assertEquals(Origin.LIBRARY, math.origin());

        SymbolDescriptor file = catalog.root("File");
        assertNotNull(file);
        assertEquals(SymbolKind.CLASS, file.kind());

        assertEquals(Sys.registry().keySet(), Set.copyOf(catalog.moduleKeys()),
                "модули те же, что доступны import");
        ModuleDescriptor io = catalog.module("sys/io");
        assertNotNull(io);
        assertNotNull(io.get("read"), "io.read — первое, что спросят у sys.io");
        assertTrue(catalog.complete(), "полноту заявляет тот, кто собрал запуск");
    }
}
