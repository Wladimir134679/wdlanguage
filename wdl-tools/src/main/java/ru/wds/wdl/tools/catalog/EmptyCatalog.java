package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.value.ValueType;

import java.util.Collection;
import java.util.List;

/**
 * Каталог, который ничего не знает.
 * <p>
 * Не заглушка на время разработки, а рабочее значение по умолчанию: анализ файла
 * от каталога не зависит, и потребитель, которому нечего подать, ничего не теряет —
 * дополнение просто не предложит {@code println}. Члены типов здесь тоже пусты:
 * пустой каталог не притворяется, что знает язык.
 */
final class EmptyCatalog implements Catalog {

    static final Catalog INSTANCE = new EmptyCatalog();

    private EmptyCatalog() {
    }

    @Override
    public Collection<SymbolDescriptor> roots() {
        return List.of();
    }

    @Override
    public ModuleDescriptor module(String key) {
        return null;
    }

    @Override
    public List<MemberDescriptor> members(ValueType type) {
        return List.of();
    }

    @Override
    public String toString() {
        return "каталог: пусто";
    }
}
