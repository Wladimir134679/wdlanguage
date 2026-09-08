package ru.wds.wdl.profile;

import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Место в скрипте, время которого считается: что вызвали и где оно объявлено.
 * <p>
 * <b>Ключ профиля — объявление, а не значение.</b> Функция-литерал, вычисленная
 * в цикле, даёт новое значение на каждой итерации, но написана она один раз, и в
 * профиле обязана быть одной строкой. Отсюда состав ключа: вид, имя, файл объявления
 * и интервал объявления в нём — всё, что у двух значений одного литерала совпадает.
 * <p>
 * Исходник хранится ссылкой, а не именем, ровно затем, чтобы отчёт умел отвечать
 * строкой и столбцом ({@link #line()}): без него профиль показывал бы смещение
 * в символах, которое человеку ни о чём не говорит, а редактору всё равно нужно
 * пересчитывать. Ссылка живёт столько же, сколько запуск, — файл уже прочитан.
 * <p>
 * Сравнение исходников — по ссылке, как у всякого не-record: один файл читается
 * в запуске один раз, и два разных {@code Source} с одинаковым текстом — это
 * действительно два разных файла.
 *
 * @param kind   что вызвали
 * @param name   имя функции, класса или файла — так, как его видит автор скрипта
 * @param source файл объявления или {@code null}: у встроенной функции его нет,
 *               и придумывать ей место в тексте скрипта было бы неправдой
 * @param span   интервал объявления в этом файле; {@link Span#NONE}, когда файла нет
 */
public record CallSite(CallKind kind, String name, Source source, Span span) {

    public CallSite {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(span, "span");
    }

    /** Запись без места в тексте: встроенная функция, метод значения, класс от приложения. */
    public static CallSite of(CallKind kind, String name) {
        return new CallSite(kind, name, null, Span.NONE);
    }

    /** Запись с местом объявления: функция, класс или файл скрипта. */
    public static CallSite of(CallKind kind, String name, Source source, Span span) {
        return new CallSite(kind, name, source, span);
    }

    /** Имя файла объявления или пустая строка, если места нет. */
    public String file() {
        return source == null ? "" : source.name();
    }

    /**
     * Строка объявления, нумерация с единицы; {@code 0}, если места нет.
     * <p>
     * Считается по запросу, а не хранится полем: спрашивают её при выводе отчёта —
     * то есть один раз на строку таблицы, — а профиль по ходу запуска обходится
     * без неё вовсе.
     */
    public int line() {
        if (source == null || span.isNone() || span.start() > source.length()) {
            return 0;
        }
        return source.positionOf(span.start()).line();
    }

    /**
     * {@code total (script.wdl:12)} — или просто имя, если места объявления нет.
     * <p>
     * Файл сам себя местом не подписывает: у записи о верхнем уровне имя и есть путь,
     * и {@code script.wdl (script.wdl:1)} было бы шумом, а не уточнением.
     */
    public String title() {
        int line = line();
        if (line == 0 || name.equals(source.name())) {
            return name;
        }
        return name + " (" + source.name() + ":" + line + ")";
    }

    @Override
    public String toString() {
        return title();
    }
}
