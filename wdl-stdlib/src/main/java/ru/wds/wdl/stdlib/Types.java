package ru.wds.wdl.stdlib;

import ru.wds.wdl.embed.NativeClass;
import ru.wds.wdl.runtime.Environment;

import java.util.function.Supplier;

/**
 * Классы библиотеки, общие для запуска: найти в области или собрать.
 * <p>
 * Нативный класс собирается на запуск — иначе запись скрипта в поля класса
 * ({@code File.mark = 1}) пережила бы свой скрипт и досталась следующему. Но внутри
 * одного запуска класс обязан быть <b>одним</b>: {@code std} кладёт {@code File}
 * в корень, {@code sys.io} отдаёт его же, и {@code f is File} должно быть правдой,
 * откуда бы {@code File} ни взяли.
 * <p>
 * Отсюда способ: библиотека спрашивает класс у области, в которую устанавливается.
 * Область модуля стоит на корне запуска, поэтому {@code sys.io} видит то, что положил
 * туда {@code std}, — тот же приём, которым {@link Streams} берёт трейт {@code Closeable}
 * из прелюдии. Не нашлось — собирается свой, и это верно: приложение, давшее скрипту
 * только {@code sys.io}, получит рабочий {@code File} без {@code std}.
 */
final class Types {

    private Types() {
    }

    static NativeClass in(Environment scope, String name, Supplier<NativeClass> build) {
        return scope.lookup(name) instanceof NativeClass declared && declared.name().equals(name)
                ? declared
                : build.get();
    }
}
