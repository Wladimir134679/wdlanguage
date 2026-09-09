package ru.wds.wdl.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Снятие отступа у многострочной строки.
 * <p>
 * Отдельно от лексера, потому что это чистое преобразование списка строк, и его
 * удобно проверять само по себе: правило отступа — единственное место фичи, где
 * легко ошибиться незаметно. Состояния здесь нет вовсе, как в {@code ast.Nodes},
 * поэтому годится любому потоку.
 * <p>
 * <b>Правило взято у Java без изменений</b> (JLS 3.10.6), и это осознанно: оно
 * специфицировано, предсказуемо и уже знакомо. Отступ считается по непустым строкам
 * и по последней строке — той, на которой стоит закрывающий разделитель; поэтому
 * сдвиг закрывающих кавычек вправо сдвигает весь текст, а не только их строку.
 * <p>
 * Счёт и снятие разведены на два шага не ради красоты: у строки с подстановкой
 * отступ мерят одни строки (текстовые куски), а снимают со всех — выражение,
 * записанное внутри {@code ${...}} «в столбик», не должно сдвигать весь блок.
 */
final class TextBlocks {

    private TextBlocks() {
    }

    /**
     * Одна строка содержимого: текст без перевода строки и смещение её начала
     * в исходнике.
     * <p>
     * Смещение хранится потому, что escape-последовательности разворачиваются
     * <b>после</b> снятия отступа, а диагностика про них обязана показывать точное
     * место в файле. Текст строки — всегда подстрока исходника, так что символ
     * {@code i} лежит в файле по смещению {@code offset + i}.
     */
    record Line(String text, int offset) {

        Line {
            Objects.requireNonNull(text, "text");
        }

        boolean isBlank() {
            return text.isBlank();
        }

        int end() {
            return offset + text.length();
        }
    }

    /**
     * Общий отступ: минимум по непустым строкам, а последняя учитывается всегда.
     * <p>
     * Последняя — это строка, на которой стоит закрывающий разделитель. Обычно она
     * состоит из одних пробелов, и её «отступ» — это позиция самих кавычек: так автор
     * и задаёт левый край текста, не трогая ни одной содержательной строки.
     */
    static int indent(List<Line> lines) {
        return indent(lines, true);
    }

    /**
     * То же, но со сказанным явно: последняя строка списка — та, на которой стоит
     * закрывающий разделитель.
     * <p>
     * У строки с подстановкой это не всегда так: последний кусок текста может
     * начинаться после {@code ${...}} прямо посреди строки, и тогда её отступ
     * принадлежит не ей. Считать его левым краем значило бы дать подстановке,
     * записанной в начале строки, сдвигать весь текст.
     */
    static int indent(List<Line> lines, boolean lastIsClosing) {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            boolean closing = lastIsClosing && i == lines.size() - 1;
            if (!closing && line.isBlank()) {
                continue;
            }
            min = Math.min(min, indentOf(line.text()));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /**
     * Снимает отступ со всех строк и срезает хвостовые пробелы.
     * <p>
     * Хвост режется до разворота escape — поэтому {@code \s} в конце строки и работает
     * как «пробел, который здесь нужен»: на момент среза это ещё не пробел.
     */
    static List<Line> strip(List<Line> lines, int indent) {
        List<Line> result = new ArrayList<>(lines.size());
        for (Line line : lines) {
            result.add(strip(line, indent, true, true));
        }
        return result;
    }

    /**
     * Снимает отступ и хвост с одной строки — по отдельности, потому что у строки
     * с подстановкой они снимаются не всегда вместе.
     * <p>
     * Отступ есть только у строки, которая в блоке началась: кусок текста после
     * {@code ${...}} продолжает чужую строку, и слева у него резать нечего. Хвост же
     * не режется у строки, которая кончилась не переводом строки, а подстановкой:
     * пробел перед {@code ${} написан осознанно и стоит в середине текста.
     */
    static Line strip(Line line, int indent, boolean cutIndent, boolean cutTrailing) {
        int cut = cutIndent ? Math.min(indent, line.text().length()) : 0;
        String value = line.text().substring(cut);
        return new Line(cutTrailing ? stripTrailing(value) : value, line.offset() + cut);
    }

    /**
     * Есть ли в отступах блока и табуляции, и пробелы.
     * <p>
     * Отступ считается в символах, и таб здесь весит ровно единицу — как в Java.
     * Смешать два вида отступа значит получить сдвиг, которого не видно в редакторе,
     * поэтому о смеси говорится предупреждением.
     */
    static boolean mixedIndentation(List<Line> lines) {
        boolean spaces = false;
        boolean tabs = false;
        for (Line line : lines) {
            String value = line.text();
            for (int i = 0; i < value.length() && isIndent(value.charAt(i)); i++) {
                spaces |= value.charAt(i) == ' ';
                tabs |= value.charAt(i) == '\t';
            }
        }
        return spaces && tabs;
    }

    /** Отступ строки; у строки из одних пробелов — вся её длина. */
    private static int indentOf(String value) {
        int i = 0;
        while (i < value.length() && isIndent(value.charAt(i))) {
            i++;
        }
        return i;
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && isIndent(value.charAt(end - 1))) {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    private static boolean isIndent(char current) {
        return current == ' ' || current == '\t';
    }
}
