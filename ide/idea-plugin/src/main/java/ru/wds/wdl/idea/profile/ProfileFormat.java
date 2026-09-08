package ru.wds.wdl.idea.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Чтение файла, который написал {@code wdl --profile-out}.
 * <p>
 * Читается он всегда в UTF-8, а не в кодировке платформы: файл пишется в UTF-8
 * на любой машине, и на Windows кодировка консоли увела бы имена файлов.
 */
public final class ProfileFormat {

    private ProfileFormat() {
    }

    /** Файл нечитаем: не тот формат, не та версия или обрыв записи. */
    public static final class BadProfileException extends IOException {
        BadProfileException(String message) {
            super(message);
        }

        BadProfileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Разбирает отчёт.
     *
     * @throws BadProfileException файла нет содержимого, он не JSON или версия формата
     *                             незнакома — всё это разные сообщения, а не одно
     *                             исключение парсера
     */
    public static ProfileReport read(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            throw new BadProfileException("Профиль не получен: файл " + file + " пуст или не создан");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    /** То же чтение из готового потока — отсюда же читают тесты. */
    public static ProfileReport parse(Reader source) throws IOException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseReader(source);
            if (!parsed.isJsonObject()) {
                throw new BadProfileException("Это не отчёт профилировщика wdl: в корне файла не объект");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new BadProfileException("Файл не разбирается как JSON: " + e.getMessage(), e);
        }
        int version = integer(root, "version");
        if (version != ProfileReport.FORMAT_VERSION) {
            // Версия читается до всего остального: незнакомый файл должен сказать,
            // что он от другой версии wdl, а не рассыпаться на пропавшем поле.
            throw new BadProfileException("Отчёт от другой версии wdl: ожидалась "
                    + ProfileReport.FORMAT_VERSION + ", получена " + version
                    + ". Обновите плагин или wdl до одной версии.");
        }
        List<ProfileSite> sites = new ArrayList<>();
        for (JsonElement element : array(root, "sites")) {
            JsonObject site = element.getAsJsonObject();
            sites.add(new ProfileSite(
                    text(site, "kind"), text(site, "name"), text(site, "file"),
                    integer(site, "line"), integer(site, "offset"),
                    number(site, "calls"), number(site, "total_ns"),
                    number(site, "self_ns"), number(site, "max_ns")));
        }
        List<ProfileEdge> edges = new ArrayList<>();
        for (JsonElement element : array(root, "edges")) {
            JsonObject edge = element.getAsJsonObject();
            int caller = integer(edge, "caller");
            int callee = integer(edge, "callee");
            // Ребро в никуда читателя не касается: показать его всё равно нечем.
            if (caller < 0 || caller >= sites.size() || callee < 0 || callee >= sites.size()) {
                continue;
            }
            edges.add(new ProfileEdge(caller, callee, number(edge, "calls"), number(edge, "total_ns")));
        }
        return new ProfileReport(version, text(root, "script"), number(root, "calls"),
                number(root, "self_ns"), number(root, "wall_ns"), integer(root, "threads"),
                sites, edges);
    }

    private static JsonArray array(JsonObject owner, String key) {
        JsonElement value = owner.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String text(JsonObject owner, String key) {
        JsonElement value = owner.get(key);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static long number(JsonObject owner, String key) {
        JsonElement value = owner.get(key);
        return value == null || !value.isJsonPrimitive() ? 0L : value.getAsLong();
    }

    private static int integer(JsonObject owner, String key) {
        return (int) number(owner, key);
    }
}
