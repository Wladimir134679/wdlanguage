package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.expr.*;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    /** Разбирает выражение и требует, чтобы обошлось без ошибок. */
    private static Expr parse(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Expr expr = Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        return expr;
    }

    /** Форма дерева скобочной записью. */
    private static String tree(String code) {
        return SExprPrinter.print(parse(code));
    }

    /** Диагностика разбора: тексты ошибок проверяются вместе с тем, что дерево всё равно есть. */
    private static Diagnostics diagnose(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        assertNotNull(Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics),
                "дерево должно возвращаться даже при ошибках");
        return diagnostics;
    }

    // --- приоритеты и ассоциативность ---------------------------------------

    @Test
    @DisplayName("умножение связывает сильнее сложения")
    void multiplicativeBeforeAdditive() {
        assertEquals("(+ 1 (* 2 3))", tree("1 + 2 * 3"));
        assertEquals("(+ (* 1 2) 3)", tree("1 * 2 + 3"));
        assertEquals("(- (+ 1 (/ 6 3)) (% 7 2))", tree("1 + 6 / 3 - 7 % 2"));
    }

    @Test
    @DisplayName("одинаковые операторы группируются слева направо")
    void leftAssociative() {
        assertEquals("(- (- 10 3) 2)", tree("10 - 3 - 2"));
        assertEquals("(/ (/ 100 5) 2)", tree("100 / 5 / 2"));
    }

    @Test
    @DisplayName("скобки перекрывают приоритет и в дереве не остаются")
    void parentheses() {
        assertEquals("(* (+ 1 2) 3)", tree("(1 + 2) * 3"));
        assertEquals("(+ 1 2)", tree("((1 + 2))"));
    }

    @Test
    @DisplayName("побитовые операции сильнее сравнений — в отличие от Си")
    void bitwiseBindsTighterThanComparison() {
        assertEquals("(== (& флаги 4) 0)", tree("флаги & 4 == 0"));
        assertEquals("(|| (< (| a b) 5) c)", tree("a | b < 5 || c"));
    }

    @Test
    @DisplayName("логическое И сильнее логического ИЛИ")
    void andBeforeOr() {
        assertEquals("(|| a (&& b c))", tree("a || b && c"));
    }

    @Test
    @DisplayName("сдвиги сильнее сравнений, но слабее сложения")
    void shiftPrecedence() {
        assertEquals("(< (<< 1 2) (+ 3 4))", tree("1 << 2 < 3 + 4"));
    }

    // --- унарные операции ----------------------------------------------------

    @Test
    @DisplayName("унарный оператор слабее обращения, но сильнее умножения")
    void unaryPrecedence() {
        assertEquals("(- (get a \"b\"))", tree("-a.b"));
        assertEquals("(* (- a) b)", tree("-a * b"));
        assertEquals("(- (* a b))", tree("-(a * b)"));
    }

    @Test
    @DisplayName("унарные операторы складываются друг на друга")
    void nestedUnary() {
        assertEquals("(! (! флаг))", tree("!!флаг"));
        assertEquals("(- (- 1))", tree("- -1"));
        assertEquals("(~ (- 1))", tree("~-1"));
    }

    // --- условное выражение --------------------------------------------------

    @Test
    @DisplayName("условное выражение вкладывается вправо")
    void ternaryIsRightAssociative() {
        assertEquals("(?: a b (?: c d e))", tree("a ? b : c ? d : e"));
    }

    @Test
    @DisplayName("условное выражение слабее любых операций в его частях")
    void ternaryIsWeakest() {
        assertEquals("(?: (> a 0) (+ a 1) (- a 1))", tree("a > 0 ? a + 1 : a - 1"));
    }

    // --- обращение: точка и скобки — одно и то же ----------------------------

    @Test
    @DisplayName("точка — это ключ-строка: a.b и a[\"b\"] дают одинаковое дерево")
    void dotIsSugarForStringKey() {
        assertEquals("(get a \"b\")", tree("a.b"));
        assertEquals("(get a \"b\")", tree("a[\"b\"]"));
        assertEquals(tree("данные.строки"), tree("данные[\"строки\"]"));
    }

    @Test
    @DisplayName("форма записи сохраняется отдельно от смысла — для форматтера и диагностики")
    void accessStyleIsRemembered() {
        assertEquals(AccessStyle.DOT, assertInstanceOf(AccessExpr.class, parse("a.b")).style());
        assertEquals(AccessStyle.BRACKET, assertInstanceOf(AccessExpr.class, parse("a[\"b\"]")).style());
        assertEquals("b", assertInstanceOf(AccessExpr.class, parse("a.b")).fieldName());
    }

    @Test
    @DisplayName("цепочка обращений собирается вложением, независимо от формы записи")
    void accessChain() {
        assertEquals("(get (get (get a \"b\") 0) \"c\")", tree("a.b[0].c"));
        assertEquals("(get (get (get a \"b\") 0) \"c\")", tree("a[\"b\"][0][\"c\"]"));
    }

    @Test
    @DisplayName("ключ можно вычислить прямо в обращении")
    void computedKey() {
        assertEquals("(get настройки (+ префикс \"цвет\"))", tree("настройки[префикс + \"цвет\"]"));
    }

    @Test
    @DisplayName("после точки допустимо ключевое слово: имя поля — не позиция для конструкции языка")
    void keywordAsFieldName() {
        assertEquals("(get результат \"match\")", tree("результат.match"));
        assertEquals("(get значение \"class\")", tree("значение.class"));
    }

    @Test
    @DisplayName("обращение крепче всех операций")
    void accessBindsTightest() {
        assertEquals("(+ (get a \"x\") (get b \"y\"))", tree("a.x + b.y"));
    }

    // --- создание и проверка класса ------------------------------------------

    @Test
    @DisplayName("new — не вызов: в дереве это отдельный узел")
    void newIsNotCall() {
        assertEquals("(new Point 1 2)", tree("new Point(1, 2)"));
        assertEquals("(new Point)", tree("new Point()"));
    }

    @Test
    @DisplayName("new забирает одно обращение и один список аргументов, остальное — экземпляру")
    void newTakesOneCall() {
        // Создаётся Circle, и уже у него зовётся area — а не создаётся результат вызова
        assertEquals("(call (get (new Circle 5) \"area\"))", tree("new Circle(5).area()"));
        assertEquals("(get (new Point 1 2) \"x\")", tree("new Point(1, 2).x"));
    }

    @Test
    @DisplayName("слева от скобок может стоять что угодно, что даёт класс")
    void newOverExpression() {
        assertEquals("(new (get kinds 0) 1 2)", tree("new kinds[0](1, 2)"));
        assertEquals("(new (get factory \"point\") 1)", tree("new factory.point(1)"));
    }

    @Test
    @DisplayName("is — обычный оператор с приоритетом сравнений")
    void isBindsLikeComparison() {
        assertEquals("(is figure Circle)", tree("figure is Circle"));
        assertEquals("(== (is figure Circle) true)", tree("figure is Circle == true"));
        assertEquals("(&& (is figure Circle) big)", tree("figure is Circle && big"));
        // Справа — выражение, а не только имя: класс лежит в обычной переменной
        assertEquals("(is figure (get kinds 0))", tree("figure is kinds[0]"));
    }

    @Test
    @DisplayName("скобки после new обязательны, даже пустые")
    void newRequiresArguments() {
        assertTrue(diagnose("new Point").renderAll().contains("список аргументов в скобках"));
        assertTrue(diagnose("new 5(1)").renderAll().contains("ожидалось имя класса"));
    }

    // --- литералы ------------------------------------------------------------

    @Test
    @DisplayName("числа всех форм превращаются в значения при разборе")
    void numberLiterals() {
        assertEquals("42", tree("42"));
        assertEquals("1000000", tree("1_000_000"));
        assertEquals("2.5", tree("2.5"));
        assertEquals("255", tree("0xFF"));
        assertEquals("5", tree("0b101"));
    }

    @Test
    @DisplayName("литералы массива и объекта")
    void collectionLiterals() {
        assertEquals("(array 1 2 (array 3))", tree("[1, 2, [3]]"));
        assertEquals("(array)", tree("[]"));
        assertEquals("(array 1 2)", tree("[1, 2,]"));
        assertEquals("(object (\"a\" 1) (\"b\" 2))", tree("{a: 1, \"b\": 2}"));
        assertEquals("(object)", tree("{}"));
    }

    @Test
    @DisplayName("ключ объекта без кавычек — тот же сахар, что и точка")
    void bareObjectKeys() {
        assertEquals(tree("{\"имя\": 1}"), tree("{имя: 1}"));
        assertEquals("(object ((+ a b) 1))", tree("{(a + b): 1}"));
    }

    // --- позиции -------------------------------------------------------------

    @Test
    @DisplayName("узел знает свой интервал в исходнике целиком")
    void spans() {
        Expr expr = parse("1 + 2 * 3");
        assertEquals(0, expr.span().start());
        assertEquals(9, expr.span().end());

        Expr access = parse("данные.поле");
        assertEquals(0, access.span().start());
        assertEquals(11, access.span().end());
    }

    // --- ошибки --------------------------------------------------------------

    @Test
    @DisplayName("после ошибки дерево всё равно возвращается")
    void treeSurvivesErrors() {
        Diagnostics diagnostics = diagnose("1 +");
        assertTrue(diagnostics.hasErrors());
        assertTrue(diagnostics.renderAll().contains("ожидалось выражение"), diagnostics.renderAll());
    }

    @Test
    @DisplayName("незакрытая скобка называется по имени")
    void unclosedParen() {
        assertTrue(diagnose("(1 + 2").renderAll().contains("закрывающую скобку ')'"));
        assertTrue(diagnose("[1, 2").renderAll().contains("закрывающую скобку ']'"));
    }

    @Test
    @DisplayName("лишнее после выражения — тоже ошибка, а не молчаливо отброшенный хвост")
    void trailingTokens() {
        assertTrue(diagnose("1 2").renderAll().contains("лишнее после выражения"));
    }

    @Test
    @DisplayName("после точки должно стоять имя поля")
    void missingFieldName() {
        assertTrue(diagnose("a.1").renderAll().contains("после точки ожидалось имя поля"));
    }

    @Test
    @DisplayName("мусор внутри списка не завешивает разбор")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void garbageInsideListTerminates() {
        assertTrue(diagnose("[1, , 2]").hasErrors());
        assertTrue(diagnose("[1 2]").hasErrors());
        assertTrue(diagnose("{a: }").hasErrors());
        assertTrue(diagnose("{: 1}").hasErrors());
    }

    @Test
    @DisplayName("пустой ввод — одна понятная ошибка, а не падение")
    void emptyInput() {
        Diagnostics diagnostics = diagnose("");
        assertTrue(diagnostics.hasErrors());
        assertTrue(diagnostics.renderAll().contains("конец файла"), diagnostics.renderAll());
    }

    @Test
    @DisplayName("целое, не влезающее в 64 бита, становится вещественным с предупреждением")
    void hugeIntegerWarns() {
        Source source = Source.ofString("99999999999999999999");
        Diagnostics diagnostics = new Diagnostics(source);
        Expr expr = Parser.parseExpression(Lexer.tokenize(source, diagnostics), diagnostics);

        assertFalse(diagnostics.hasErrors());
        assertTrue(diagnostics.renderAll().contains("не помещается в 64 бита"));
        assertEquals("1.0E20", SExprPrinter.print(expr));
    }
}
