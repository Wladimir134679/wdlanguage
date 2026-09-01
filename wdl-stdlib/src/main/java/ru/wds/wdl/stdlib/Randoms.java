package ru.wds.wdl.stdlib;

import ru.wds.wdl.bridge.reflect.JavaBridge;
import ru.wds.wdl.bridge.NativeClass;
import ru.wds.wdl.bridge.reflect.FromJava;

/**
 * Класс {@code Random}: генератор случайных чисел, открытый скриптом через мост.
 * <p>
 * Раньше здесь стоял построитель на семьдесят строк лямбд, а сам генератор жил
 * в {@code NativeInstance.state()}. Теперь генератор — обычный Java-класс
 * ({@link ScriptRandom}), а здесь осталось только то, что и должно быть в описании:
 * <b>какие имена видит скрипт</b>.
 *
 * <h2>Почему схема, а не «всё public»</h2>
 * Открыто ровно четыре имени. {@code FromJava.all()} открыло бы заодно
 * {@code equals}, {@code hashCode} и {@code toString} — имена, которые в языке
 * ничего не значат и только засоряют {@code Random.methods}. Схема здесь дешевле
 * фильтра: четыре строки против объяснений, почему у генератора есть {@code hashCode}.
 *
 * <h2>Один класс на запуск — как и раньше</h2>
 * Собирается из {@code installTo} и берётся из области, если уже там стоит
 * ({@link ru.wds.wdl.bridge.Module}): {@code std} и будущие модули должны отдавать <b>тот же</b>
 * класс, иначе {@code r is Random} врал бы. Заодно исчезла старая забота: даже
 * если классов окажется два, {@code is} у моста отвечает по живому Java-типу,
 * а не по ссылке на класс.
 */
final class Randoms {

    private Randoms() {
    }

    /** Класс {@code Random}: собирается на запуск, ставится модулем. */
    static NativeClass build() {
        return JavaBridge.open()
                .expose(FromJava.of(ScriptRandom.class).as("Random")
                        .method("next")
                        // В Java метод не назовёшь 'int' — а в скрипте это лучшее имя.
                        .methodAs("int", "nextInt")
                        .method("pick")
                        // Зерно не меняется после создания, поэтому свойство, а не метод:
                        // ответ зависит только от объекта, каким его создали.
                        .bean("seed"))
                .build()
                .classOf(ScriptRandom.class);
    }
}
