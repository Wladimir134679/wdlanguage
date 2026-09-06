package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.value.ValueType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Несколько каталогов одним: встроенное языка плюс библиотеки хозяина плюс реестр
 * модулей.
 * <p>
 * Первый сильнее — то же правило, по которому ближняя область видимости сильнее
 * дальней. Приложение, положившее в корень свой {@code println}, увидит в подсказке
 * свой, а не встроенный, если подаст свой каталог первым.
 * <p>
 * Полноту сложение <b>не наследует</b>: полон только тот набор, где полны все
 * слагаемые, — иначе одно честное «я знаю не всё» терялось бы за чужим обещанием.
 */
final class MergedCatalog implements Catalog {

    private final List<Catalog> parts;

    private MergedCatalog(List<Catalog> parts) {
        this.parts = parts;
    }

    static Catalog of(List<Catalog> catalogs) {
        Objects.requireNonNull(catalogs, "catalogs");
        List<Catalog> parts = new ArrayList<>(catalogs.size());
        for (Catalog catalog : catalogs) {
            Objects.requireNonNull(catalog, "catalog");
            // Вложенное сложение разворачивается сразу: иначе цепочка из десяти
            // каталогов стала бы деревом из десяти уровней на каждый вопрос.
            if (catalog instanceof MergedCatalog merged) {
                parts.addAll(merged.parts);
            } else if (catalog != Catalog.empty()) {
                parts.add(catalog);
            }
        }
        return switch (parts.size()) {
            case 0 -> Catalog.empty();
            case 1 -> parts.get(0);
            default -> new MergedCatalog(List.copyOf(parts));
        };
    }

    @Override
    public Collection<SymbolDescriptor> roots() {
        Map<String, SymbolDescriptor> byName = new LinkedHashMap<>();
        for (Catalog part : parts) {
            for (SymbolDescriptor descriptor : part.roots()) {
                byName.putIfAbsent(descriptor.name(), descriptor);
            }
        }
        return List.copyOf(byName.values());
    }

    @Override
    public ModuleDescriptor module(String key) {
        for (Catalog part : parts) {
            ModuleDescriptor found = part.module(key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public Collection<String> moduleKeys() {
        Collection<String> keys = new LinkedHashSet<>();
        for (Catalog part : parts) {
            keys.addAll(part.moduleKeys());
        }
        return List.copyOf(keys);
    }

    @Override
    public List<MemberDescriptor> members(ValueType type) {
        for (Catalog part : parts) {
            List<MemberDescriptor> found = part.members(type);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    @Override
    public boolean complete() {
        for (Catalog part : parts) {
            if (!part.complete()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "каталог: " + parts.size() + " источника";
    }
}
