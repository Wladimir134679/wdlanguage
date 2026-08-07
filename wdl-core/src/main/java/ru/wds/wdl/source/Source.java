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
