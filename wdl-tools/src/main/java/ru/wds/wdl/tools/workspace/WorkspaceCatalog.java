package ru.wds.wdl.tools.workspace;

import ru.wds.wdl.tools.catalog.Catalog;
import ru.wds.wdl.tools.catalog.ModuleDescriptor;
import ru.wds.wdl.tools.catalog.SymbolDescriptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Неизменяемая экспортная поверхность проиндексированной рабочей папки. */
final class WorkspaceCatalog implements Catalog {

    private final Map<String, ModuleDescriptor> modules;
    private final List<String> keys;

    WorkspaceCatalog(Map<String, ModuleDescriptor> modules) {
        this.modules = Map.copyOf(Objects.requireNonNull(modules, "modules"));
        this.keys = this.modules.keySet().stream().sorted().toList();
    }

    @Override
    public Collection<SymbolDescriptor> roots() {
        // Файловый модуль попадает в область только через import и выбранный alias.
        return List.of();
    }

    @Override
    public ModuleDescriptor module(String key) {
        return modules.get(key);
    }

    @Override
    public Collection<String> moduleKeys() {
        return keys;
    }
}
