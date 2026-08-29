package ru.wds.wdl.runtime;

import ru.wds.wdl.value.ClassValue;
import ru.wds.wdl.value.TraitValue;
import ru.wds.wdl.value.Value;

import java.util.EnumMap;
import java.util.Map;

/**
 * Типы {@linkplain Prelude прелюдии} этого запуска: классы ошибок и трейт
 * {@code Closeable}.
 * <p>
 * Реестр нужен по той же причине, что {@code Modules} и {@code Linker}, — он
 * принадлежит запуску, а не области видимости. Типы прелюдии лежат в корневой
 * области рядом с {@code println}, и скрипт вправе их перекрыть: пространство имён
 * одно, и исключений из этого правила язык не заводит. Но <b>движок</b> берёт их
 * отсюда, а не поиском имени, поэтому {@code IndexError = 5} ломает только тот скрипт,
 * который это сделал, а не сам механизм ошибок.
 * <p>
 * Снимок делается один раз, сразу после выполнения прелюдии, — до первой строки
 * пользовательского кода.
 *
 * <h2>Потоки</h2>
 * Реестр заполняется один раз и дальше только читается — из любого потока, какой
 * позовёт функцию запуска. Поэтому карта не правится на месте, а <b>заменяется целиком</b>
 * записью в {@code volatile}: собранный экземпляр становится видимым чужому потоку
 * вместе со всем содержимым, а не наполовину. Замок здесь был бы платой за каждый
 * {@code catch} ради одной записи за весь запуск.
 */
public final class PreludeTypes {

    private static final String CLOSEABLE = "Closeable";

    private volatile Map<ErrorKind, ClassValue> errors = Map.of();
    private volatile TraitValue closeable;

    /** Запоминает типы прелюдии, объявленные в этой области. */
    void captureFrom(Environment scope) {
        Map<ErrorKind, ClassValue> captured = new EnumMap<>(ErrorKind.class);
        for (ErrorKind kind : ErrorKind.values()) {
            if (scope.lookup(kind.title()) instanceof ClassValue declared) {
                captured.put(kind, declared);
            }
        }
        // Одной записью, когда карта уже собрана: до этой строки чужой поток видит
        // прежний снимок целиком, после — новый целиком.
        this.errors = captured;
        if (scope.lookup(CLOSEABLE) instanceof TraitValue declared) {
            closeable = declared;
        }
    }

    /** Класс ошибки движка или {@code null}, если прелюдия в этом запуске не выполнялась. */
    ClassValue classOf(ErrorKind kind) {
        return errors.get(kind);
    }

    /** Трейт {@code Closeable} или {@code null}, если прелюдии не было. */
    TraitValue closeable() {
        return closeable;
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
        ClassValue declared = errors.get(kind);
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
