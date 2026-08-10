package ru.wds.wdl.module;

import ru.wds.wdl.source.Source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Откуда движок берёт исходники модулей.
 * <p>
 * Ядро не решает этого за приложение — ровно по той же причине, по которой не решает,
 * куда печатает {@code println}, и не подключает стандартную библиотеку само. Скрипт,
 * выполняемый внутри чужого сервера, не обязан иметь доступ к его файловой системе,
 * а скрипту в редакторе исходники модулей могут приходить из несохранённых буферов.
 * Поэтому здесь — один метод, а откуда возьмётся текст, говорит хозяин запуска.
 * <p>
 * По умолчанию источника нет ({@link #none()}) и {@code import} отвечает ошибкой:
 * встроенный движок молча читать чужие файлы не должен.
 * <p>
 * <b>Ключ уже разрешён.</b> Сюда приходит нормализованный путь без расширения
 * и без ведущего слэша — {@code "lib/math"}. Всё, что зависит от того, какой файл
 * выполняется, разобрано до вызова (см. {@link ModuleKey#resolve}).
 */
@FunctionalInterface
public interface ModuleSource {

    /** Исходник модуля или {@code null}, если такого модуля здесь нет. */
    Source find(String key);

    /** Источника нет: любой {@code import} — ошибка с объяснением. */
    static ModuleSource none() {
        return key -> null;
    }

    /**
     * Файлы в каталоге: ключ {@code "lib/math"} — это {@code root/lib/math.wdl}.
     * <p>
     * Выход за пределы каталога не запрещается: движок не изображает песочницу,
     * которой не является. Приложению, которому нужны ограничения, проще написать
     * свой источник, чем полагаться на проверки пути здесь.
     */
    static ModuleSource ofDirectory(Path root) {
        Objects.requireNonNull(root, "root");
        return key -> {
            try {
                // normalize — ради сообщений об ошибках: имя файла из Source попадает
                // в них как есть, и путь с '..' посередине читать неприятно.
                Path file = root.resolve(key + ".wdl").normalize();
                return Files.isRegularFile(file) ? Source.ofFile(file) : null;
            } catch (InvalidPathException | IOException e) {
                // Нечитаемый файл — это «модуля нет»: причину скажет тот, кто спрашивал,
                // и скажет с местом в скрипте, которого здесь нет.
                return null;
            }
        };
    }

    /**
     * Модули, заданные текстом: {@code "lib/math"} → исходник. Для тестов и для
     * приложений, у которых скрипты лежат не в файлах.
     */
    static ModuleSource ofMap(Map<String, String> modules) {
        Map<String, String> copy = Map.copyOf(modules);
        return key -> {
            String text = copy.get(key);
            return text == null ? null : new Source(key + ".wdl", text);
        };
    }
}
