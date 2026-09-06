package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.value.ValueType;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Тот же каталог, но заявляющий полноту.
 * <p>
 * Обёрткой, а не полем снимка, потому что знает об этом не снимок, а тот, кто собрал
 * запуск: имя можно завести и после снятия, и сам каталог об этом никогда не узнает.
 * Всё остальное проходит насквозь — состав от этого не меняется, меняется только
 * обещание.
 */
final class CompleteCatalog implements Catalog {

    private final Catalog inner;

    CompleteCatalog(Catalog inner) {
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    @Override
    public Collection<SymbolDescriptor> roots() {
        return inner.roots();
    }

    @Override
    public SymbolDescriptor root(String name) {
        return inner.root(name);
    }

    @Override
    public ModuleDescriptor module(String key) {
        return inner.module(key);
    }

    @Override
    public Collection<String> moduleKeys() {
        return inner.moduleKeys();
    }

    @Override
    public List<MemberDescriptor> members(ValueType type) {
        return inner.members(type);
    }

    @Override
    public boolean complete() {
        return true;
    }

    @Override
    public String toString() {
        return inner + " (полный)";
    }
}
