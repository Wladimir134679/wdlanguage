package ru.wds.wdl.module;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.source.Source;

import java.util.Objects;

/**
 * Единица трансляции: один разобранный файл со всем, что о нём известно.
 * <p>
 * Пока файл был один, юнит был не нужен — исходник знал вызывающий, а формы классов
 * лежали прямо в контексте выполнения. С появлением {@code import} файлов стало много,
 * и три вещи перестали быть свойствами запуска, став свойствами файла:
 * <ul>
 *   <li><b>исходник</b> — по нему рисуется строка с подчёркиванием в сообщении об
 *       ошибке. Ошибка в теле функции модуля, отрендеренная по главному файлу,
 *       показала бы чужую строку и ничего не сказала бы об этом;</li>
 *   <li><b>формы классов и трейтов</b> — резолвер работает по одной программе, и
 *       функция из модуля, объявляющая класс в своём теле, обязана видеть формы
 *       своего файла, а не того, откуда её позвали;</li>
 *   <li><b>{@link #key() ключ модуля}</b> — по нему считаются относительные пути его
 *       импортов и по нему же интерпретатор узнаёт, что форма класса пришла из чужого
 *       файла и значение надо брать у модуля, а не строить своё.</li>
 * </ul>
 * Юнит запоминают функция и класс — там же, где запоминают замыкание: вызов возвращает
 * выполнение в тот файл, где код написан.
 *
 * @param source     исходник файла; {@code null}, если программа пришла не из файла
 *                   (REPL, {@code eval} строки) — тогда ошибки рендерятся как раньше
 * @param program    дерево файла; {@code null} у юнита-заглушки
 * @param resolution формы классов и трейтов этого файла
 * @param key        ключ модуля или {@code null} у главного скрипта — он не модуль
 *                   и импортируется ниоткуда
 */
public record Unit(Source source, Program program, Resolution resolution, String key) {

    /** Юнит контекста, который ещё не знает, какую программу выполняет. */
    private static final Unit NONE = new Unit(null, null, Resolution.none(), null);

    public Unit {
        Objects.requireNonNull(resolution, "resolution");
    }

    public static Unit none() {
        return NONE;
    }

    /** Юнит главного скрипта: корень для относительных путей импорта. */
    public static Unit of(Source source, Program program, Resolution resolution) {
        return new Unit(source, program, resolution, null);
    }

    /** Юнит программы без исходника: REPL и тесты, собирающие дерево из строки. */
    public static Unit anonymous(Program program, Resolution resolution) {
        return new Unit(null, program, resolution, null);
    }

    /** Каталог файла: от него считаются пути его импортов. */
    public String home() {
        return ModuleKey.homeOf(key);
    }

    /** Тот же юнит с другими формами — {@code Interpreter.run(program, resolution, context)}. */
    public Unit withResolution(Resolution newResolution) {
        return new Unit(source, program, newResolution, key);
    }
}
