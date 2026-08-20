package ru.wds.wdl.value;

import ru.wds.wdl.value.types.BoolValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.NullValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.Objects;

/**
 * Метаданные цели декоратора: то, что приходит декоратору нулевым аргументом.
 * <pre>
 * def timer(meta, message = "") {
 *     return def (*args, **named) => meta.target(*args, **named)
 * }
 * </pre>
 * <p>
 * <b>Обычная карта, а не особый тип значения.</b> Всё, что декоратор с ней делает, —
 * читает три ключа; заводить ради этого {@code ValueType} значило бы добавить ветку
 * в каждый {@code switch} по типам ради структуры без единого собственного поведения.
 * <p>
 * <b>Источник один.</b> Ту же карту строит и интерпретатор, когда применяет {@code @[...]},
 * и модуль {@code sys.meta}, когда декоратор зовут руками:
 * {@code reg(m.of(app))} обязано быть тем же самым, что {@code @[reg] def app()}.
 * Иначе декоратор нельзя было бы протестировать, не воспроизводя движок, — а декоратор
 * в этом языке должен оставаться обычной функцией.
 * <p>
 * <b>Типа цели здесь нет.</b> Он спрашивается у самой цели встроенной {@code typeof}:
 * {@code typeof(meta.target)} даёт {@code function} или {@code class}. Два источника
 * одного факта рано или поздно разошлись бы.
 */
public final class DecoratorMeta {

    /** Сама цель: функция, класс или трейт. */
    public static final String TARGET = "target";
    /**
     * Имя, под которым цель известна, или {@code null}.
     * <p>
     * У объявления это его имя. У анонимной функции — имя переменной, в которую её
     * положили: {@code f = def(a) => a} даёт {@code "f"}, потому что разбор подставляет
     * имя цели простого присваивания. Если и его нет — {@code m.of(def(x) => x)} прямо
     * в вызове, обёртка, возвращённая другим декоратором, — здесь {@code null},
     * а не выдуманное {@code "def"}: под таким ключом регистрировать нельзя.
     */
    public static final String NAME = "name";
    /**
     * Записана ли цель без имени.
     * <p>
     * Не то же самое, что «{@link #NAME} пустое»: у {@code f = def(a) => a} имя есть,
     * а функция анонимна. Поэтому фактов два, а не один.
     */
    public static final String IS_ANONYMOUS = "isAnonymous";

    private DecoratorMeta() {
    }

    /**
     * Собирает метаданные по значению.
     * <p>
     * Годится любое значение, а не только объявляемое: {@code sys.meta} зовут из скрипта,
     * и запрещать там число значило бы завести проверку, которой при декорировании
     * не бывает. Имени у числа нет — в {@code name} тогда ляжет {@code null}.
     */
    public static MapValue of(Value target) {
        Objects.requireNonNull(target, "target");
        MapValue meta = new MapValue();
        meta.put(TARGET, target);
        String name = nameOf(target);
        meta.put(NAME, name == null ? NullValue.NULL : StringValue.of(name));
        meta.put(IS_ANONYMOUS, BoolValue.of(anonymous(target)));
        return meta;
    }

    /**
     * Имя цели или {@code null}, если у значения его нет.
     * <p>
     * У функции спрашивается {@code knownName()}, а не {@code name()}: второй обязан
     * вернуть строку для сообщения об ошибке и подставляет {@code "def"} там, где имени
     * нет. Подстановка для человека — не имя, и в карту ей нельзя.
     */
    private static String nameOf(Value target) {
        return switch (target) {
            case FunctionValue function -> function.knownName();
            case ClassValue declared -> declared.name();
            case TraitValue trait -> trait.name();
            default -> null;
        };
    }

    /**
     * Объявлена ли цель без имени. У класса и трейта такой формы нет вовсе:
     * {@code class} без имени не разберётся.
     */
    private static boolean anonymous(Value target) {
        return target instanceof FunctionValue function && function.anonymous();
    }
}
