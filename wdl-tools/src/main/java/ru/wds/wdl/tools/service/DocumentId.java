package ru.wds.wdl.tools.service;

import java.util.Objects;

/**
 * Чем документ адресуется у того, кто его открыл.
 * <p>
 * Строка, а не {@code Path}, потому что документ не обязан быть файлом: редактор
 * внутри приложения держит скрипт в базе, LSP присылает URI, а тест — выдуманное
 * имя. Разбирать этот адрес сервису незачем — он его только помнит и возвращает.
 *
 * @param uri адрес документа в понятиях того, кто его открыл
 */
public record DocumentId(String uri) {

    public DocumentId {
        Objects.requireNonNull(uri, "uri");
        if (uri.isBlank()) {
            throw new IllegalArgumentException("пустой адрес документа");
        }
    }

    public static DocumentId of(String uri) {
        return new DocumentId(uri);
    }

    /**
     * Короткое имя для сообщений: последнее звено адреса.
     * <p>
     * Им подписывается {@link ru.wds.wdl.source.Source}, а значит, и каждая строка
     * диагностики: {@code hello.wdl:3:5: ошибка: ...} читается, а весь URI — нет.
     */
    public String shortName() {
        int slash = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('\\'));
        return slash < 0 || slash == uri.length() - 1 ? uri : uri.substring(slash + 1);
    }

    @Override
    public String toString() {
        return uri;
    }
}
