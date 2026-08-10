package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор {@code import}: обе формы записи пути, именованный импорт и сообщения
 * об ошибках. Приведение пути к ключу модуля здесь не проверяется — им занимается
 * загрузчик, а не парсер.
 */
class ImportParserTest {

    private static Program parse(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        return program;
    }

    private static ImportStmt single(String code) {
        Program program = parse(code);
        assertEquals(1, program.statements().size(), "ожидалась одна инструкция");
        return assertInstanceOf(ImportStmt.class, program.statements().get(0));
    }

    private static Diagnostics diagnose(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), "ожидались ошибки разбора");
        return diagnostics;
    }

    @Test
    @DisplayName("простой импорт по имени")
    void plainImport() {
        ImportStmt stmt = single("import math");

        assertEquals("math", stmt.path());
        assertNull(stmt.alias());
        assertFalse(stmt.hasAlias());
    }

    @Test
    @DisplayName("путь через точку — это путь по каталогам, а не обращение")
    void dottedPath() {
        assertEquals("lib/math", single("import lib.math").path());
        assertEquals("a/b/c", single("import a.b.c").path());
    }

    @Test
    @DisplayName("путь строкой: любые символы, слэш как разделитель каталогов")
    void stringPath() {
        assertEquals("lib/math", single("import \"lib/math\"").path());
        assertEquals("lib/math.wdl", single("import \"lib/math.wdl\"").path());
        assertEquals("/math", single("import \"/math\"").path());
        assertEquals("имя-модуля (1)", single("import \"имя-модуля (1)\"").path());
    }

    @Test
    @DisplayName("именованный импорт запоминает имя и его место")
    void aliasedImport() {
        ImportStmt stmt = single("import lib.math as m");

        assertEquals("lib/math", stmt.path());
        assertEquals("m", stmt.alias());
        assertTrue(stmt.hasAlias());
        assertEquals("m", "import lib.math as m".substring(
                stmt.aliasSpan().start(), stmt.aliasSpan().end()));
    }

    @Test
    @DisplayName("импорт разбирается везде, где разбирается инструкция")
    void importIsAnOrdinaryStatement() {
        Program program = parse("fun f() { import lib.math as m; return m.PI; }");
        assertEquals(1, program.statements().size());

        Program block = parse("{ import math }");
        assertEquals(1, block.statements().size());
    }

    @Test
    @DisplayName("несколько импортов подряд — по одному на строку, без разделителей")
    void severalImports() {
        assertEquals(3, parse("import a\nimport b as c\nimport \"d/e\"").statements().size());
    }

    @Test
    @DisplayName("после import обязателен путь")
    void pathIsRequired() {
        assertTrue(diagnose("import").all().get(0).message().contains("ожидался путь модуля"));
        assertTrue(diagnose("import 5").all().get(0).message().contains("ожидался путь модуля"));
    }

    @Test
    @DisplayName("ключевое слово в пути через точку — совет записать строкой")
    void keywordInDottedPath() {
        String message = diagnose("import lib.class").all().get(0).message();

        assertTrue(message.contains("после точки в пути модуля ожидалось имя"), message);
        assertTrue(message.contains("записывается строкой"), message);
    }

    @Test
    @DisplayName("после as обязательно имя")
    void aliasIsRequired() {
        assertTrue(diagnose("import math as").all().get(0).message().contains("после 'as' ожидалось имя"));
        assertTrue(diagnose("import math as \"m\"").all().get(0).message().contains("после 'as' ожидалось имя"));
    }

    @Test
    @DisplayName("испорченный импорт не съедает следующую инструкцию")
    void recoveryKeepsNextStatement() {
        Source source = Source.ofString("import lib.\nx = 1");
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);

        assertTrue(diagnostics.hasErrors());
        assertEquals(2, program.statements().size(), "вторая инструкция должна разобраться");
    }
}
