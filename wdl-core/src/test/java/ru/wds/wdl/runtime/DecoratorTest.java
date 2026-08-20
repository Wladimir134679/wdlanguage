package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.module.Unit;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Выполнение декораторов: порядок, метаданные, обёртки, классы. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DecoratorTest {

    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(Unit.of(source, program), ExecutionContext.fresh(output::append));
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    // --- основа --------------------------------------------------------------

    @Test
    @DisplayName("декоратор без возврата оставляет цель прежней")
    void noReturnKeepsTarget() {
        assertEquals("зарегистрирован command 3", printed("""
                def reg(meta) {
                    println("зарегистрирован ", meta.name)
                }

                @[reg]
                def command(a, b) => a + b

                println(command(1, 2))
                """));
    }

    @Test
    @DisplayName("декоратор подменяет цель тем, что вернул")
    void returnReplacesTarget() {
        assertEquals("обёрнуто:3", printed("""
                def wrap(meta) {
                    return def (*args, **named) => "обёрнуто:" + meta.target(*args, **named);
                }

                @[wrap]
                def sum(a, b) => a + b

                println(sum(1, 2))
                """));
    }

    @Test
    @DisplayName("свои аргументы декоратора идут после метаданных")
    void ownArgumentsFollowMeta() {
        assertEquals("[info] command", printed("""
                def log(meta, level, prefix = "") {
                    println(prefix, "[", level, "] ", meta.name)
                }

                @[log]("info")
                def command() {}
                """));
    }

    @Test
    @DisplayName("аргументы декоратора именуются двоеточием, как везде")
    void ownArgumentsCanBeNamed() {
        assertEquals(">>[debug] command", printed("""
                def log(meta, level, prefix = "") {
                    println(prefix, "[", level, "] ", meta.name)
                }

                @[log](level: "debug", prefix: ">>")
                def command() {}
                """));
    }

    // --- порядок -------------------------------------------------------------

    @Test
    @DisplayName("применяются снизу вверх: нижний видит цель, верхний — результат нижнего")
    void appliedBottomUp() {
        assertEquals("нижний: command верхний: null", printed("""
                def watch(meta, tag) {
                    println(tag, ": ", meta.name)
                }

                def wrap(meta) {
                    return def (*args, **named) => meta.target(*args, **named);
                }

                @[watch]("верхний")
                @[wrap]
                @[watch]("нижний")
                def command() {}
                """));
    }

    @Test
    @DisplayName("под именем оказывается результат самого верхнего декоратора")
    void topmostWins() {
        assertEquals("внешний внутренний тело", printed("""
                def mark(meta, tag) {
                    return def () {
                        println(tag)
                        meta.target()
                    };
                }

                @[mark]("внешний")
                @[mark]("внутренний")
                def body() => println("тело")

                body()
                """));
    }

    // --- метаданные ----------------------------------------------------------

    @Test
    @DisplayName("метаданные несут цель, имя и признак анонимности; тип спрашивается у typeof")
    void metaContents() {
        assertEquals("command function false", printed("""
                def show(meta) {
                    println(meta.name, " ", typeof(meta.target), " ", meta.isAnonymous)
                }

                @[show]
                def command() {}
                """));
    }

    @Test
    @DisplayName("у класса метаданные те же, а typeof говорит class")
    void metaOfClass() {
        assertEquals("Command class false", printed("""
                def show(meta) {
                    println(meta.name, " ", typeof(meta.target), " ", meta.isAnonymous)
                }

                @[show]
                class Command(id) {}
                """));
    }

    @Test
    @DisplayName("имя первого аргумента занять своим нельзя: позиция уже задана")
    void metaPositionCannotBeNamed() {
        assertTrue(errorOf("""
                def deco(meta, **opts) {}

                @[deco](meta: 5)
                def command() {}
                """).getMessage().contains("уже задан"));
    }

    @Test
    @DisplayName("верхний декоратор видит обёртку: имени у неё нет, и выдумывать его незачем")
    void wrapperAboveHasNoName() {
        assertEquals("null true", printed("""
                def wrap(meta) {
                    return def (*args, **named) => meta.target(*args, **named);
                }

                def watch(meta) {
                    println(meta.name, " ", meta.isAnonymous)
                }

                @[watch]
                @[wrap]
                def command() {}
                """));
    }

    // --- цель и ошибки -------------------------------------------------------

    @Test
    @DisplayName("декоратором может быть только функция")
    void decoratorMustBeAFunction() {
        assertTrue(errorOf("""
                notAFunction = 42

                @[notAFunction]
                def command() {}
                """).getMessage().contains("декоратором может быть только функция"));
    }

    @Test
    @DisplayName("декоратор — обычное выражение: годится и поле объекта, и элемент массива")
    void calleeIsEvaluated() {
        assertEquals("через карту command", printed("""
                def reg(meta) {
                    println("через карту ", meta.name)
                }
                table = {"reg": reg}

                @[table["reg"]]
                def command() {}
                """));
    }

    @Test
    @DisplayName("декоратор выполняется каждый раз, когда исполнение проходит через объявление")
    void runsOnEveryPass() {
        assertEquals("3", printed("""
                count = 0
                def tick(meta) {
                    count = count + 1
                }

                def outer() {
                    @[tick]
                    def inner() {}
                    return inner;
                }

                outer()
                outer()
                outer()
                println(count)
                """));
    }

    @Test
    @DisplayName("рекурсия попадает в обёртку: имя ищется в момент вызова")
    void recursionGoesThroughTheWrapper() {
        assertEquals("5 120", printed("""
                calls = 0
                def counted(meta) {
                    return def (n) {
                        calls = calls + 1
                        return meta.target(n);
                    };
                }

                @[counted]
                def fact(n) => n <= 1 ? 1 : n * fact(n - 1)

                result = fact(5)
                println(calls, " ", result)
                """));
    }

    // --- like: обёртка, представляющаяся целью -------------------------------

    @Test
    @DisplayName("без like обёртка съедает проверку числа аргументов")
    void wrapperLosesArityWithoutLike() {
        // Ошибка всё равно будет, но придёт она изнутри обёртки, а не со строки вызова.
        assertTrue(errorOf("""
                def wrap(meta) {
                    return def (*args, **named) => meta.target(*args, **named);
                }

                @[wrap]
                def command(a, b) => a + b

                command(1, 2, 3, 4)
                """).getMessage().contains("'command'"));
    }

    @Test
    @DisplayName("like возвращает арность и имя цели: ошибка снова на строке вызова")
    void likeKeepsArity() {
        WdlRuntimeError error = errorOf("""
                def wrap(meta) {
                    return like(meta.target, def (*args, **named) => meta.target(*args, **named));
                }

                @[wrap]
                def command(a, b) => a + b

                command(1, 2, 3, 4)
                """);
        assertTrue(error.getMessage().contains("функция 'command' принимает"), error.getMessage());
        assertTrue(error.getMessage().contains("передано 4"), error.getMessage());
    }

    @Test
    @DisplayName("сквозь like проходят имена, значения по умолчанию и пропуски")
    void likePassesNamesAndDefaults() {
        assertEquals("1 10 1 7", printed("""
                def wrap(meta) {
                    return like(meta.target, def (*args, **named) => meta.target(*args, **named));
                }

                @[wrap]
                def show(a, b = 10) => a + " " + b

                println(show(1), " ", show(a: 1, b: 7))
                """));
    }

    @Test
    @DisplayName("обёртка для like обязана собирать аргументы целиком")
    void likeRequiresVariadicWrapper() {
        assertTrue(errorOf("""
                def target(a) => a
                like(target, def (x) => x)
                """).getMessage().contains("объявите её как 'def (*args, **named)'"));
    }

    @Test
    @DisplayName("like печатается и опознаётся как цель")
    void likeLooksLikeTarget() {
        assertEquals("function def command", printed("""
                def wrap(meta) {
                    return like(meta.target, def (*args, **named) => meta.target(*args, **named));
                }

                @[wrap]
                def command() {}

                println(typeof(command), " ", command)
                """));
    }

    // --- классы --------------------------------------------------------------

    @Test
    @DisplayName("класс оборачивается наследником, аргументы перебрасываются остатком")
    void classProxyForwardsArguments() {
        assertEquals("В родителе было так: точка(3, 4)", printed("""
                def dec(meta) {
                    class Proxy(*args, **named) : meta.target(*args, **named) {
                        def text() => "В родителе было так: " + super.text()
                    }
                    return Proxy;
                }

                @[dec]
                class Point(x, y) {
                    def text() => "точка(" + x + ", " + y + ")"
                }

                println(new Point(3, y: 4).text())
                """));
    }

    @Test
    @DisplayName("класс, возвращённый как есть, остаётся собой для 'is'")
    void classReturnedUnchangedStaysItself() {
        assertEquals("true", printed("""
                def reg(meta) {
                    return meta.target;
                }

                @[reg]
                class Command {}

                println(new Command() is Command)
                """));
    }
}
