package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.Library;
import ru.wds.wdl.module.NativeModules;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Готовый набор встроенных модулей: то, что можно дать скрипту одной строкой.
 *
 * <pre>{@code
 * ExecutionContext context = ExecutionContext.fresh(Output.standard())
 *         .withModules(ModuleSource.ofDirectory(scriptHome))
 *         .withNativeModules(Sys.modules());
 * }</pre>
 *
 * <pre>{@code
 * import sys.json as json
 * import sys.io as io
 * import sys.net.http as http
 * }</pre>
 *
 * <b>Набор — это решение приложения, а не движка.</b> Отдельной таблицы модулей
 * в языке нет: здесь просто карта, и собрать свою — обычная работа. Нужен скрипт
 * без файловой системы и сети — берётся часть набора; нужна своя реализация
 * {@code sys/io} — она кладётся под тем же именем и побеждает
 * ({@link NativeModules#first}).
 * <p>
 * {@code std} лежит тут же и по той же причине, по которой всё остальное:
 * {@link Std} — обычная библиотека, и разница между «положить её имена в корень»
 * и «отдать по {@code import std as s}» только в том, куда её установили.
 */
public final class Sys {

    private Sys() {
    }

    /** Имена модулей набора и их фабрики — в порядке, в каком их стоит читать. */
    public static Map<String, Supplier<Library>> registry() {
        Map<String, Supplier<Library>> modules = new LinkedHashMap<>();
        modules.put("std", Std::library);
        modules.put("sys/io", Io::library);
        modules.put("sys/json", Json::library);
        modules.put("sys/meta", Meta::library);
        modules.put("sys/net/http", Http::library);
        modules.put("sys/gui", ru.wds.wdl.stdlib.gui.Gui::library);
        modules.put("sys/net/socket", ru.wds.wdl.stdlib.net.Sockets::library);
        modules.put("sys/thread", ru.wds.wdl.stdlib.thread.Threads::library);
        return modules;
    }

    /** Тот же набор реестром для {@code ExecutionContext.withNativeModules}. */
    public static NativeModules modules() {
        return NativeModules.of(registry());
    }
}
