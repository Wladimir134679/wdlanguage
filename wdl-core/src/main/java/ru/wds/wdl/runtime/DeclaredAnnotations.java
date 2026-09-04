package ru.wds.wdl.runtime;

import ru.wds.wdl.value.Value;

import java.util.List;
import java.util.Map;

/**
 * Вычисленные аннотации одного объявления: свои и по параметрам.
 * <p>
 * Значения, а не дерево: аннотация считается <b>один раз</b>, при проходе объявления
 * и до применения первого декоратора, — иначе {@code @[deco] @{a: 1}} и
 * {@code @{a: 1} @[deco]} значили бы разное, а разницы в записи читатель не заметит.
 * <p>
 * Карта неизменяема, и это держит <b>тип</b>, а не договорённость. Отдать наружу
 * {@code MapValue} значило бы через неделю обнаружить в чужом скрипте
 * {@code f.annotations.clear()} — запись, которой в языке быть не должно; копия
 * для чтения собирается на каждое обращение в {@code members.Introspection}.
 * <p>
 * Пустое объявление отдаёт {@link #NONE} — одно значение на весь запуск. Функция
 * без аннотаций встречается на каждом шагу, и лишней карты на горячем пути
 * не создаётся.
 *
 * @param own    аннотации самого объявления
 * @param params аннотации параметров по позициям заголовка; пустой список, если
 *               не написано ни одной — хранить столько же пустых карт, сколько
 *               параметров, незачем
 */
record DeclaredAnnotations(Map<Value, Value> own, List<Map<Value, Value>> params) {

    static final DeclaredAnnotations NONE = new DeclaredAnnotations(Map.of(), List.of());

    /** Аннотации параметра по номеру; у ненаписанных — пустая карта, а не {@code null}. */
    Map<Value, Value> param(int index) {
        return index >= 0 && index < params.size() ? params.get(index) : Map.of();
    }

    boolean isEmpty() {
        return own.isEmpty() && params.isEmpty();
    }
}
