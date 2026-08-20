package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.expr.Argument;
import ru.wds.wdl.ast.expr.Decorator;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.DecoratedStmt;
import ru.wds.wdl.ast.stmt.DefDeclStmt;
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

/** Разбор декораторов: форма {@code @[выражение](аргументы)} и то, на что её можно повесить. */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DecoratorParserTest {

    private static Stmt single(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        assertEquals(1, program.statements().size(), "ожидалась одна инструкция");
        return program.statements().get(0);
    }

    private static DecoratedStmt decorated(String code) {
        return assertInstanceOf(DecoratedStmt.class, single(code));
    }

    private static String errorOf(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    @Test
    @DisplayName("декоратор без скобок и со скобками — одна и та же запись")
    void parenthesesAreOptional() {
        DecoratedStmt bare = decorated("@[reg] def app() {}");
        DecoratedStmt called = decorated("@[reg]() def app() {}");
        assertEquals(1, bare.decorators().size());
        assertTrue(bare.decorators().get(0).arguments().isEmpty());
        assertEquals(bare.decorators().get(0).toString(), called.decorators().get(0).toString());
    }

    @Test
    @DisplayName("стек декораторов хранится в порядке записи, сверху вниз")
    void stackKeepsWrittenOrder() {
        DecoratedStmt stmt = decorated("""
                @[timer]("ms")
                @[log]("info")
                def command() {}
                """);
        assertEquals(2, stmt.decorators().size());
        assertEquals("timer", stmt.decorators().get(0).callee().toString());
        assertEquals("log", stmt.decorators().get(1).callee().toString());
        assertEquals("command", stmt.name());
    }

    @Test
    @DisplayName("внутри скобок — обычное выражение, любой сложности")
    void calleeIsAnyExpression() {
        Decorator decorator = decorated("""
                @[t.commands["all"]().getDeco()](1)
                def abcd() {}
                """).decorators().get(0);
        assertEquals("t.commands[\"all\"]().getDeco()", decorator.callee().toString());
        assertEquals(1, decorator.arguments().size());
    }

    @Test
    @DisplayName("аргументы декоратора — обычный список: имена через двоеточие, раскрытие звёздочкой")
    void argumentsFollowTheOneRule() {
        Decorator decorator = decorated("""
                @[log]("debug", *extra, prefix: "", **options)
                def command() {}
                """).decorators().get(0);
        assertEquals(4, decorator.arguments().size());
        assertEquals(Argument.Kind.SPREAD, decorator.arguments().get(1).kind());
        assertEquals(Argument.Kind.NAMED, decorator.arguments().get(2).kind());
        assertEquals(Argument.Kind.NAMED_SPREAD, decorator.arguments().get(3).kind());
    }

    @Test
    @DisplayName("целью бывает функция, класс и трейт")
    void targetsAreDeclarations() {
        assertInstanceOf(DefDeclStmt.class, decorated("@[d] def f() {}").declaration());
        assertInstanceOf(ClassDeclStmt.class, decorated("@[d] class C {}").declaration());
        assertInstanceOf(TraitDeclStmt.class, decorated("@[d] trait T {}").declaration());
    }

    @Test
    @DisplayName("декораторы стоят перед модификатором, а не после")
    void decoratorsComeBeforeModifiers() {
        DecoratedStmt stmt = decorated("@[d] synchronized def f() {}");
        assertTrue(assertInstanceOf(DefDeclStmt.class, stmt.declaration()).function().isSynchronized());
    }

    @Test
    @DisplayName("после '@' обязательна '[': без неё запись неоднозначна")
    void bracketIsRequired() {
        assertTrue(errorOf("@reg def app() {}").contains("после '@' ожидалась '['"));
    }

    @Test
    @DisplayName("пустое '@[]' — ошибка: декоратору неоткуда взяться")
    void emptyBracketsAreAnError() {
        assertTrue(errorOf("@[] def app() {}").contains("в '@[]' нет выражения"));
    }

    @Test
    @DisplayName("декоратор вешается только на объявление")
    void targetMustBeADeclaration() {
        assertTrue(errorOf("@[reg] x = 1").contains("декоратор вешается на объявление"));
        // Анонимная функция в позиции выражения — вторая версия языка.
        assertTrue(errorOf("@[reg] def() => 1").contains("декоратор вешается на объявление"));
    }
}
