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
     * Все пути считаются от корня проекта, независимо от импортирующего файла.
     * Ведущий слэш и ./ не меняют базовый каталог.
     *
     * @param path путь так, как он написан в инструкции
     * @param home сохранён для совместимости; не используется
     */
    public static String resolve(String path, String home) {
        String cleaned = path.replace('\\', '/');
        if (cleaned.endsWith(EXTENSION)) {
            cleaned = cleaned.substring(0, cleaned.length() - EXTENSION.length());
        }

        List<String> segments = new ArrayList<>();
        append(segments, cleaned);
        return String.join("/", segments);
    }

    /**
     * Имя, под которым модуль ищется среди встроенных: путь как написан, без
     * расширения и с {@code /} вместо {@code \}.
     * <p>
     * Каталог импортирующего файла здесь не участвует, и это главное отличие
     * встроенного модуля от файла: у {@link NativeModules встроенного} каталога нет,
     * поэтому {@code import sys.json} обязан означать одно и то же в любом файле.
     */
    public static String name(String path) {
        String cleaned = path.replace('\\', '/');
        return cleaned.endsWith(EXTENSION)
                ? cleaned.substring(0, cleaned.length() - EXTENSION.length())
                : cleaned;
    }

    /**
     * Написан ли путь так, что речь заведомо о файле.
     * <p>
     * Ведущий {@code /} — «от корня запуска», ведущие {@code ./} и {@code ../} —
     * «от корня проекта». Всё это указания на место в дереве файлов, а у
     * встроенного модуля места нет. Отсюда и способ дотянуться до файла, имя которого
     * занято встроенным модулем: {@code import "./sys/json"}.
     */
    public static boolean isExplicitPath(String path) {
        String cleaned = path.replace('\\', '/');
        return cleaned.startsWith("/") || cleaned.equals(".") || cleaned.equals("..")
                || cleaned.startsWith("./") || cleaned.startsWith("../");
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
