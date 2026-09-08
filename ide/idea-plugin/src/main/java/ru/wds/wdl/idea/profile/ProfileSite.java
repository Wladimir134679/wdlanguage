package ru.wds.wdl.idea.profile;

/**
 * Одна запись профиля: что вызывали, где оно объявлено и во что обошлось.
 *
 * @param kind    вид записи как его назвал {@code wdl}: {@code script}, {@code function},
 *                {@code constructor}, {@code native}
 * @param name    имя функции, класса или файла
 * @param file    файл объявления; пуст у встроенной функции — у неё места в тексте нет
 * @param line    строка объявления с единицы; ноль, когда места нет
 * @param offset  смещение объявления в единицах UTF-16 — то же, чем меряет документ
 *                IntelliJ; {@code -1}, когда места нет
 * @param calls   сколько раз вызывали
 * @param totalNanos время от входа до выхода вместе с вложенными вызовами
 * @param selfNanos  то же за вычетом вложенных вызовов
 * @param maxNanos   самый долгий вызов
 */
public record ProfileSite(String kind, String name, String file, int line, int offset,
                          long calls, long totalNanos, long selfNanos, long maxNanos) {

    /** Есть ли куда переходить: у встроенной функции места в тексте нет. */
    public boolean hasPlace() {
        return !file.isBlank() && offset >= 0;
    }

    /**
     * Подпись для таблицы: имя и место, если оно что-то добавляет.
     * <p>
     * Правило то же, что у {@code wdl --profile}: у записи файла имя и есть место,
     * и повторять его скобками незачем. Отличие одно — путь сокращён до имени файла:
     * столбец в окне узкий, а полный путь виден в подсказке и в переходе.
     */
    public String title() {
        if (line == 0 || file.isBlank() || name.equals(file)) {
            return name;
        }
        return name + " (" + fileName() + ":" + line + ")";
    }

    /** Имя файла без каталогов — в таблице путь целиком не нужен. */
    public String fileName() {
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return slash < 0 ? file : file.substring(slash + 1);
    }
}
