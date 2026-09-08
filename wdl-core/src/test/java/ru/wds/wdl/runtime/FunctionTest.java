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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выполнение функций: вызов, {@code return}, рекурсия, замыкания и области видимости.
 * <p>
 * Правило областей видимости, которое здесь проверяется чаще всего: имя, которое есть
 * снаружи, изнутри функции именно <b>меняется</b>; новую переменную создаёт только новое
 * имя, и наружу она не выходит.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class FunctionTest {

    /** Юнитом, а не голой программой: так же запускает файл и консольный {@code wdl}. */
    private static Unit parse(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        return Unit.of(source, program);
    }

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        new Interpreter().run(parse(code), ExecutionContext.fresh(printed::append));
        return printed.toString();
    }

    /** Вывод одной строкой: переводы строк заменены пробелами — так ожидания читаются целиком. */
    private static String printed(String code) {
        return run(code).replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    // --- вызов и возврат -----------------------------------------------------

    @Test
    @DisplayName("три формы тела дают одинаковый результат")
    void bodyFormsBehaveTheSame() {
        assertEquals("5", printed("def f(a, b) { return a + b; }\nprintln(f(2, 3))"));
        assertEquals("5", printed("def f(a, b) return a + b;\nprintln(f(2, 3))"));
        assertEquals("5", printed("def f(a, b) => a + b\nprintln(f(2, 3))"));
    }

    @Test
    @DisplayName("функция без return возвращает null")
    void withoutReturnGivesNull() {
        assertEquals("null", printed("def f() { x = 1 }\nprintln(f())"));
        assertEquals("null", printed("def f() { return; }\nprintln(f())"));
    }

    @Test
    @DisplayName("return выходит из любой вложенности разом")
    void returnLeavesEverything() {
        assertEquals("1", printed("""
                def найти(значения, что) {
                    for (i = 0; i < len(значения); i += 1) {
                        if (значения[i] == что) {
                            return i;
                        }
                    }
                    return -1;
                }
                println(найти(["а", "б", "в"], "б"))
                """));
        assertEquals("-1", printed("""
                def найти(значения, что) {
                    for (x in значения) { if (x == что) { return "есть"; } }
                    return -1;
                }
                println(найти([1, 2], 3))
                """));
    }

    @Test
    @DisplayName("инструкции после return не выполняются")
    void returnStopsTheBody() {
        assertEquals("до 1", printed("""
                def f() {
                    println("до")
                    return 1;
                    println("после")
                }
                println(f())
                """));
    }

    @Test
    @DisplayName("число аргументов проверяется до входа в функцию")
    void arityIsChecked() {
        String message = errorOf("def f(a) => a\nf(1, 2)").getMessage();
        assertTrue(message.contains("'f'"), message);
        assertTrue(message.contains("ровно 1 аргумент"), message);
        assertTrue(message.contains("передано 2"), message);

        assertTrue(errorOf("def f(a, b) => a\nf(1)").getMessage().contains("ровно 2 аргумента"));
    }

    // --- значения по умолчанию -----------------------------------------------

    @Test
    @DisplayName("непереданный аргумент берётся из значения по умолчанию")
    void defaultValueIsSubstituted() {
        assertEquals("привет, мир здравствуй, мир", printed("""
                def greet(name, greeting = "привет") => greeting + ", " + name
                println(greet("мир"))
                println(greet("мир", "здравствуй"))
                """));
    }

    @Test
    @DisplayName("значение по умолчанию видит параметры, связанные левее")
    void defaultValueSeesEarlierParameters() {
        // 120.0 и 240.0 вещественные: их посчитал дефолт с 0.2, а в третьем вызове
        // налог передан целым нулём — тип результата виден в выводе.
        assertEquals("120.0 240.0 200", printed("""
                def total(price, count = 1, tax = price * count * 0.2) => price * count + tax
                println(total(100), " ", total(100, 2), " ", total(100, 2, 0))
                """));
    }

    @Test
    @DisplayName("значение по умолчанию считается при вызове, а не при объявлении")
    void defaultValueIsEvaluatedAtCallTime() {
        // Внешняя переменная менялась между объявлением и вызовом — берётся новое значение.
        assertEquals("21", printed("""
                step = 10
                def inc(x, by = step) => x + by
                step = 20
                println(inc(1))
                """));
    }

    @Test
    @DisplayName("на каждый вызов своё значение, а не одно общее — в отличие от Python")
    void defaultValueIsFreshEachCall() {
        assertEquals("1 2", printed("""
                def box(value, holder = {}) {
                    holder.value = value
                    return holder;
                }
                println(box(1).value, " ", box(2).value)
                """));
    }

    @Test
    @DisplayName("значение по умолчанию не вычисляется, если аргумент передали")
    void defaultValueIsSkippedWhenArgumentIsGiven() {
        assertEquals("5", printed("""
                def mark() {
                    println("считаю")
                    return 1;
                }
                def f(a = mark()) => a
                println(f(5))
                """));
        assertEquals("считаю 1", printed("""
                def mark() {
                    println("считаю")
                    return 1;
                }
                def f(a = mark()) => a
                println(f())
                """));
    }

    @Test
    @DisplayName("число аргументов стало отрезком, и проверка осталась там же")
    void arityBecomesRange() {
        String message = errorOf("def greet(name, greeting = \"привет\") => greeting\ngreet(1, 2, 3)")
                .getMessage();
        assertTrue(message.contains("'greet'"), message);
        assertTrue(message.contains("от 1 до 2 аргументов"), message);
        assertTrue(message.contains("передано 3"), message);

        assertTrue(errorOf("def f(a, b = 1) => a\nf()").getMessage().contains("от 1 до 2 аргументов"));
    }

    @Test
    @DisplayName("значение по умолчанию работает у анонимной функции и у встроенного значения в поле")
    void defaultValueForAnonymousFunctions() {
        assertEquals("42 21", printed("""
                double = def(x, by = 2) => x * by
                handlers = {inc: def(x, step = 1) => x + step}
                println(double(21), " ", handlers.inc(20))
                """));
    }

    // --- объявление существует со своей строки -------------------------------

    @Test
    @DisplayName("вызов выше объявления — ошибка, называющая строку объявления")
    void callAboveDeclarationIsAnError() {
        // Стадии до выполнения нет: инструкции идут подряд, и на первой строке имени
        // ещё не существует. Сообщение поэтому называет причину, а не симптом.
        String message = errorOf("println(сумма(2, 3))\ndef сумма(a, b) => a + b").getMessage();
        assertTrue(message.contains("переменная 'сумма' не определена"), message);
        assertTrue(message.contains("объявление стоит ниже, на строке 2"), message);
    }

    @Test
    @DisplayName("функция видит функцию, объявленную ниже: имя ищется в момент вызова")
    void bodySeesLaterDeclarations() {
        // Самый частый случай на практике, и он работает без всякого преданализа:
        // тело выполняется тогда, когда объявлены уже обе функции.
        assertEquals("4", printed("""
                def main() => helper(2)
                def helper(n) => n * 2
                println(main())
                """));
    }

    @Test
    @DisplayName("две функции могут вызывать друг друга")
    void mutualRecursion() {
        // По той же причине: к первому вызову обе уже объявлены, а замыкание —
        // ссылка на область, а не снимок её значений.
        assertEquals("true false", printed("""
                def чётное(n) => n == 0 ? true : нечётное(n - 1)
                def нечётное(n) => n == 0 ? false : чётное(n - 1)
                println(нечётное(7))
                println(нечётное(8))
                """));
    }

    @Test
    @DisplayName("выбор реализации по условию: имя заводится присваиванием, а не def")
    void conditionalImplementation() {
        // 'def' в ветке принадлежит области ветки и наружу не выходит — правило блока
        // осталось прежним. Наружу отдаёт присваивание: имя заводится на верхнем уровне,
        // а какая функция в него попадёт, решает условие.
        assertEquals("тихо", printed("""
                debug = false
                log = null
                if (debug) {
                    log = def(msg) => println("[отладка] ", msg)
                } else {
                    log = def(msg) => println(msg)
                }
                log("тихо")
                """));
    }

    @Test
    @DisplayName("объявление внутри блока наружу не выходит")
    void declarationBelongsToItsScope() {
        assertEquals("тут", printed("if (true) { def внутренняя() => \"тут\"\n println(внутренняя()) }"));
        assertTrue(errorOf("if (true) { def внутренняя() => 1 }\nprintln(внутренняя())")
                .getMessage().contains("не определена"));
        // Объявление внутри функции живёт только в её вызове
        assertTrue(errorOf("def снаружи() { def внутри() => 1\n return внутри(); }\nснаружи()\nвнутри()")
                .getMessage().contains("не определена"));
    }

    @Test
    @DisplayName("имя, объявленное во вложенной области, диагностика находит и называет")
    void hintPointsIntoNestedScope() {
        String message = errorOf("""
                def снаружи() { def внутри() => 1
                    return внутри(); }
                внутри()
                """).getMessage();
        assertTrue(message.contains("внутри вложенной области"), message);
    }

    @Test
    @DisplayName("объявление в теле анонимной функции диагностика тоже находит")
    void hintReachesAnonymousFunctionBody() {
        // Раньше подсказка сюда не доходила: обход перечислял виды инструкций руками,
        // и присваивание с функцией справа в список не попало. Теперь состав детей
        // берётся у общего обхода, и такие места не забываются.
        String message = errorOf("""
                handler = def() { def helper() => 1
                    return helper(); }
                helper()
                """).getMessage();
        assertTrue(message.contains("внутри вложенной области"), message);
    }

    @Test
    @DisplayName("константа выше и одноимённая функция ниже — ошибка на строке def")
    void constantBlocksLaterFunction() {
        // Раньше это держалось на побочном эффекте: помеченное объявление выполнялось
        // второй раз, уже после заморозки имени. Теперь оно выполняется ровно один раз
        // и на своей строке — а результат тот же.
        assertTrue(errorOf("const A = 1\ndef A() => 2").getMessage()
                .contains("нельзя объявить"));
    }

    // --- рекурсия ------------------------------------------------------------

    @Test
    @DisplayName("рекурсия работает без особой механики: функция находит себя")
    void recursion() {
        assertEquals("120", printed("def факториал(n) => n <= 1 ? 1 : n * факториал(n - 1)\nprintln(факториал(5))"));
    }

    @Test
    @DisplayName("бесконечная рекурсия останавливает выполнение, а не роняет поток")
    void recursionHasLimit() {
        FatalError fatal = assertThrows(FatalError.class,
                () -> printed("def вечно(n) => вечно(n + 1)\nвечно(0)"));
        assertTrue(fatal.getMessage().contains("слишком глубокая рекурсия"), fatal.getMessage());
        assertFalse(fatal.span().isNone(), "ошибка обязана знать место в скрипте");
    }

    @Test
    @DisplayName("разумная глубина рекурсии проходит целиком")
    void deepEnoughRecursionWorks() {
        int depth = Limits.DEFAULT_CALL_DEPTH - 2;
        assertEquals("дно", printed("def вниз(n) => n <= 0 ? \"дно\" : вниз(n - 1)\nprintln(вниз(" + depth + "))"));
    }

    @Test
    @DisplayName("если стека потока не хватило раньше счётчика — всё равно остановка, а не крах")
    void shortStackStillGivesScriptError() throws InterruptedException {
        // Счётчик движка до предела не дойдёт: он рассчитан на обычный поток, а здесь стек
        // нарочно крошечный. Проверяется вторая линия защиты — та, что превращает
        // StackOverflowError в остановку выполнения с внятным сообщением.
        Unit unit = parse("def вниз(n) => n <= 0 ? 0 : вниз(n - 1)\nвниз("
                + (Limits.DEFAULT_CALL_DEPTH - 2) + ")");

        Throwable[] thrown = new Throwable[1];
        Thread thread = new Thread(null, () -> {
            try {
                new Interpreter().run(unit, ExecutionContext.fresh(text -> { }));
            } catch (Throwable t) {
                thrown[0] = t;
            }
        }, "короткий стек", 96 * 1024);
        thread.start();
        thread.join();

        FatalError fatal = assertInstanceOf(FatalError.class, thrown[0],
                () -> "ожидалась остановка выполнения, а получено: " + thrown[0]);
        assertTrue(fatal.getMessage().contains("стек вызовов исчерпан"), fatal.getMessage());
    }

    // --- функция как значение ------------------------------------------------

    @Test
    @DisplayName("функция лежит в переменной, поле и элементе массива")
    void functionIsOrdinaryValue() {
        assertEquals("function", printed("def f() => 1\nprintln(typeof(f))"));
        assertEquals("7", printed("f = def(a, b) => a + b\nprintln(f(3, 4))"));
        assertEquals("14 6", printed("""
                операции = {плюс: def(a, b) => a + b, минус: def(a, b) => a - b}
                println(операции.плюс(10, 4))
                println(операции["минус"](10, 4))
                """));
        assertEquals("2", printed("обработчики = [def(x) => x * 2]\nprintln(обработчики[0](1))"));
    }

    @Test
    @DisplayName("функцию можно передать аргументом и вернуть из функции")
    void higherOrder() {
        assertEquals("20", printed("""
                def удвоить(x) => x * 2
                def дважды(f, значение) => f(f(значение))
                println(дважды(удвоить, 5))
                """));
        assertEquals("15", printed("""
                def прибавлятель(сколько) => def(x) => x + сколько
                прибавить10 = прибавлятель(10)
                println(прибавить10(5))
                """));
    }

    // --- замыкания -----------------------------------------------------------

    @Test
    @DisplayName("замыкание держит область, а не снимок значений")
    void closureSharesTheVariable() {
        assertEquals("1 2 3", printed("""
                def счётчик() {
                    сколько = 0
                    return def() {
                        сколько += 1
                        return сколько;
                    };
                }
                тик = счётчик()
                println(тик())
                println(тик())
                println(тик())
                """));
    }

    @Test
    @DisplayName("у каждого вызова своё замыкание")
    void closuresAreIndependent() {
        assertEquals("1 1", printed("""
                def счётчик() {
                    сколько = 0
                    return def() { сколько += 1
                        return сколько; };
                }
                println(счётчик()())
                println(счётчик()())
                """));
    }

    @Test
    @DisplayName("два замыкания над одной переменной видят одно и то же")
    void closuresShareOneVariable() {
        assertEquals("2", printed("""
                def пара() {
                    сколько = 0
                    прибавить = def() { сколько += 1 }
                    прочитать = def() => сколько
                    прибавить()
                    прибавить()
                    return прочитать();
                }
                println(пара())
                """));
    }

    @Test
    @DisplayName("переменная перебора захватывается своя на каждый проход")
    void perIterationCapture() {
        assertEquals("1 2 3", printed("""
                хранилище = [null, null, null]
                i = 0
                for (x in [1, 2, 3]) {
                    хранилище[i] = def() => x
                    i += 1
                }
                println(хранилище[0](), " ", хранилище[1](), " ", хранилище[2]())
                """));
    }

    // --- области видимости ---------------------------------------------------

    @Test
    @DisplayName("локальная переменная функции наружу не выходит")
    void localsDoNotLeak() {
        assertTrue(errorOf("def f() { локальная = 1 }\nf()\nprintln(локальная)")
                .getMessage().contains("переменная 'локальная' не определена"));
    }

    @Test
    @DisplayName("внешняя переменная читается и меняется")
    void outerVariableIsSharedForReadAndWrite() {
        assertEquals("снаружи изнутри", printed("""
                значение = "снаружи"
                def f() {
                    println(значение)
                    значение = "изнутри"
                }
                f()
                println(значение)
                """));
        assertEquals("3", printed("""
                счёт = 0
                def добавить(сколько) { счёт += сколько }
                добавить(1)
                добавить(2)
                println(счёт)
                """));
    }

    @Test
    @DisplayName("параметр всегда локальный: одноимённая внешняя не портится")
    void parameterShadowsOuter() {
        assertEquals("10 внешнее", printed("""
                x = "внешнее"
                def f(x) {
                    x = 10
                    println(x)
                }
                f(1)
                println(x)
                """));
    }

    @Test
    @DisplayName("объявление заводит имя локально и не затирает внешнее")
    void declarationDefinesLocally() {
        assertEquals("1 внешнее", printed("""
                имя = "внешнее"
                def f() {
                    def имя() => 1
                    return имя();
                }
                println(f())
                println(имя)
                """));
    }

    @Test
    @DisplayName("у каждого вызова своя копия локальных переменных")
    void callsHaveOwnScopes() {
        // Печать идёт после рекурсивного вызова, поэтому первым выводит самый глубокий:
        // 1, 2, 3 — и каждое значение своё, из своего вызова.
        assertEquals("1 2 3", printed("""
                def вниз(n) {
                    свой = n
                    if (n > 1) { вниз(n - 1) }
                    println(свой)
                }
                вниз(3)
                """));
    }
}
