package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.module.Library;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Каталог встроенных модулей поверх того же реестра, из которого их берёт
 * {@code import}: ключ — имя модуля, значение — фабрика библиотеки.
 * <p>
 * <b>Снимок ленивый и по одному модулю.</b> Снять модуль — значит установить
 * библиотеку, а установка это выполнение нашего кода: {@code sys.gui} при сборке
 * классов тянет Swing, {@code sys.thread} заводит пул. Снимать весь реестр на старте
 * сервера нельзя; снимается тот модуль, о котором спросили, и результат остаётся
 * в кэше до конца процесса.
 * <p>
 * Кэш конкурентный: каталог читают из потоков сервера, а снимок неизменяем. Дважды
 * снятый в гонке модуль ничего не портит — установка идёт в выброшенную область
 * и наружу не видна.
 */
final class RegistryCatalog implements Catalog {

    private final Map<String, Supplier<Library>> registry;
    private final List<String> keys;
    private final Map<String, ModuleDescriptor> snapshots = new ConcurrentHashMap<>();

    RegistryCatalog(Map<String, Supplier<Library>> registry) {
        this.registry = Map.copyOf(Objects.requireNonNull(registry, "registry"));
        // Порядок ключей фиксируется здесь: дополнению путей после import нужен
        // устойчивый список, а порядок карты-источника не обещан никем.
        this.keys = this.registry.keySet().stream().sorted().toList();
    }

    @Override
    public Collection<SymbolDescriptor> roots() {
        // Модуль сам по себе имени в корневой области не заводит: его туда кладёт
        // import, и под тем именем, которое выбрал автор файла.
        return List.of();
    }

    @Override
    public ModuleDescriptor module(String key) {
        Supplier<Library> factory = registry.get(key);
        if (factory == null) {
            return null;
        }
        return snapshots.computeIfAbsent(key, name -> Catalogs.snapshot(name, factory));
    }

    @Override
    public Collection<String> moduleKeys() {
        return keys;
    }

    @Override
    public String toString() {
        return "каталог модулей: " + registry.size();
    }
}
