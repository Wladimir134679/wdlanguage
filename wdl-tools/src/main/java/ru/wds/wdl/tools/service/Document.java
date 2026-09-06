package ru.wds.wdl.tools.service;

import ru.wds.wdl.source.Source;
import ru.wds.wdl.tools.analysis.FileAnalysis;

import java.util.Objects;

/**
 * Открытый документ: текст, версия и весь разбор этого текста.
 * <p>
 * Неизменяем целиком — правка не меняет документ, а создаёт следующий. Отсюда два
 * свойства, ради которых это и сделано так: ответ, начатый на версии N, досчитается
 * по версии N, даже если пришла N+1; и читать документ можно из любого потока,
 * не спрашивая разрешения.
 * <p>
 * Разбор идёт при создании, а не при первом вопросе: редактор всё равно спросит —
 * диагностику он запрашивает сам, без просьбы пользователя.
 *
 * @param id       адрес документа
 * @param version  версия правки; растёт у того, кто редактирует
 * @param source   текст с индексом строк
 * @param analysis дерево, диагностика, области и имена
 */
public record Document(DocumentId id, long version, Source source, FileAnalysis analysis) {

    public Document {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(analysis, "analysis");
    }

    /** Разбирает текст и собирает документ. Имя источника — короткое имя адреса. */
    public static Document of(DocumentId id, long version, String text) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Source source = new Source(id.shortName(), text);
        return new Document(id, version, source, FileAnalysis.of(source));
    }

    public String text() {
        return source.text();
    }

    @Override
    public String toString() {
        return id + "@" + version + ", " + source.length() + " символов";
    }
}
