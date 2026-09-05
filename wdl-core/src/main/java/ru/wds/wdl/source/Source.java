package ru.wds.wdl.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Исходный текст скрипта вместе с его именем и индексом строк.
 * <p>
 * Узлы AST и токены хранят только {@link Span} (смещения в символах) — компактно
 * и дёшево. Перевод смещения в «строка:столбец» для сообщений об ошибках делается
 * здесь, по требованию.
 * <p>
 * <b>Контракт позиций, на который опирается весь инструментарий.</b> Смещение —
 * индекс в {@link String}, то есть единица UTF-16 (см. {@link Span}). Строки
 * и столбцы {@link Position} нумеруются <b>с единицы</b>; перевод в нумерацию
 * с нуля, которой пользуется LSP, — дело адаптера, ядро о ней не знает.
 * Перевод обратим: {@code offsetOf(positionOf(o)) == o} для любого допустимого
 * смещения, включая смещение за последним символом.
 * <p>
 * Строку заканчивает {@code \n}; {@code \r} перед ним — обычный символ текста,
 * он входит в позиции, но {@link #lineText} его не отдаёт. Маркер кодировки (BOM)
 * — тоже обычный символ позиции 0: пропускает его {@link ru.wds.wdl.lexer.Lexer},
 * а не этот класс, поэтому редактор обязан лексить текст документа, а не байты файла.
 */
public final class Source {

    private final String name;
    private final String text;
    /** Смещение начала каждой строки; {@code lineStarts[0] == 0}. */
    private final int[] lineStarts;

    public Source(String name, String text) {
        this.name = Objects.requireNonNull(name, "name");
        this.text = Objects.requireNonNull(text, "text");
        this.lineStarts = computeLineStarts(text);
    }

    public static Source ofString(String text) {
        return new Source("<script>", text);
    }

    public static Source ofFile(Path path) throws IOException {
        return new Source(path.toString(), Files.readString(path, StandardCharsets.UTF_8));
    }

    public String name() {
        return name;
    }

    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    public int lineCount() {
        return lineStarts.length;
    }

    /**
     * Переводит смещение в позицию «строка:столбец», нумерация с единицы.
     *
     * @throws IndexOutOfBoundsException если смещение вне текста
     */
    public Position positionOf(int offset) {
        if (offset < 0 || offset > text.length()) {
            throw new IndexOutOfBoundsException("offset " + offset + " вне текста длины " + text.length());
        }
        int line = binarySearchLine(offset);
        return new Position(line + 1, offset - lineStarts[line] + 1);
    }

    /**
     * Переводит позицию «строка:столбец» обратно в смещение — то, чем редактор
     * отвечает на клик мышью.
     * <p>
     * Обратно к {@link #positionOf(int)} и в тех же единицах: столбец считается
     * в символах UTF-16 от начала строки, нумерация с единицы. Допустимый столбец
     * доходит до позиции <b>за</b> последним символом строки — там стоит курсор,
     * дописывающий строку, и именно эту позицию присылает редактор чаще всего.
     *
     * @throws IndexOutOfBoundsException если строки нет или столбец за её концом
     */
    public int offsetOf(int line, int column) {
        if (line < 1 || line > lineStarts.length) {
            throw new IndexOutOfBoundsException("строка " + line + " вне диапазона 1.." + lineStarts.length);
        }
        int start = lineStarts[line - 1];
        int end = (line < lineStarts.length) ? lineStarts[line] - 1 : text.length();
        int offset = start + column - 1;
        if (column < 1 || offset > end) {
            throw new IndexOutOfBoundsException("столбец " + column + " вне строки " + line
                    + " длиной " + (end - start));
        }
        return offset;
    }

    /** То же для собранной позиции: {@code offsetOf(positionOf(o)) == o}. */
    public int offsetOf(Position position) {
        Objects.requireNonNull(position, "position");
        return offsetOf(position.line(), position.column());
    }

    /** Текст строки без завершающего перевода строки. Нумерация с единицы. */
    public String lineText(int line) {
        if (line < 1 || line > lineStarts.length) {
            throw new IndexOutOfBoundsException("строка " + line + " вне диапазона 1.." + lineStarts.length);
        }
        int start = lineStarts[line - 1];
        int end = (line < lineStarts.length) ? lineStarts[line] : text.length();
        while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(start, end);
    }

    private int binarySearchLine(int offset) {
        int low = 0;
        int high = lineStarts.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private static int[] computeLineStarts(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        int[] starts = new int[lines];
        int index = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[index++] = i + 1;
            }
        }
        return starts;
    }

    @Override
    public String toString() {
        return "Source[" + name + ", " + text.length() + " символов]";
    }
}
