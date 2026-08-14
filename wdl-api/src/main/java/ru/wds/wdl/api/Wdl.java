package ru.wds.wdl.api;

import ru.wds.wdl.runtime.Output;

import java.nio.file.Path;

/**
 * Самый короткий способ выполнить скрипт — на один раз, без сборки движка.
 * <pre>{@code
 * Object answer = Wdl.eval("40 + 2");                  // 42
 * Wdl.run(Path.of("report.wdl"), System.out::print);   // с выводом в консоль
 * }</pre>
 * Всё здесь — обёртки над {@link WdlEngine}, и никакой своей логики у них нет. Как только
 * понадобится второй запуск того же скрипта, своя библиотека, свой набор модулей или
 * доступ к тому, что скрипт объявил, — путь отсюда один: собрать движок явно.
 * <p>
 * Состав по умолчанию — {@link Stdlib#SAFE}: считать и разбирать данные скрипт умеет,
 * читать файлы и ходить в сеть — нет. Выбор для короткой формы намеренно осторожный:
 * то, что пишется одной строкой, чаще всего и запускает что-то пришедшее со стороны.
 */
public final class Wdl {

    private Wdl() {
    }

    /**
     * Вычисляет <b>выражение</b> и отдаёт обычный Java-объект: {@code Long},
     * {@code String}, {@code List}, {@code Map}.
     * <pre>{@code
     * Wdl.eval("40 + 2")                    // 42
     * Wdl.eval("[1, 2, 3]")                 // List.of(1L, 2L, 3L)
     * }</pre>
     * Выражение, а не скрипт: {@code 40 + 2} целой инструкцией в языке не бывает —
     * см. {@link WdlEngine#eval}. Для скрипта есть {@link #run}.
     */
    public static Object eval(String expression) {
        return Values.toJava(engine(Output.discarding()).eval(expression));
    }

    /** Выполняет текст скрипта, ничего не печатая. */
    public static Object run(String code) {
        try (WdlInstance instance = engine(Output.discarding()).compile(code).instance()) {
            return Values.toJava(instance.execute());
        }
    }

    /** Выполняет текст скрипта, направляя его вывод куда сказано. */
    public static Object run(String code, Output output) {
        try (WdlInstance instance = engine(output).compile(code).instance()) {
            return Values.toJava(instance.execute());
        }
    }

    /**
     * Выполняет файл. Модули ищутся рядом с ним.
     * <p>
     * Стандартная библиотека здесь полная: свой файл, лежащий рядом с приложением, —
     * это как раз тот скрипт, которому доверяют.
     */
    public static Object run(Path file, Output output) {
        WdlEngine engine = WdlEngine.builder()
                .stdlib(Stdlib.STANDARD)
                .output(output)
                .build();
        try (WdlInstance instance = engine.compile(file).instance()) {
            return Values.toJava(instance.execute());
        }
    }

    private static WdlEngine engine(Output output) {
        return WdlEngine.builder().stdlib(Stdlib.SAFE).output(output).build();
    }
}
