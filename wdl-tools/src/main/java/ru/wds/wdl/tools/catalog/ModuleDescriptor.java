package ru.wds.wdl.tools.catalog;

import java.util.List;
import java.util.Objects;

/**
 * Состав модуля: то, что видно после {@code import sys.io as io}.
 * <p>
 * Ключ здесь тот же, каким модуль зовут в реестре ({@code "sys/io"}), а не путь
 * из текста скрипта: {@code import sys.io} и {@code import "sys/io"} — одно и то же
 * имя в одной таблице, и каталог обязан отвечать одинаково на оба.
 * <p>
 * Псевдоним ({@code io}) сюда не попадает и попасть не может: его выбирает автор
 * скрипта в каждом файле по-своему, а каталог один на весь запуск.
 *
 * @param key           ключ модуля: {@code "sys/io"}
 * @param name          короткое имя: {@code "io"} — то, что обычно и пишут псевдонимом
 * @param documentation описание самого модуля или {@code null}
 * @param names         имена модуля, отсортированные по алфавиту
 */
public record ModuleDescriptor(String key, String name, String documentation,
                               List<SymbolDescriptor> names) {

    public ModuleDescriptor {
        Objects.requireNonNull(key, "key");
        name = name == null ? shortNameOf(key) : name;
        names = names == null ? List.of() : List.copyOf(names);
    }

    public ModuleDescriptor(String key, List<SymbolDescriptor> names) {
        this(key, shortNameOf(key), null, names);
    }

    /** Пустой модуль: имя известно, состав — нет. */
    public static ModuleDescriptor empty(String key) {
        return new ModuleDescriptor(key, List.of());
    }

    public boolean hasDocumentation() {
        return documentation != null && !documentation.isBlank();
    }

    /** Имя модуля или {@code null}: {@code io.read} без запуска. */
    public SymbolDescriptor get(String memberName) {
        for (SymbolDescriptor descriptor : names) {
            if (descriptor.name().equals(memberName)) {
                return descriptor;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }

    /** {@code "sys/net/http"} → {@code "http"}: последнее звено пути и есть имя. */
    private static String shortNameOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    @Override
    public String toString() {
        return "модуль '" + key + "' (" + names.size() + ")";
    }
}
