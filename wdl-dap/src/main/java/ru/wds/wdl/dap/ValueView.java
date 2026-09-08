package ru.wds.wdl.dap;

import ru.wds.wdl.value.Value;
import ru.wds.wdl.value.types.ArrayValue;
import ru.wds.wdl.value.types.InstanceObjectValue;
import ru.wds.wdl.value.types.MapValue;
import ru.wds.wdl.value.types.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Как значение выглядит в панели переменных.
 * <p>
 * Три ответа на значение: короткая строка, имя типа и дети. И одно правило на все три:
 * <b>кода скрипта здесь не выполняется</b>. За именем свойства стоит вызов
 * ({@code docs/members.md}), и раскрывать свойства при построении панели значило бы
 * выполнять скрипт на каждом шаге отладчика — с его точками останова, его ошибками
 * и его временем. Поэтому в панель попадают только элементы массива и поля объекта:
 * то, что уже лежит в памяти.
 */
final class ValueView {

    /**
     * Докуда обрезается строка в панели.
     * <p>
     * Панель — одна строка на значение, и текст файла, прочитанный целиком в
     * переменную, не должен уехать в протокол мегабайтом: смотреть его так всё равно
     * нельзя. Полное значение достаётся вычислением в кадре.
     */
    private static final int MAX_LENGTH = 512;

    private ValueView() {
    }

    /**
     * Значение одной строкой.
     * <p>
     * Строки — в кавычках, в отличие от {@code display()}: в панели переменных
     * {@code "10"} и {@code 10} обязаны выглядеть по-разному, иначе строку не отличить
     * от числа. Всё остальное показывается так же, как его печатает {@code println}, —
     * второго представления значений в языке нет.
     */
    static String summary(Value value) {
        if (value == null) {
            return "null";
        }
        String text = value instanceof StringValue string
                ? "\"" + string.display() + "\""
                : value.display();
        return text.length() <= MAX_LENGTH
                ? text
                : text.substring(0, MAX_LENGTH) + "… (" + text.length() + " символов)";
    }

    /**
     * Имя типа для колонки «тип».
     * <p>
     * У экземпляра это имя его класса, а не слово «объект»: {@code Point} говорит
     * читателю то, чего {@code object} не говорит.
     */
    static String type(Value value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof InstanceObjectValue instance) {
            return instance.owner().name();
        }
        return value.type().id();
    }

    /**
     * Дети значения: элементы массива по индексам, поля объекта по ключам.
     * <p>
     * Порядок — тот, в котором они лежат в самом значении: у массива это порядок
     * элементов, у объекта — порядок, в котором ключи заводили. Пересортировать
     * по алфавиту значило бы спрятать от читателя то, что он сам построил.
     */
    static List<Child> children(Value value) {
        if (value instanceof ArrayValue array) {
            List<Child> children = new ArrayList<>(array.size());
            for (int index = 0; index < array.size(); index++) {
                children.add(new Child("[" + index + "]", array.get(index)));
            }
            return children;
        }
        if (value instanceof MapValue object) {
            List<Child> children = new ArrayList<>(object.size());
            for (Map.Entry<Value, Value> entry : object.entries().entrySet()) {
                children.add(new Child(entry.getKey().display(), entry.getValue()));
            }
            return children;
        }
        return List.of();
    }

    /** Сколько детей — для {@code namedVariables} и {@code indexedVariables}. */
    static int childCount(Value value) {
        if (value instanceof ArrayValue array) {
            return array.size();
        }
        if (value instanceof MapValue object) {
            return object.size();
        }
        return 0;
    }

    /** Индексируется ли значение числом: массив рисуется клиентом иначе, чем объект. */
    static boolean indexed(Value value) {
        return value instanceof ArrayValue;
    }

    /** Ребёнок значения: имя в панели и само значение. */
    record Child(String name, Value value) {
    }
}
