package ru.wds.wdl.tools.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Снятый каталог: готовый список корневых имён и ничего больше.
 * <p>
 * Неизменяем и потому читается из любого потока — а сервер читает его из своих
 * всегда. Модули сюда не входят: их снимает {@link RegistryCatalog}, и снимает
 * лениво, потому что снять модуль дороже, чем перечислить область.
 */
final class SnapshotCatalog implements Catalog {

    private final List<SymbolDescriptor> roots;

    SnapshotCatalog(List<SymbolDescriptor> roots) {
        this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
    }

    @Override
    public Collection<SymbolDescriptor> roots() {
        return roots;
    }

    @Override
    public ModuleDescriptor module(String key) {
        return null;
    }

    @Override
    public String toString() {
        return "каталог: " + roots.size() + " имён";
    }
}
