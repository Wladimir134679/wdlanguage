package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Value;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Операторы принадлежности: {@code in}, {@code has} и их отрицания. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class MembershipTest {

    private static Value eval(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Expr expr = Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        return new Interpreter().eval(expr, ExecutionContext.fresh());
    }

    private static String show(String code) {
        return eval(code).display();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> eval(code));
    }

    @Test
    @DisplayName("в массиве ищется значение, а не ссылка")
    void arrayMembership() {
        assertEquals("true", show("2 in [1, 2, 3]"));
        assertEquals("false", show("5 in [1, 2, 3]"));
        // Числа сравниваются по величине — тем же Operations.equal, что и '=='.
        assertEquals("true", show("2.0 in [1, 2, 3]"));
        assertEquals("false", show("\"2\" in [1, 2, 3]"));
        assertEquals("false", show("1 in []"));
    }

    @Test
    @DisplayName("в строке ищется подстрока")
    void stringMembership() {
        assertEquals("true", show("\"ход\" in \"переход\""));
        assertEquals("false", show("\"ход\" in \"перевод\""));
        assertEquals("true", show("\"\" in \"что угодно\""));
    }

    @Test
    @DisplayName("у объекта спрашивается ключ, а не значение")
    void objectMembership() {
        assertEquals("true", show("\"color\" in {color: \"red\"}"));
        assertEquals("false", show("\"red\" in {color: \"red\"}"));
    }

    @Test
    @DisplayName("'has' — то же самое, но записанное от контейнера")
    void hasIsMirrorOfIn() {
        assertEquals("true", show("[1, 2, 3] has 2"));
        assertEquals("true", show("\"переход\" has \"ход\""));
        assertEquals("true", show("{color: \"red\"} has \"color\""));
        assertEquals("false", show("[1, 2, 3] has 5"));
    }

    @Test
    @DisplayName("отрицания дают обратный ответ")
    void negations() {
        assertEquals("false", show("2 !in [1, 2, 3]"));
        assertEquals("true", show("5 !in [1, 2, 3]"));
        assertEquals("false", show("[1, 2, 3] !has 2"));
        assertEquals("true", show("1 !is String"));
        assertEquals("false", show("1 !is Number"));
    }

    @Test
    @DisplayName("искать можно только в массиве, строке или объекте")
    void containerMustBeContainer() {
        assertTrue(errorOf("1 in 5").getMessage()
                .contains("искать можно в массиве, строке, объекте или диапазоне, а здесь число (5)"));
        assertTrue(errorOf("1 in null").getMessage().contains("искать можно в массиве"));
        assertTrue(errorOf("5 in \"текст\"").getMessage()
                .contains("в строке ищется строка, а здесь число (5)"));
    }

    @Test
    @DisplayName("член и оператор отвечают одинаково")
    void membersAgreeWithOperator() {
        assertEquals(show("2 in [1, 2, 3]"), show("[1, 2, 3].contains(2)"));
        assertEquals(show("\"ход\" in \"переход\""), show("\"переход\".contains(\"ход\")"));
        // После точки ключевое слово — имя члена: 'obj.has(k)' от нового слова 'has'
        // не пострадал.
        assertEquals(show("\"color\" in {color: \"red\"}"), show("{color: \"red\"}.has(\"color\")"));
    }

    @Test
    @DisplayName("принадлежность стоит на уровне сравнений")
    void precedence() {
        // 'a in b == true' — сравнение ответа, а не поиск 'a' в 'b == true'.
        assertEquals("true", show("2 in [1, 2] == true"));
        // '&&' слабее: скобки не нужны.
        assertEquals("true", show("2 in [1, 2] && 3 in [3]"));
    }
}
