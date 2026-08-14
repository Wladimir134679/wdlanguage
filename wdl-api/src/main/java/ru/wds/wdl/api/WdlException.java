package ru.wds.wdl.api;

import ru.wds.wdl.diagnostic.Diagnostic;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.runtime.WdlError;
import ru.wds.wdl.source.Source;

import java.util.List;

/**
 * Скрипт не отработал: не разобрался или упал по дороге.
 * <p>
 * Одно исключение на обе беды намеренно. Приложению, встроившему движок, разница между
 * «ошибка разбора» и «ошибка выполнения» интересна редко, а нужно ему всегда одно и то же:
 * <b>показать человеку, что не так, с местом в его тексте</b>. Поэтому в {@link #getMessage()}
 * лежит не «NullPointerException в строке 412 интерпретатора», а готовый к печати кусок
 * исходника с подчёркиванием и путь по скрипту — то же самое, что печатает консольный
 * {@code wdl}.
 * <pre>
 * ошибка: переменная 'total' не определена
 *   3 | println(total)
 *     |         ^^^^^
 *   в greet (script.wdl:3)
 *   в main (script.wdl:9)
 * </pre>
 * Кому нужны подробности — {@link #diagnostics()} отдаёт разбор по одной диагностике,
 * а {@link #cause()} у ошибки выполнения — исходный {@link WdlError} с местом, классом
 * ошибки и трассировкой.
 */
public class WdlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Diagnostic> diagnostics;

    WdlException(String report, List<Diagnostic> diagnostics, Throwable cause) {
        super(report, cause);
        this.diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Ошибки разбора: их бывает много, и показывать надо все.
     * <p>
     * Лексер и парсер не останавливаются на первой — за один проход человек узнаёт
     * обо всех своих опечатках, а не о самой верхней.
     */
    static WdlException syntax(Diagnostics diagnostics) {
        return new WdlException(diagnostics.renderAll().stripTrailing(), diagnostics.all(), null);
    }

    /** Ошибка выполнения: одна, зато с путём по скрипту. */
    static WdlException runtime(WdlError error, Source fallback) {
        // Место у ошибки есть, а какому файлу оно принадлежит, знает она сама: с импортом
        // файлов много, и смещение в каждом указывает на своё. Запасной вариант — исходник
        // главного скрипта: ошибка могла случиться до входа в модуль.
        Source failed = error.source() != null ? error.source() : fallback;
        Diagnostic diagnostic = error.toDiagnostic();
        StringBuilder report = new StringBuilder(256);
        report.append(new Diagnostics(failed).render(diagnostic).stripTrailing());
        for (String frame : error.trace()) {
            report.append(System.lineSeparator()).append("  ").append(frame);
        }
        return new WdlException(report.toString(), List.of(diagnostic), error);
    }

    /**
     * Диагностики поодиночке: место, уровень и текст.
     * <p>
     * Нужны тому, кто показывает ошибки сам, — редактору, веб-форме, логу со своей
     * разметкой. Кому достаточно готового текста, тот берёт {@link #getMessage()}.
     */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
