package ru.wds.wdl.module;

import java.util.ArrayList;
import java.util.List;

/**
 * Ключ модуля: то, во что превращается путь из инструкции {@code import}.
 * <p>
 * Ключ — имя модуля для всего движка сразу: по нему модуль ищут в источнике, узнают
 * в реестрах и называют в сообщениях об ошибках. Разные записи одного пути обязаны
 * давать один ключ — иначе один и тот же файл разберётся дважды и даст два разных
 * набора классов.
 * <p>
 * Работа здесь чисто текстовая, без файловой системы: где лежат исходники, знает
 * {@link ModuleSource}.
 */
public final class ModuleKey {

    private static final String EXTENSION = ".wdl";

    private ModuleKey() {
    }

    /**
     * Приводит путь из инструкции к ключу модуля.
     * <p>
     * Путь сюда приходит уже с {@code /} — точечную запись {@code import a.b} парсер
     * разворачивает при разборе, потому что там ещё видно, где точка означала каталог,
     * а где она просто часть имени файла в строке.
     * <p>
     * Ведущий {@code /} значит «от корня запуска», всё остальное — от каталога того
     * файла, где написан {@code import}. Так набор файлов переносится целиком: модуль
     * в подкаталоге ссылается на соседа по короткому имени и не знает, откуда запустили
     * главный скрипт.
     *
     * @param path путь так, как он написан в инструкции
     * @param home каталог импортирующего файла ({@code ""} — корень запуска)
     */
    public static String resolve(String path, String home) {
        String cleaned = path.replace('\\', '/');
        if (cleaned.endsWith(EXTENSION)) {
            cleaned = cleaned.substring(0, cleaned.length() - EXTENSION.length());
        }

        List<String> segments = new ArrayList<>();
        if (!cleaned.startsWith("/")) {
            append(segments, home);
        }
        append(segments, cleaned);
        return String.join("/", segments);
    }

    /** Каталог модуля: от него считаются пути его собственных импортов. */
    public static String homeOf(String key) {
        if (key == null) {
            return "";
        }
        int slash = key.lastIndexOf('/');
        return slash < 0 ? "" : key.substring(0, slash);
    }

    /**
     * Разбирает путь на части, убирая пустые и {@code .}; {@code ..} поднимает на
     * уровень выше. Подняться выше корня разрешено: получившийся ключ так и останется
     * с {@code ..}, и что с ним делать, решит источник модулей — у файлов на диске
     * такой путь осмысленный, у карты в памяти его просто не найдётся.
     */
    private static void append(List<String> segments, String path) {
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..") && !segments.isEmpty()
                    && !segments.get(segments.size() - 1).equals("..")) {
                segments.remove(segments.size() - 1);
                continue;
            }
            segments.add(segment);
        }
    }
}
