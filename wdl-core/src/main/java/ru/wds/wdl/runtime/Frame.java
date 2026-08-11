package ru.wds.wdl.runtime;

import ru.wds.wdl.module.Unit;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Кадр вызова: какая функция выполняется и откуда её позвали.
 * <p>
 * Кадры связаны в цепочку ссылками на родителя и создаются там же, где уже создаётся
 * контекст вызова ({@link ExecutionContext#call}). Никакого «стека» как структуры
 * данных нет: цепочка разворачивается в список строк только тогда, когда ошибку
 * действительно бросили ({@link #trace}).
 * <p>
 * <b>Почему цепочка, а не изменяемый список в контексте.</b> Контекст неизменяем,
 * и на этом держится изоляция нескольких интерпретаторов в одном процессе. Изменяемый
 * стек пришлось бы либо делить между вложенными контекстами — и тогда функция,
 * вызванная из другого потока, писала бы в чужой, — либо копировать на каждый вызов.
 * Цепочка стоит одного маленького объекта на вызов и заодно заменяет собой счётчик
 * глубины: {@link #depth()} известна при создании кадра и не пересчитывается.
 * <p>
 * <b>Файл берётся у вызывающего, а не у функции.</b> {@link #callSite} — это место
 * в тексте <i>того, кто позвал</i>, и смещение в нём осмысленно только в его файле.
 * Функция из модуля, вызванная из главного скрипта, показывает в кадре строку главного
 * скрипта — там она и написана. Свой файл функция показывает в другом месте: в самой
 * ошибке, у которой место броска внутри тела ({@code WdlError.inSource}).
 * <p>
 * Не {@code record} ровно из-за {@link #depth()}: она выводится из родителя, считается
 * один раз при создании и не должна пересчитываться обходом цепочки — проверка глубины
 * идёт на каждом вызове.
 */
final class Frame {

    private final String function;
    private final Span callSite;
    private final Unit unit;
    private final Frame parent;
    private final int depth;

    private Frame(String function, Span callSite, Unit unit, Frame parent) {
        this.function = Objects.requireNonNull(function, "function");
        this.callSite = Objects.requireNonNull(callSite, "callSite");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.parent = parent;
        this.depth = parent == null ? 1 : parent.depth + 1;
    }

    /**
     * Кадр вызова функции.
     *
     * @param function имя функции, которую вызвали
     * @param callSite место вызова в тексте вызывающего
     * @param caller   файл вызывающего: ему принадлежит {@code callSite}
     * @param parent   кадр вызывающего или {@code null}, если звали из главного скрипта
     */
    static Frame of(String function, Span callSite, Unit caller, Frame parent) {
        return new Frame(function, callSite, caller, parent);
    }

    /** Сколько вызовов уже в работе, считая этот. */
    int depth() {
        return depth;
    }

    /**
     * Путь по скрипту от этого кадра наружу — по строке на вызов.
     * <p>
     * Разворачивается только при броске: до этого момента список никому не нужен,
     * а вызовов в горячем цикле бывают миллионы.
     */
    static List<String> trace(Frame innermost) {
        if (innermost == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(innermost.depth);
        for (Frame frame = innermost; frame != null; frame = frame.parent) {
            lines.add(frame.line());
        }
        return List.copyOf(lines);
    }

    /** {@code в parseNumber (config.wdl:12:24)} — или без места, если файла нет (REPL, eval). */
    private String line() {
        Source source = unit.source();
        if (source == null || callSite.isNone() || callSite.start() > source.length()) {
            return "в " + function;
        }
        return "в " + function + " (" + source.name() + ":" + source.positionOf(callSite.start()) + ")";
    }

    @Override
    public String toString() {
        return line();
    }
}
