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

/**
 * Выполнение строки с подстановкой.
 * <p>
 * Главное здесь — что значение вставляется своим {@code display}: тем же, каким его
 * печатает {@code println} и каким его добавляет конкатенация. Второго способа
 * показать значение текстом в языке нет.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class InterpolationTest {

    /** Запускает скрипт и возвращает напечатанное одной строкой. */
    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(Unit.of(source, program),
                ExecutionContext.fresh(output::append));
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    @Test
    @DisplayName("части склеиваются по порядку")
    void joinsParts() {
        assertEquals("итого: 360 руб.",
                printed("price = 120\ncount = 3\nprintln('итого: ${price * count} руб.')"));
    }

    @Test
    @DisplayName("подстановка даёт строку, даже когда все части — числа")
    void alwaysAString() {
        // Цепочкой сложений это было бы 3: строка получается только потому,
        // что узел свой.
        assertEquals("12 string",
                printed("a = 1\nb = 2\nvalue = '${a}${b}'\nprintln(value, \" \", typeof(value))"));
    }

    @Test
    @DisplayName("значение вставляется своим display: null, массив, объект")
    void usesDisplay() {
        assertEquals("null [1, 2] {\"a\": 1}",
                printed("println('${null} ${[1, 2]} ${ {a: 1} }')"));
    }

    @Test
    @DisplayName("экземпляр вставляется тем же представлением, каким его печатают")
    void sameAsConcatenation() {
        // Своей печати у экземпляра пока нет — он печатается как объект. Важно другое:
        // подстановка не заводит второго представления, у неё и у конкатенации оно одно.
        String code = "class Point(x, y)\n"
                + "p = new Point(1, 2)\n"
                + "println('${p}' == \"\" + p)";

        assertEquals("true", printed(code));
    }

    @Test
    @DisplayName("в подстановке работает любой вызов, включая рекурсивный")
    void callsInsideHole() {
        String code = "def factorial(n) => n <= 1 ? 1 : n * factorial(n - 1)\n"
                + "println('5! = ${factorial(5)}')";

        assertEquals("5! = 120", printed(code));
    }

    @Test
    @DisplayName("строка с подстановкой — обычная строка: те же члены")
    void isAnOrdinaryString() {
        assertEquals("МИР 3",
                printed("name = 'мир'\ngreeting = '${name}'\n"
                        + "println(greeting.upper, \" \", greeting.size)"));
    }

    @Test
    @DisplayName("подстановка годится и значением параметра по умолчанию")
    void worksAsDefaultValue() {
        String code = "def hello(name, greeting = 'привет, ${name}') => greeting\n"
                + "println(hello(\"мир\"))";

        assertEquals("привет, мир", printed(code));
    }

    // --- многострочная строка с подстановкой ---------------------------------

    @Test
    @DisplayName("многострочная строка с подстановкой: отступ снят, значения на месте")
    void multiline() {
        String code = "name = \"мир\"\n"
                + "items = [1, 2]\n"
                + "letter = '''\n"
                + "    Привет, ${name}!\n"
                + "    В корзине ${items.size} шт.\n"
                + "    '''\n"
                + "print(letter)";

        // Перевод строки внутри значения остаётся переводом: printed склеивает
        // только разделители самих println.
        assertEquals("Привет, мир!\nВ корзине 2 шт.", printed(code));
    }

    @Test
    @DisplayName("строка в строке: подстановка внутри подстановки")
    void nested() {
        String code = "name = \"мир\"\n"
                + "print('''\n"
                + "    внутри: ${ 'ещё ${name}' }\n"
                + "    ''')";

        assertEquals("внутри: ещё мир", printed(code));
    }
}
