package ru.wds.wdl.runtime;

import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;

import java.util.EnumMap;
import java.util.Map;

/**
 * Классы ошибок этого запуска: {@link ErrorKind} → значение класса из
 * {@linkplain Prelude прелюдии}.
 * <p>
 * Реестр нужен по той же причине, что {@code Modules} и {@code Linker}, — он
 * принадлежит запуску, а не области видимости. Классы прелюдии лежат в корневой
 * области рядом с {@code println}, и скрипт вправе их перекрыть: пространство имён
 * одно, и исключений из этого правила язык не заводит. Но <b>движок</b> берёт класс
 * отсюда, а не поиском имени, поэтому {@code IndexError = 5} ломает только тот скрипт,
 * который это сделал, а не сам механизм ошибок.
 * <p>
 * Снимок делается один раз, сразу после выполнения прелюдии, — до первой строки
 * пользовательского кода.
 */
final class Exceptions {

    private final Map<ErrorKind, ClassValue> classes = new EnumMap<>(ErrorKind.class);

    /** Запоминает классы прелюдии, объявленные в этой области. */
    void captureFrom(Environment scope) {
        for (ErrorKind kind : ErrorKind.values()) {
            if (scope.lookup(kind.title()) instanceof ClassValue declared) {
                classes.put(kind, declared);
            }
        }
    }

    /** Класс ошибки движка или {@code null}, если прелюдия в этом запуске не выполнялась. */
    ClassValue classOf(ErrorKind kind) {
        return classes.get(kind);
    }

    /**
     * Отвечает на {@code catch (e is Тип)} для ошибки движка — <b>не создавая</b>
     * её значения: проверка идёт по классу из реестра, то есть сравнением форм.
     * <p>
     * Запасной путь — сравнение имён по цепочке предков {@link ErrorKind}. Он нужен
     * там, где прелюдии не было вовсе: функция wdl, вызванная приложением через свой
     * {@code CallContext}, начинает вычисление вне какого-либо запуска. Ловить ошибки
     * это позволяет, различать одноимённый класс скрипта — уже нет, поэтому обычным
     * путём остаётся реестр.
     */
    boolean matches(ErrorKind kind, Value target) {
        ClassValue declared = classes.get(kind);
        if (declared != null) {
            return declared.conformsTo(target);
        }
        String name = nameOf(target);
        for (ErrorKind current = kind; current != null; current = current.parent()) {
            if (current.title().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String nameOf(Value target) {
        return switch (target) {
            case ClassValue declared -> declared.name();
            case TraitValue trait -> trait.name();
            default -> null;
        };
    }
}
