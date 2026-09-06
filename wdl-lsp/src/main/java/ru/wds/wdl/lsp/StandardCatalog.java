package ru.wds.wdl.lsp;

import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Output;
import ru.wds.wdl.stdlib.Std;
import ru.wds.wdl.stdlib.Sys;
import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.Catalogs;
import ru.wds.wdl.tools.catalog.Origin;

/**
 * Каталог имён той конфигурации, которую собирает консольный запуск {@code wdl}.
 * <p>
 * Снимается с движка, а не пишется рядом: разойтись со списком имён, который получит
 * скрипт, он поэтому не может. Ровно то же делает {@code wdl --catalog}, и это одна
 * из причин, по которой оба ответа обязаны совпадать.
 * <p>
 * Собирается один раз на запуск сервера: имена языка и стандартной библиотеки
 * не меняются, пока не сменился сам движок, а снятие стоит установки библиотек.
 * Приложение со своим набором модулей соберёт свой каталог тем же способом
 * и подаст его в {@link ru.wds.wdl.tools.service.LanguageService#of}.
 */
public final class StandardCatalog {

    private StandardCatalog() {
    }

    public static Catalog create() {
        ExecutionContext context = ExecutionContext.fresh(Output.discarding())
                .withNativeModules(Sys.modules());
        Std.install(context.scope());
        Catalog roots;
        try {
            roots = Catalogs.of(context.scope(), Origin.LIBRARY);
        } finally {
            // Снимок сделан — держать запуск открытым незачем: сервер живёт часами.
            context.shutdownModules();
            context.closeRun();
        }
        // Каталог языка идёт первым: в сложении сильнее первый, и println получает
        // происхождение «встроено в язык», а не «библиотека», хотя лежит в той же области.
        return Catalogs.complete(Catalog.merged(Catalogs.builtins(), roots,
                Catalogs.ofModules(Sys.registry())));
    }
}
