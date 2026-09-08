package ru.wds.wdl.debug;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.ErrorStmt;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.runtime.ExecutionContext;
import ru.wds.wdl.runtime.Interpreter;
import ru.wds.wdl.source.Source;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полнота точки съёма: <b>останов срабатывает на инструкции любого вида</b>.
 * <p>
 * Тест защищает единственный инвариант, ради которого в интерпретаторе заведён
 * {@code step}: инструкции выполняются из полутора десятков мест, и точка останова,
 * поставленная в одном из них, но забытая в другом, даёт отладчик, который «иногда
 * не срабатывает». Глазами такое не проверить — а так проверяется само собой:
 * скрипт ниже содержит каждый вид инструкции, точки ставятся на все места разом,
 * и виды, на которых движок остановился, сравниваются с видами, которые есть
 * в дереве.
 * <p>
 * Отсюда и его главное свойство: <b>новый вид инструкции ломает этот тест сам</b>,
 * если про него забыли в {@code step}. Скрипт, правда, придётся дописать — но это
 * та же работа, что дописать посетителя, и компилятор о ней всё равно напомнит.
 */
class StepCoverageTest {

    /**
     * Скрипт со всеми видами инструкций, и все они действительно выполняются:
     * ветка {@code else} — с обеих сторон, {@code catch} — по настоящей ошибке,
     * {@code continue} и {@code break} — по достижимым условиям.
     */
    private static final String CODE = """
            const LIMIT = 3

            trait Named {
                def title()
            }

            class Handle(name) with Closeable, Named {
                def title() => name
                def close() { }
            }

            extend Array {
                def second() => this[1]
            }

            def mark(meta) => meta.target

            @[mark]
            def twice(value) {
                return value * 2;
            }

            def classify(value) {
                if (value > 0) {
                    return "plus";
                } else {
                    return "minus";
                }
            }

            def sumTo(bound) {
                total = 0
                index = 0
                while (true) {
                    index = index + 1
                    if (index > bound) {
                        break;
                    }
                    if (index == 2) {
                        continue;
                    }
                    total = total + index
                }
                return total;
            }

            def sized(value) => match (value) {
                case is Array {
                    yield value.size
                }
                else => 0
            }

            def guarded() {
                defer noop()
                throw new Exception("нарочно");
            }

            def noop() { }

            first, second = *[10, 20]

            for (i = 0; i < LIMIT; i = i + 1) {
                noop()
            }

            for (word in ["a", "b"]) {
                noop()
            }

            use (handle = new Handle("файл")) {
                noop()
            }

            try {
                guarded()
            } catch (e) {
                noop()
            }

            answers = [twice(2), classify(1), classify(-1), sumTo(LIMIT), sized([1, 2])]
            answers.second()
            """;

    @Test
    @DisplayName("Останов срабатывает на инструкции любого вида")
    void everyStatementKindStops() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            Source source = Source.ofString(CODE);
            Diagnostics diagnostics = new Diagnostics(source);
            Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
            assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());

            Set<String> expected = kindsOf(program);
            Set<String> seen = new TreeSet<>();

            DebugSession session = new DebugSession();
            ExecutionContext context = ExecutionContext.fresh();
            session.listener(new DebugListener() {
                @Override
                public void suspended(SuspendedEvent event) {
                    // Отладчик здесь — сам скрипт: останавливаемся, записываем вид
                    // инструкции и тут же отпускаем. Слушателю это позволено — сессия
                    // возобновление из обработчика переживает.
                    DebugFrame frame = event.top();
                    if (frame != null) {
                        seen.add(kindAt(program, frame.offset()));
                    }
                    session.resumeAll();
                }

                @Override
                public void resumed(long threadId) {
                }
            });
            session.attach(context);
            session.breakpoints().set(source.name(), offsetsOf(program));

            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread runner = new Thread(() -> {
                try {
                    new Interpreter().run(Unit.of(source, program), context);
                } catch (Throwable failed) {
                    failure.set(failed);
                }
            }, "script");
            runner.setDaemon(true);
            runner.start();
            runner.join(15_000);
            assertFalse(runner.isAlive(), "скрипт не закончился");
            // Скрипт обязан дойти до конца: упавший на середине проверил бы половину
            // видов инструкций и промолчал бы об этом.
            assertNull(failure.get(), () -> "скрипт упал: " + failure.get());

            session.detach();
            context.closeRun();
            context.shutdownModules();

            Set<String> missed = new LinkedHashSet<>(expected);
            missed.removeAll(seen);
            assertTrue(missed.isEmpty(),
                    () -> "на этих видах инструкций останова не было: " + missed
                            + "\nбыли: " + seen);
        });
    }

    /** Виды инструкций, которые в этом дереве есть и на которых останов обязан быть. */
    private static Set<String> kindsOf(Program program) {
        Set<String> kinds = new TreeSet<>();
        Nodes.walk(program, node -> {
            if (isStop(node)) {
                kinds.add(node.getClass().getSimpleName());
            }
        });
        return kinds;
    }

    private static List<Integer> offsetsOf(Program program) {
        List<Integer> offsets = new ArrayList<>();
        Nodes.walk(program, node -> {
            if (isStop(node)) {
                offsets.add(node.span().start());
            }
        });
        return offsets;
    }

    /** Вид инструкции, начинающейся на этом смещении. */
    private static String kindAt(Program program, int offset) {
        List<String> found = new ArrayList<>();
        Nodes.walk(program, node -> {
            if (isStop(node) && node.span().start() == offset) {
                found.add(node.getClass().getSimpleName());
            }
        });
        // Несколько инструкций могут начинаться в одной точке (декорированное
        // объявление и само объявление): берётся внешняя — та, до которой дошло
        // выполнение первой.
        return found.isEmpty() ? "?" : found.get(0);
    }

    /**
     * Останов бывает на любой инструкции, кроме трёх:
     * <ul>
     *   <li>{@link BlockStmt} — у блока своё место есть, но точка на нём означала бы
     *       «перед первой инструкцией внутри», и это она и есть;</li>
     *   <li>{@link ErrorStmt} — там, где разбор не удался, выполнения не будет;</li>
     *   <li>{@link ImportStmt} — в этом скрипте модулей нет, и проверять на нём
     *       нечего.</li>
     * </ul>
     */
    private static boolean isStop(Node node) {
        return node instanceof Stmt statement
                && !(statement instanceof BlockStmt)
                && !(statement instanceof ErrorStmt)
                && !(statement instanceof ImportStmt)
                && !statement.span().isNone();
    }
}
