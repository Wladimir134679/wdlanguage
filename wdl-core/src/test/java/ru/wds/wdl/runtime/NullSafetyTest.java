package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;
import ru.wds.wdl.value.Value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Безопасное обращение {@code ?.}, подстановка {@code ??} и запись по нужде
 * {@code ??=} — три записи с одним вопросом: «пусто ли здесь».
 */
class NullSafetyTest {

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

    /** Запускает скрипт и возвращает всё, что тот напечатал. */
    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(printed::append));
        return printed.toString();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    /** Тексты ошибок разбора — с деревом, которое возвращается всё равно. */
    private static String parseErrors(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), "ожидались ошибки разбора");
        return diagnostics.renderAll();
    }

    // --- обращение -----------------------------------------------------------

    @Test
    @DisplayName("пустой получатель пропускает звено, непустой ведёт себя как обычно")
    void optionalAccess() {
        assertEquals("db.local", show("{ db: { host: \"db.local\" } }?.db?.host"));
        assertEquals("null", show("null?.db"));
        // Ключа нет вовсе — обращение к объекту и без того даёт null, '?.' тут ни при чём.
        assertEquals("null", show("{ db: null }?.db?.host"));
    }

    @Test
    @DisplayName("замыкание идёт до конца цепочки, а не до следующего звена")
    void wholeChainIsSkipped() {
        // Ради этого '?.' и делался: 'user' пуст, и '.name' не выполняется вовсе.
        assertEquals("null", show("null?.profile.name"));
        assertEquals("null", show("null?.profile[0].name"));
        // Скобки закрывают цепочку: дальше обращение идёт уже по её значению.
        assertEquals(ErrorKind.TYPE, errorOf("println((null?.profile).name)").kind());
    }

    @Test
    @DisplayName("безопасный вызов: метода в языке нет, поэтому '?.' закрывает и вызов")
    void optionalCall() {
        assertEquals("null" + System.lineSeparator(), run("logger = null; println(logger?.info(\"go\"))"));
        assertEquals("null" + System.lineSeparator(), run("h = { onClick: null }; println(h.onClick?.())"));
        assertEquals("5" + System.lineSeparator(),
                run("h = { size: def() => 5 }; println(h.size?.())"));
    }

    @Test
    @DisplayName("у пропущенного звена не вычисляются ни аргументы, ни ключ")
    void skippedLinkEvaluatesNothing() {
        assertEquals("", run("def loud(x) { println(\"звали\"); return x; }\n"
                + "missing = null\n"
                + "missing?.send(loud(1))"));
        assertEquals("", run("def key() { println(\"ключ\"); return 0; }\n"
                + "missing = null\n"
                + "value = missing?.[key()]"));
    }

    @Test
    @DisplayName("'?.' закрывает только пустого получателя, остальные ошибки остаются")
    void optionalHidesOnlyNullReceiver() {
        assertEquals("3", show("\"abc\"?.size"));
        assertEquals(ErrorKind.INDEX, errorOf("println([1, 2]?.[9])").kind());
        assertEquals(ErrorKind.NAME, errorOf("println(\"abc\"?.nosuch)").kind());
        assertEquals(ErrorKind.CALL, errorOf("println(5?.())").kind());
    }

    @Test
    @DisplayName("граница цепочки не утекает наружу: соседние цепочки независимы")
    void chainsDoNotLeak() {
        assertEquals("[\"-\", \"есть\"]",
                show("[null, { title: \"есть\" }].map(r => r?.title ?? \"-\")"));
        // Две цепочки в одном выражении: первая пропущена, вторая отработала целиком.
        assertEquals("-3", show("(null?.a ?? \"-\") + \"abc\"?.size"));
    }

    @Test
    @DisplayName("безопасное обращение слева от '=' — отказ на разборе")
    void optionalIsNotAPlace() {
        assertTrue(parseErrors("config = {}\nconfig?.db = 1")
                .contains("'?.' не может стоять слева от '='"));
        assertTrue(parseErrors("config = {}\nconfig?.db.host = 1")
                .contains("'?.' не может стоять слева от '='"));
        assertTrue(parseErrors("config = {}\nconfig?.count += 1")
                .contains("'?.' не может стоять слева от '+='"));
    }

    // --- подстановка ---------------------------------------------------------

    @Test
    @DisplayName("'??' спрашивает про пустоту, а '||' — про ложность")
    void coalesceAsksAboutNull() {
        assertEquals("localhost", show("null ?? \"localhost\""));
        assertEquals("db.local", show("\"db.local\" ?? \"localhost\""));
        // Вся разница между ними — в ложном значении: выключённое остаётся выключенным.
        assertEquals("false", show("false ?? true"));
        assertEquals("true", show("false || true"));
        // Ноль и пустая строка в этом языке истинны, поэтому их не подменяет ни та,
        // ни другая запись.
        assertEquals("0", show("0 ?? 8080"));
        assertEquals("0", show("0 || 8080"));
    }

    @Test
    @DisplayName("правая часть '??' вычисляется только при нужде")
    void coalesceIsLazy() {
        assertEquals("", run("def loud() { println(\"звали\"); return 1; }\nvalue = \"есть\" ?? loud()"));
        assertEquals("звали" + System.lineSeparator(),
                run("def loud() { println(\"звали\"); return 1; }\nvalue = null ?? loud()"));
    }

    @Test
    @DisplayName("'??' сильнее сравнений: ответ подстановки сравнивается, а не наоборот")
    void coalescePower() {
        assertEquals("true", show("null ?? \"anon\" == \"anon\""));
        assertEquals("false", show("null ?? 0 > 5"));
        assertEquals("localhost", show("null ?? null ?? \"localhost\""));
    }

    @Test
    @DisplayName("'??' не может быть образцом ветки 'case'")
    void coalesceIsNotAPattern() {
        assertTrue(parseErrors("x = match (1) { case ?? 5 -> 1 else -> 2 }")
                .contains("не может быть образцом ветки 'case'"));
    }

    // --- запись по нужде -----------------------------------------------------

    @Test
    @DisplayName("'??=' пишет только в пустое место")
    void coalesceAssign() {
        assertEquals("{\"retries\": 0, \"timeout\": 30}" + System.lineSeparator(),
                run("options = { retries: 0 }\noptions.retries ??= 3\noptions.timeout ??= 30\n"
                        + "println(options)"));
        assertEquals("false" + System.lineSeparator(),
                run("o = { verbose: false }\no.verbose ??= true\nprintln(o.verbose)"));
    }

    @Test
    @DisplayName("'??=' не вычисляет правую часть, когда писать некуда")
    void coalesceAssignIsLazy() {
        assertEquals("", run("def loud() { println(\"звали\"); return 1; }\n"
                + "o = { x: 1 }\no.x ??= loud()"));
        assertEquals("звали" + System.lineSeparator(), run("def loud() { println(\"звали\"); return 1; }\n"
                + "o = { x: null }\no.x ??= loud()"));
    }

    @Test
    @DisplayName("место записи у '??=' вычисляется один раз — как у всех составных")
    void coalesceAssignResolvesPlaceOnce() {
        assertEquals("ключ" + System.lineSeparator(),
                run("def key() { println(\"ключ\"); return 0; }\nitems = [1]\nitems[key()] ??= 5"));
    }

    @Test
    @DisplayName("'??=' требует объявленного имени — та же мерка, что у '+='")
    void coalesceAssignNeedsDeclaredName() {
        assertEquals(ErrorKind.NAME, errorOf("host ??= \"localhost\"").kind());
        // Заведённое имя с пустым значением, наоборот, дописывается.
        assertEquals("localhost" + System.lineSeparator(),
                run("host = null\nhost ??= \"localhost\"\nprintln(host)"));
    }

    @Test
    @DisplayName("константа: пустую '??=' заполняет с отказом, непустую не трогает вовсе")
    void coalesceAssignMeetsConstant() {
        // Следствие правила «не пусто — не пишем»: до проверки константности дело
        // не доходит, потому что записи не происходит.
        assertEquals("1" + System.lineSeparator(), run("const x = 1\nx ??= 2\nprintln(x)"));
        assertEquals(ErrorKind.DECLARATION, errorOf("const x = null\nx ??= 2").kind());
    }
}
