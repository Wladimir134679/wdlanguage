package ru.wds.wdl.source;

/**
 * Полуинтервал {@code [start, end)} в исходном тексте.
 * <p>
 * Span хранится в каждом токене и каждом узле AST — без него невозможны ни внятные
 * сообщения об ошибках, ни подсветка, ни будущий LSP. Добавлять позиции задним
 * числом всегда дороже, чем нести их с самого начала.
 * <p>
 * <b>Смещение — индекс в {@link String}, то есть единица UTF-16.</b> Не байт и не
 * кодовая точка: символ вне BMP (эмодзи) занимает две единицы, и {@code span.length()}
 * у него равна двум. Так считает и документ IntelliJ, и позиция LSP по умолчанию —
 * поэтому перевод в их координаты не требует пересчёта, только смены нумерации строк
 * ({@link Source} нумерует с единицы, LSP — с нуля).
 * <p>
 * Интервал полуоткрыт: {@code end} — позиция за последним символом. Отсюда правило
 * для узлов-ошибок: место, где парсер чего-то не дождался, — это <b>точка</b>
 * ({@link #point}) сразу за последним разобранным токеном, а не интервал чужого
 * токена, который подвернулся следующим.
 *
 * @param start смещение первого символа, включительно
 * @param end   смещение за последним символом, исключительно
 */
public record Span(int start, int end) {

    /** Отсутствие позиции — для синтетических узлов, порождённых оптимизатором. */
    public static final Span NONE = new Span(-1, -1);

    public Span {
        boolean none = (start == -1 && end == -1);
        if (!none && (start < 0 || end < start)) {
            throw new IllegalArgumentException("некорректный Span: " + start + ".." + end);
        }
    }

    public static Span point(int offset) {
        return new Span(offset, offset);
    }

    /** Объединяет два интервала: от начала этого до конца переданного. */
    public Span to(Span end) {
        if (this.equals(NONE)) {
            return end;
        }
        if (end.equals(NONE)) {
            return this;
        }
        return new Span(Math.min(start, end.start), Math.max(this.end, end.end));
    }

    public int length() {
        return end - start;
    }

    public boolean isNone() {
        return start < 0;
    }

    @Override
    public String toString() {
        return isNone() ? "<нет позиции>" : start + ".." + end;
    }
}
