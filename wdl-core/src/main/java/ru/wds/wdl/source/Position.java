package ru.wds.wdl.source;

/**
 * Позиция «строка:столбец» для показа человеку. Нумерация с единицы.
 * Внутри компилятора используются смещения ({@link Span}), сюда переводим только
 * при выводе диагностики.
 */
public record Position(int line, int column) {

    public Position {
        if (line < 1 || column < 1) {
            throw new IllegalArgumentException("нумерация строк и столбцов начинается с 1: " + line + ":" + column);
        }
    }

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
