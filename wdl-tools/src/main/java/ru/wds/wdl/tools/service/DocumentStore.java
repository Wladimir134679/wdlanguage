package ru.wds.wdl.tools.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Открытые документы и их версии.
 * <p>
 * Главное правило здесь одно: <b>документ с меньшей версией не заменяет больший</b>.
 * Редактор шлёт правки быстрее, чем сервер отвечает, и правка N вполне может прийти
 * после N+1 — по дороге через очереди. Без этого правила редактор однажды подчеркнёт
 * ошибку, которой в тексте уже нет, и повторить это будет нечем.
 * <p>
 * Изменяемое состояние живёт дольше вызова, значит, оно конкурентное:
 * {@link ConcurrentHashMap} и вся правка одного документа — внутри {@code compute},
 * то есть атомарно относительно других правок того же документа.
 */
public final class DocumentStore {

    private final ConcurrentHashMap<DocumentId, Document> open = new ConcurrentHashMap<>();

    /** Открывает документ заново: прошлое содержимое, если оно было, забывается. */
    public Document open(DocumentId id, long version, String text) {
        Objects.requireNonNull(id, "id");
        Document document = Document.of(id, version, text);
        open.put(id, document);
        return document;
    }

    /**
     * Применяет правку. Устаревшая (версия не больше текущей) отбрасывается, и в ответ
     * приходит документ, который остался в силе, — а не отказ: клиент ни в чём
     * не виноват, он просто опоздал.
     */
    public Document change(DocumentId id, long version, String text) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        return open.compute(id, (key, current) -> current != null && current.version() >= version
                ? current
                : Document.of(key, version, text));
    }

    /** Закрывает документ и отдаёт то, что было открыто, или {@code null}. */
    public Document close(DocumentId id) {
        Objects.requireNonNull(id, "id");
        return open.remove(id);
    }

    /** Открытый документ или {@code null}: спросить про закрытый — обычное дело. */
    public Document get(DocumentId id) {
        return id == null ? null : open.get(id);
    }

    public boolean isOpen(DocumentId id) {
        return get(id) != null;
    }

    public Collection<DocumentId> ids() {
        return List.copyOf(open.keySet());
    }

    public int size() {
        return open.size();
    }

    public void clear() {
        open.clear();
    }

    @Override
    public String toString() {
        return "открытых документов: " + open.size();
    }
}
