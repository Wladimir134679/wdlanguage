package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.BinaryExpr;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.NewExpr;
import ru.wds.wdl.ast.expr.TryExpr;
import ru.wds.wdl.ast.expr.TryStyle;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.BlockStmt;
import ru.wds.wdl.ast.stmt.DeferStmt;
import ru.wds.wdl.ast.stmt.ExprStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.ThrowStmt;
import ru.wds.wdl.ast.stmt.TryStmt;
import ru.wds.wdl.ast.stmt.UseStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разбор {@code throw} и {@code try}: форма дерева и ошибки разбора. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ErrorParserTest {

    private static Stmt single(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        assertEquals(1, program.statements().size(), "ожидалась одна инструкция");
        return program.statements().get(0);
    }

    /** Текст всех сообщений разбора; пустой, если их нет. */
    private static String problems(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для:\n" + code);
        return diagnostics.renderAll();
    }

    // --- форма дерева --------------------------------------------------------

    @Test
    @DisplayName("throw разбирается в инструкцию с выражением-ошибкой")
    void throwStatement() {
        ThrowStmt thrown = assertInstanceOf(ThrowStmt.class, single("throw new Exception(\"ой\")"));
        assertInstanceOf(NewExpr.class, thrown.error());
    }

    @Test
    @DisplayName("несколько обработчиков лежат в порядке записи")
    void severalHandlers() {
        TryStmt statement = assertInstanceOf(TryStmt.class, single("""
                try {
                    risky()
                } catch (e is IoError) {
                    a()
                } catch (e is Exception) {
                    b()
                }
                """));
        assertEquals(2, statement.handlers().size());
        assertEquals("IoError", statement.handlers().get(0).types().get(0).title());
        assertEquals("Exception", statement.handlers().get(1).types().get(0).title());
        assertFalse(statement.hasFinally());
    }

    @Test
    @DisplayName("несколько типов в одном обработчике и квалифицированное имя")
    void severalTypesInOneHandler() {
        TryStmt statement = assertInstanceOf(TryStmt.class, single("""
                try { risky() } catch (e is IoError, ValueError, db.QueryError) { a() }
                """));
        TryStmt.Catch handler = statement.handlers().get(0);
        assertEquals(3, handler.types().size());
        assertEquals("db", handler.types().get(2).alias());
        assertEquals("db.QueryError", handler.types().get(2).title());
    }

    @Test
    @DisplayName("catch без типа ловит всё")
    void catchWithoutType() {
        TryStmt statement = assertInstanceOf(TryStmt.class,
                single("try { risky() } catch (e) { a() }"));
        assertTrue(statement.handlers().get(0).catchesEverything());
        assertEquals("e", statement.handlers().get(0).name());
    }

    @Test
    @DisplayName("try без единого catch законен, если есть finally")
    void tryWithoutHandlers() {
        TryStmt statement = assertInstanceOf(TryStmt.class,
                single("try { risky() } finally { cleanup() }"));
        assertTrue(statement.handlers().isEmpty());
        assertTrue(statement.hasFinally());
    }

    @Test
    @DisplayName("finally стоит после всех обработчиков")
    void handlersThenFinally() {
        TryStmt statement = assertInstanceOf(TryStmt.class,
                single("try { a() } catch (e) { b() } finally { c() }"));
        assertEquals(1, statement.handlers().size());
        assertEquals(1, statement.finallyBlock().statements().size());
    }

    // --- ошибки разбора ------------------------------------------------------

    @Test
    @DisplayName("тело try — всегда блок: без скобок границу пришлось бы угадывать")
    void tryBodyIsAlwaysBlock() {
        assertTrue(problems("try risky() catch (e) { a() }").contains("блоком в фигурных скобках"));
        assertTrue(problems("try { a() } catch (e) b()").contains("блоком в фигурных скобках"));
    }

    @Test
    @DisplayName("catch и finally без своего try называются по имени")
    void orphanHandlers() {
        assertTrue(problems("catch (e) { a() }").contains("'catch' без 'try'"));
        assertTrue(problems("finally { a() }").contains("'finally' без 'try'"));
    }

    @Test
    @DisplayName("try без catch и без finally — недописанная конструкция, а не блок")
    void bareTry() {
        assertTrue(problems("try { a() }").contains("хотя бы один 'catch'"));
    }

    @Test
    @DisplayName("return, break и continue в finally запрещены при разборе")
    void noEscapeFromFinally() {
        assertTrue(problems("""
                def f() {
                    try { return 1; } finally { return 2; }
                }
                """).contains("'return' в блоке 'finally' запрещён"));
        assertTrue(problems("""
                for (i in [1]) {
                    try { a() } finally { break }
                }
                """).contains("'break' в блоке 'finally' запрещён"));
        assertTrue(problems("""
                for (i in [1]) {
                    try { a() } finally { continue }
                }
                """).contains("'continue' в блоке 'finally' запрещён"));
    }

    @Test
    @DisplayName("функция внутри finally возвращает из себя самой — это не выход из finally")
    void returnInsideNestedFunctionIsFine() {
        assertInstanceOf(TryStmt.class, single("""
                try { a() } finally { f = def() { return 1; } }
                """));
    }

    @Test
    @DisplayName("один тип дважды в обработчике — ошибка, как дважды подмешанный трейт")
    void duplicateType() {
        assertTrue(problems("try { a() } catch (e is IoError, IoError) { b() }")
                .contains("указан дважды"));
    }

    @Test
    @DisplayName("после 'catch (' обязательно имя")
    void catchNeedsName() {
        assertTrue(problems("try { a() } catch (is IoError) { b() }")
                .contains("ошибка ляжет в переменную"));
    }

    @Test
    @DisplayName("ошибка в try не съедает то, что идёт после неё")
    void recoveryKeepsNextStatement() {
        Source source = Source.ofString("catch (e) { a() }\nx = 1");
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors());
        // Последняя инструкция обязана разобраться: одна ошибка не разваливает файл.
        assertInstanceOf(AssignStmt.class, program.statements().get(program.statements().size() - 1));
    }

    @Test
    @DisplayName("throw без выражения называет причину один раз")
    void brokenThrow() {
        assertTrue(problems("throw )").contains("ожидалось выражение"));
    }

    // --- короткие формы try? и try! ------------------------------------------

    @Test
    @DisplayName("try? и try! — выражения, а не конструкция с блоком")
    void shortFormsAreExpressions() {
        AssignStmt optional = assertInstanceOf(AssignStmt.class, single("n = try? parse(text)"));
        assertEquals(TryStyle.OPTIONAL,
                assertInstanceOf(TryExpr.class, optional.value()).style());

        AssignStmt forced = assertInstanceOf(AssignStmt.class, single("port = try! config()"));
        assertEquals(TryStyle.FORCED, assertInstanceOf(TryExpr.class, forced.value()).style());
    }

    @Test
    @DisplayName("операнд короткой формы — вызов целиком, но не соседнее сложение")
    void shortFormBindsLikeUnary() {
        AssignStmt whole = assertInstanceOf(AssignStmt.class, single("n = try? config.port()"));
        TryExpr wrapped = assertInstanceOf(TryExpr.class, whole.value());
        assertInstanceOf(CallExpr.class, wrapped.inner());

        // 'try? f() + 1' — это '(try? f()) + 1': сложение слабее унарной силы.
        AssignStmt sum = assertInstanceOf(AssignStmt.class, single("n = try? f() + 1"));
        BinaryExpr plus = assertInstanceOf(BinaryExpr.class, sum.value());
        assertInstanceOf(TryExpr.class, plus.left());
    }

    @Test
    @DisplayName("try? отдельной инструкцией — это вызов с проглоченной ошибкой")
    void shortFormIsAStatement() {
        ExprStmt statement = assertInstanceOf(ExprStmt.class, single("try? save()"));
        assertInstanceOf(TryExpr.class, statement.expr());
    }

    @Test
    @DisplayName("короткая форма пишется слитно")
    void shortFormIsWrittenTogether() {
        assertTrue(problems("n = try ? f()").contains("пишется слитно"));
        assertTrue(problems("n = try ! f()").contains("пишется слитно"));
    }

    @Test
    @DisplayName("голый try в позиции выражения объясняет, чего от него ждут")
    void bareTryInExpression() {
        assertTrue(problems("n = try { f() }").contains("в позиции выражения пишется коротко"));
    }

    // --- defer ---------------------------------------------------------------

    @Test
    @DisplayName("блок помечается флагом, если в нём есть отложенное действие")
    void blockKnowsAboutDefer() {
        BlockStmt withDefer = assertInstanceOf(BlockStmt.class, single("{ defer close(); work() }"));
        assertTrue(withDefer.hasDefer());
        assertInstanceOf(DeferStmt.class, withDefer.statements().get(0));

        assertFalse(assertInstanceOf(BlockStmt.class, single("{ work() }")).hasDefer());
    }

    @Test
    @DisplayName("defer вложенного блока принадлежит ему, а не внешнему")
    void deferBelongsToItsOwnBlock() {
        BlockStmt outer = assertInstanceOf(BlockStmt.class, single("{ { defer close() } work() }"));
        assertFalse(outer.hasDefer(), "внешний блок отложенного не заводил");
        assertTrue(assertInstanceOf(BlockStmt.class, outer.statements().get(0)).hasDefer());
    }

    @Test
    @DisplayName("тело defer — одна инструкция или блок")
    void deferBodyIsStatementOrBlock() {
        assertInstanceOf(DeferStmt.class,
                assertInstanceOf(BlockStmt.class, single("{ defer f() }")).statements().get(0));
        assertInstanceOf(DeferStmt.class,
                assertInstanceOf(BlockStmt.class, single("{ defer { f(); g() } }")).statements().get(0));
    }

    // --- use -----------------------------------------------------------------

    @Test
    @DisplayName("use разбирает список ресурсов в порядке захвата")
    void useBindsResourcesInOrder() {
        UseStmt statement = assertInstanceOf(UseStmt.class,
                single("use (src = open(from), dst = create(to)) { copy(src, dst) }"));
        assertEquals(2, statement.resources().size());
        assertEquals("src", statement.resources().get(0).name());
        assertEquals("dst", statement.resources().get(1).name());
        assertEquals(1, statement.body().statements().size());
    }

    @Test
    @DisplayName("тело use — всегда блок, а список ресурсов не бывает пустым")
    void useNeedsBlockAndResources() {
        assertTrue(problems("use (f = open(p)) f.close()").contains("блоком в фигурных скобках"));
        assertTrue(problems("use () { a() }").contains("ожидалось имя"));
    }

    @Test
    @DisplayName("слева от '=' в use стоит имя, а не обращение")
    void useBindsNamesOnly() {
        assertTrue(problems("use (a.b = open(p)) { c() }").contains("знак '='"));
    }

    @Test
    @DisplayName("два ресурса под одним именем — ошибка")
    void useRejectsDuplicateNames() {
        assertTrue(problems("use (f = open(a), f = open(b)) { c() }").contains("уже занято"));
    }

    @Test
    @DisplayName("return, break и continue в теле defer запрещены при разборе")
    void noEscapeFromDefer() {
        assertTrue(problems("""
                def f() {
                    defer return 1;
                }
                """).contains("'return' в теле 'defer' запрещён"));
        assertTrue(problems("""
                for (i in [1]) {
                    defer break
                }
                """).contains("'break' в теле 'defer' запрещён"));
    }
}
