package ru.wds.wdl.stdlib;

import ru.wds.wdl.interop.JavaBridge;
import ru.wds.wdl.interop.JavaClass;
import ru.wds.wdl.interop.JavaSchema;
import ru.wds.wdl.runtime.Environment;

/**
 * Класс {@code Random}: генератор случайных чисел, открытый скриптом через мост.
 * <p>
 * Раньше здесь стоял построитель на семьдесят строк лямбд, а сам генератор жил
 * в {@code NativeInstance.state()}. Теперь генератор — обычный Java-класс
 * ({@link ScriptRandom}), а здесь осталось только то, что и должно быть в описании:
 * <b>какие имена видит скрипт</b>.
 *
 * <h2>Почему схема, а не «всё public»</h2>
 * Открыто ровно четыре имени. {@code JavaSchema.all} открыла бы заодно
 * {@code equals}, {@code hashCode} и {@code toString} — имена, которые в языке
 * ничего не значат и только засоряют {@code Random.methods}. Схема здесь дешевле
 * фильтра: четыре строки против объяснений, почему у генератора есть {@code hashCode}.
 *
 * <h2>Один класс на запуск — как и раньше</h2>
 * Собирается из {@code installTo} и берётся из области, если уже там стоит
 * ({@link Types#in}): {@code std} и будущие модули должны отдавать <b>тот же</b>
 * класс, иначе {@code r is Random} врал бы. Заодно исчезла старая забота: даже
 * если классов окажется два, {@code is} у моста отвечает по живому Java-типу,
 * а не по ссылке на класс.
 */
final class Randoms {

    private Randoms() {
    }

    /** Класс {@code Random} этого запуска: тот, что уже в области, или новый. */
    static JavaClass in(Environment scope) {
        return Types.in(scope, "Random", JavaClass.class, Randoms::build);
    }

    private static JavaClass build() {
        JavaSchema schema = JavaSchema.of(ScriptRandom.class).as("Random")
                .method("next")
                // В Java метод не назовёшь 'int' — а в скрипте это лучшее имя.
                .methodAs("int", "nextInt")
                .method("pick")
                // Зерно не меняется после создания, поэтому свойство, а не метод:
                // ответ зависит только от объекта, каким его создали.
                .bean("seed")
                .build();

        return JavaBridge.open().expose(schema).build().classOf(ScriptRandom.class);
    }
}
