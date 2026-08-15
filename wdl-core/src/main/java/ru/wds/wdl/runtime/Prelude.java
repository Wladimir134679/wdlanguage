package ru.wds.wdl.runtime;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Иерархия классов ошибок, написанная на самом языке.
 * <p>
 * Файл {@code prelude.wdl} лежит рядом с этим классом и разбирается <b>один раз</b>
 * при его загрузке. Изменяемой статики здесь нет и правило ядра не нарушено: дерево
 * неизменяемо и от запуска не зависит — зависят от него значения классов, а они
 * создаются заново в каждой корневой области.
 * <p>
 * Классы в файле идут <b>сверху вниз по наследованию</b>: {@code Exception} раньше
 * {@code RuntimeError}, тот раньше своих потомков. Иначе и нельзя — объявление
 * связывается тогда, когда до него дошло выполнение, и родителя ищет среди значений,
 * уже стоящих в области.
 * <p>
 * Почему на wdl, а не построителем нативных классов: весь смысл {@code Exception}
 * в том, чтобы от него наследовались, а наследоваться от нативного класса скрипт
 * пока не умеет.
 */
final class Prelude {

    private static final String FILE = "prelude.wdl";

    private static final Program PROGRAM;

    static {
        Source source = read();
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        if (diagnostics.hasErrors()) {
            // Прелюдия — часть движка, а не скрипта пользователя: сюда можно попасть
            // только собрав ядро со сломанным ресурсом.
            throw new IllegalStateException("прелюдия не разбирается:\n" + diagnostics.renderAll());
        }
        PROGRAM = program;
    }

    private Prelude() {
    }

    /**
     * Объявляет типы прелюдии в области видимости запуска: иерархию ошибок и Closeable.
     * <p>
     * Область — та же корневая, куда {@link Builtins#installTo} кладёт {@code println}:
     * {@code Exception} — обычное имя обычной области, и скрипт вправе его перекрыть.
     * Движку это не мешает — он берёт классы из {@link PreludeTypes реестра запуска},
     * снятого сразу после этого вызова, а не поиском имени в области.
     */
    static void installTo(ExecutionContext context) {
        new Interpreter().run(PROGRAM, context);
    }

    private static Source read() {
        try (InputStream stream = Prelude.class.getResourceAsStream(FILE)) {
            if (stream == null) {
                throw new IllegalStateException("рядом с " + Prelude.class.getName()
                        + " нет ресурса " + FILE);
            }
            return new Source(FILE, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("не удалось прочитать " + FILE, failure);
        }
    }
}
