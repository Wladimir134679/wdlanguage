package ru.wds.wdl.metrics;

/**
 * Открытый замер: закрывается там, где стадия закончилась.
 * <p>
 * Наследует {@link AutoCloseable}, но {@code close()} переопределён без {@code throws}
 * — иначе замер вокруг лексера потребовал бы {@code catch} на исключение,
 * которого не бывает.
 * <p>
 * <b>Закрывается из {@code finally}, а не {@code try}-с-ресурсами</b>, и это не вкус:
 * тело такого {@code try} к самой переменной не обращается, а сборка идёт
 * с {@code -Xlint:all}, где это законное предупреждение о забытом ресурсе. Явный
 * {@code finally} читается не хуже:
 * <pre>{@code
 * Measure measure = metrics.begin(Stage.LEX, name);
 * try {
 *     tokens = Lexer.tokenize(source, diagnostics);
 * } finally {
 *     measure.close();
 * }
 * }</pre>
 * Из {@code finally} — затем, чтобы замер записался и у стадии, которая упала:
 * время до падения тоже ответ.
 */
public interface Measure extends AutoCloseable {

    @Override
    void close();
}
