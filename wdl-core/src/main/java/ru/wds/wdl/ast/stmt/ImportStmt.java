package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.source.Span;

import java.util.Objects;

/**
 * Импорт модуля: {@code import lib.math} или {@code import lib.math as m}.
 * <p>
 * Две формы записи — две разные операции, и разница в одном слове {@code as}:
 * <ul>
 *   <li><b>развёрнутый</b> ({@code alias == null}) — имена модуля заводятся в текущей
 *       области, как будто их объявили здесь;</li>
 *   <li><b>именованный</b> — в текущей области заводится одно имя, значение-модуль,
 *       а до содержимого добираются обращением {@code m.PI}. Особого синтаксиса
 *       для этого не нужно: обращение в языке одно на все виды контейнеров.</li>
 * </ul>
 * Импорт — инструкция, а не объявление верхнего уровня, и стоять может где угодно,
 * включая тело функции. Поэтому и область его действия обычная: та, в которой он
 * написан, и не шире.
 * <p>
 * <b>Путь хранится тем же текстом, что и написан.</b> {@code import a.b} и
 * {@code import "a/b.wdl"} — записи одного модуля, но приводит их к одному ключу
 * загрузчик, а не парсер: разрешение относительного пути зависит от того, какой файл
 * выполняется, и во время разбора этого ещё не знают. Точечная запись превращается
 * в путь с {@code /} прямо здесь — только потому, что иначе дерево хранило бы форму
 * записи, а не путь.
 *
 * @param path      путь модуля так, как его написали: {@code "lib/math"}, {@code "math.wdl"},
 *                  {@code "/math"}. Ведущий {@code /} означает «от корня запуска»
 * @param pathSpan  место пути в исходнике: им подчёркивается ненайденный модуль
 * @param alias     имя, под которым модуль ляжет в область видимости, или {@code null}
 *                  для развёрнутого импорта
 * @param aliasSpan место имени или {@link Span#NONE}, если имени нет
 * @param span      место в исходнике от {@code import} до конца инструкции
 */
public record ImportStmt(String path, Span pathSpan, String alias, Span aliasSpan, Span span)
        implements Stmt {

    public ImportStmt {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(pathSpan, "pathSpan");
        Objects.requireNonNull(aliasSpan, "aliasSpan");
        Objects.requireNonNull(span, "span");
    }

    /** Именованный ли это импорт: {@code import m as x}. */
    public boolean hasAlias() {
        return alias != null;
    }

    @Override
    public String toString() {
        return "import \"" + path + "\"" + (alias == null ? "" : " as " + alias);
    }
}
