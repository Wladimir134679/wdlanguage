package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.Modifier;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор оператора: имя в обратных кавычках, слово {@code mirror} и всё, что язык
 * запрещает написать.
 * <p>
 * Проверок запретов здесь больше, чем проверок удачного разбора, и это не перекос:
 * имя оператора и его арность известны прямо по тексту, поэтому <b>вся</b> диагностика
 * перегрузки живёт в разборе, а не в выполнении.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class OperatorParserTest {

    private static Program parse(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        return program;
    }

    private static ClassDeclStmt classOf(String code) {
        Program program = parse(code);
        return assertInstanceOf(ClassDeclStmt.class, program.statements().get(0));
    }

    /** Форма объявленного члена скобочной записью — то, за что отвечает парсер. */
    private static String shape(FunctionExpr method) {
        return SExprPrinter.print(method);
    }

    private static String errorOf(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- что разбирается -----------------------------------------------------

    @Test
    @DisplayName("оператор — обычный метод, только имя записано в кавычках")
    void binaryOperator() {
        ClassDeclStmt point = classOf("class Point(x, y) { def `+`(right) => x + right }");

        FunctionExpr plus = point.methods().get(0);
        assertEquals("(def `+` right)", shape(plus));
        assertEquals("+", plus.name());
        assertEquals("+", plus.memberName());
        assertTrue(plus.modifiers().isEmpty());
    }

    @Test
    @DisplayName("'mirror' — модификатор, а имя в дереве остаётся честным")
    void mirrorOperator() {
        ClassDeclStmt point = classOf("class Point(x, y) { mirror def `/`(left) => left / x }");

        FunctionExpr mirror = point.methods().get(0);
        assertEquals("(mirror-def `/` left)", shape(mirror));
        assertEquals("/", mirror.name());
        // В таблице методов он лежит под мангленным именем: прямой и зеркальный — разные.
        assertEquals("/@", mirror.memberName());
        assertEquals(java.util.Set.of(Modifier.MIRROR), mirror.modifiers());
    }

    @Test
    @DisplayName("унарный отличается арностью и занимает свою ячейку рядом с бинарным")
    void unaryOperator() {
        ClassDeclStmt point = classOf("""
                class Point(x, y) {
                    def `-`(right) => x - right
                    def `-`() => 0 - x
                }
                """);

        assertEquals("(def `-` right)", shape(point.methods().get(0)));
        assertEquals("(def `-`)", shape(point.methods().get(1)));
        assertEquals("-", point.methods().get(0).memberName());
        assertEquals("-()", point.methods().get(1).memberName());
    }

    @Test
    @DisplayName("оператор в трейте бывает и реализацией, и требованием")
    void operatorInTrait() {
        Program program = parse("""
                trait Ordered {
                    def `<=>`(other)
                }
                trait Sized {
                    def `<=>`(other) => size - other.size
                }
                """);

        TraitDeclStmt ordered = assertInstanceOf(TraitDeclStmt.class, program.statements().get(0));
        assertEquals(1, ordered.requirements().size());
        assertEquals("<=>", ordered.requirements().get(0).name());
        assertFalse(ordered.requirements().get(0).mirror());

        TraitDeclStmt sized = assertInstanceOf(TraitDeclStmt.class, program.statements().get(1));
        assertEquals("(def `<=>` other)", shape(sized.methods().get(0)));
    }

    @Test
    @DisplayName("'mirror' можно объявить и в расширении: члены там те же")
    void operatorInExtend() {
        Stmt extended = parse("""
                extend Point {
                    def `*`(right) => right
                    mirror def `*`(left) => left
                }
                """).statements().get(0);

        assertInstanceOf(ru.wds.wdl.ast.stmt.ExtendStmt.class, extended);
    }

    @Test
    @DisplayName("после точки имя в кавычках читается: 'p.`+`' — обычное обращение")
    void quotedNameAfterDot() {
        parse("f = p.`+`");
    }

    @Test
    @DisplayName("метод по имени 'mirror' объявляется по-прежнему: слово контекстное")
    void mirrorStaysName() {
        ClassDeclStmt box = classOf("class Box(v) { def mirror() => v }");

        assertEquals("(def mirror)", shape(box.methods().get(0)));
    }

    // --- что не разбирается --------------------------------------------------

    @Test
    @DisplayName("имя не из списка — не оператор")
    void unknownOperator() {
        assertTrue(errorOf("class P(x) { def `=<`(r) => r }").contains("'=<' — не оператор"));
    }

    @Test
    @DisplayName("производные записи не объявляют: у них один член на всех")
    void derivedOperators() {
        assertTrue(errorOf("class P(x) { def `!=`(r) => r }")
                .contains("оператор '!=' не объявляют"));
        assertTrue(errorOf("class P(x) { def `<`(r) => r }")
                .contains("четыре сравнения закрывает один член '<=>'"));
        assertTrue(errorOf("class P(x) { def `has`(r) => r }")
                .contains("оператор 'has' не объявляют"));
    }

    @Test
    @DisplayName("'&&', 'is' и присваивание не перегружаются вовсе — с причиной")
    void forbiddenOperators() {
        assertTrue(errorOf("class P(x) { def `&&`(r) => r }")
                .contains("оператор '&&' не перегружается"));
        assertTrue(errorOf("class P(x) { def `is`(r) => r }")
                .contains("оператор 'is' не перегружается"));
        assertTrue(errorOf("class P(x) { def `+=`(r) => r }")
                .contains("составное 'p += q' и так зовёт член '+'"));
    }

    @Test
    @DisplayName("арность проверяется при разборе: у бинарного один параметр")
    void arity() {
        assertTrue(errorOf("class P(x) { def `*`(a, b) => a }")
                .contains("у оператора '*' один параметр"));
        assertTrue(errorOf("class P(x) { def `*`() => x }")
                .contains("у оператора '*' один параметр"));
        assertTrue(errorOf("class P(x) { def `~`(r) => r }")
                .contains("у оператора '~' параметров не бывает"));
        assertTrue(errorOf("class P(x) { def `+`(a, b) => a }")
                .contains("либо один параметр"));
        // Ни остатка, ни значения по умолчанию у оператора не бывает.
        assertTrue(errorOf("class P(x) { def `+`(*args) => x }").contains("оператор"));
        assertTrue(errorOf("class P(x) { def `+`(r = 1) => r }").contains("оператор"));
    }

    @Test
    @DisplayName("'mirror' бывает только у бинарного оператора и только там, где есть стороны")
    void mirrorLimits() {
        assertTrue(errorOf("class P(x) { mirror def foo(r) => r }")
                .contains("'mirror' бывает только у оператора"));
        assertTrue(errorOf("class P(x) { mirror def `in`(r) => r }")
                .contains("у оператора 'in' не бывает 'mirror'"));
        assertTrue(errorOf("class P(x) { mirror def `~`() => x }")
                .contains("'mirror' бывает только у бинарного оператора"));
        assertTrue(errorOf("class P(x) { mirror def `-`() => x }")
                .contains("'mirror' бывает только у бинарного оператора"));
    }

    @Test
    @DisplayName("локальных операторов в языке нет: ни с 'mirror', ни без него")
    void noLocalOperators() {
        assertTrue(errorOf("def `+`(a) => a").contains("локальных операторов в языке нет"));
        assertTrue(errorOf("mirror def `+`(a) => a").contains("локальных операторов в языке нет"));
    }

    @Test
    @DisplayName("имя в кавычках переменной не бывает")
    void quotedNameIsNotVariable() {
        assertTrue(errorOf("x = `+` + 1")
                .contains("имя в обратных кавычках бывает только у члена типа и в обращении"));
    }

    @Test
    @DisplayName("прямой и зеркальный — разные члены, а два прямых — дубликат")
    void duplicates() {
        parse("class P(x) { def `+`(r) => r\n mirror def `+`(l) => l }");
        assertTrue(errorOf("class P(x) { def `+`(r) => r\n def `+`(o) => o }")
                .contains("уже объявлен"));
    }

    @Test
    @DisplayName("после ошибки в имени оператора разбор продолжается со следующей строки")
    void recovery() {
        String errors = errorOf("""
                class P(x) {
                    def `=<`(r) => r
                    def size() => x
                }
                y = 1
                """);

        assertTrue(errors.contains("не оператор"), errors);
        assertFalse(errors.contains("size"), errors);
    }
}
