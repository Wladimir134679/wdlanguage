package ru.wds.wdl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.ast.stmt.PropertyStyle;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор свойств: обе формы записи, скрытое поле, требования в трейте и все запреты,
 * которые язык проверяет прямо при разборе.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PropertyParserTest {

    private static Stmt single(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "неожиданные ошибки:\n" + diagnostics.renderAll());
        assertEquals(1, program.statements().size(), "ожидалась одна инструкция");
        return program.statements().get(0);
    }

    private static ClassDeclStmt classOf(String code) {
        return assertInstanceOf(ClassDeclStmt.class, single(code));
    }

    private static PropertyDecl only(String code) {
        ClassDeclStmt declared = classOf(code);
        assertEquals(1, declared.properties().size(), "ожидалось одно свойство");
        return declared.properties().get(0);
    }

    private static String errorOf(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка разбора для: " + code);
        return diagnostics.renderAll();
    }

    // --- формы записи --------------------------------------------------------

    @Test
    @DisplayName("короткая форма '=>' — свойство только для чтения")
    void arrowIsReadOnly() {
        PropertyDecl area = only("class Rect(w, h) { property area => this.w * this.h }");

        assertEquals("area", area.name());
        assertEquals(PropertyStyle.ARROW, area.style());
        assertNotNull(area.getter());
        assertNull(area.setter());
        assertFalse(area.hasBackingField());
        assertFalse(area.getter().isRequirement());
    }

    @Test
    @DisplayName("блок с get и set: два аксессора у одного имени")
    void blockWithBothAccessors() {
        PropertyDecl size = only("""
                class Rect(w, h) {
                    property size {
                        def get() => this.w
                        def set(value) {
                            this.w = value
                            this.h = value
                        }
                    }
                }
                """);

        assertEquals(PropertyStyle.BLOCK, size.style());
        assertNotNull(size.getter());
        assertNotNull(size.setter());
        assertEquals(1, size.setter().function().params().size());
        assertTrue(size.getter().function().params().isEmpty());
    }

    @Test
    @DisplayName("скрытое поле: начальное значение — обычное выражение")
    void backingFieldKeepsInitial() {
        PropertyDecl x = only("""
                class Box() {
                    property x = 1 + 1 {
                        def get() => field
                        def set(value) { field = value }
                    }
                }
                """);

        assertTrue(x.hasBackingField());
        assertEquals("(+ 1 1)", SExprPrinter.print(x.initial()));
    }

    @Test
    @DisplayName("имя аксессора несёт свойство: 'Rect.area (get)' попадёт в трассировку")
    void accessorCarriesTitle() {
        assertEquals("Rect.area (get)",
                only("class Rect(w, h) { property area => this.w }").getter().function().name());
    }

    @Test
    @DisplayName("свойство соседствует с методами и не мешает им")
    void propertiesLiveNextToMethods() {
        ClassDeclStmt rect = classOf("""
                class Rect(w, h) {
                    property area => this.w * this.h
                    def grow() { this.w += 1 }
                    def Rect() { this.w = 1 }
                }
                """);

        assertEquals(1, rect.properties().size());
        assertEquals(1, rect.methods().size());
        assertTrue(rect.hasConstructor());
    }

    // --- контекстное слово ---------------------------------------------------

    @Test
    @DisplayName("'property' — контекстное слово: метод и переменная с таким именем живы")
    void propertyIsNotAKeyword() {
        assertEquals(1, classOf("class A() { def property() => 1 }").methods().size());
        Source source = Source.ofString("property = 5\nfield = property + 1");
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> diagnostics.renderAll());
    }

    // --- трейт ---------------------------------------------------------------

    @Test
    @DisplayName("в трейте аксессор без тела — требование")
    void traitAccessorWithoutBodyIsRequirement() {
        TraitDeclStmt sized = assertInstanceOf(TraitDeclStmt.class, single("""
                trait MutableSized {
                    property size {
                        def get()
                        def set(value)
                    }
                }
                """));

        PropertyDecl size = sized.properties().get(0);
        assertTrue(size.getter().isRequirement());
        assertTrue(size.setter().isRequirement());
        assertNull(size.getter().function());
    }

    @Test
    @DisplayName("в трейте требование чтения и готовая запись уживаются в одном свойстве")
    void traitMixesRequirementAndBody() {
        TraitDeclStmt mixed = assertInstanceOf(TraitDeclStmt.class, single("""
                trait Half {
                    property size {
                        def get()
                        def set(value) { this.half = value / 2 }
                    }
                }
                """));

        PropertyDecl size = mixed.properties().get(0);
        assertTrue(size.getter().isRequirement());
        assertFalse(size.setter().isRequirement());
    }

    // --- запреты -------------------------------------------------------------

    @Test
    @DisplayName("свойство только для записи не заводится")
    void setterWithoutGetter() {
        assertTrue(errorOf("class Box() { property x { def set(value) { this.y = value } } }")
                .contains("нет 'def get()'"));
    }

    @Test
    @DisplayName("у get нет параметров, у set ровно один")
    void accessorArity() {
        assertTrue(errorOf("class Box() { property x { def get(a) => a } }")
                .contains("не принимает параметров"));
        assertTrue(errorOf("class Box() { property x { def get() => 1 def set() { } } }")
                .contains("ровно один параметр"));
        assertTrue(errorOf("class Box() { property x { def get() => 1 def set(a, b) { } } }")
                .contains("ровно один параметр"));
    }

    @Test
    @DisplayName("у аксессора не бывает остатка и значения по умолчанию")
    void accessorHasNoRestOrDefault() {
        assertTrue(errorOf("class Box() { property x { def get() => 1 def set(*args) { } } }")
                .contains("не бывает остатка"));
        assertTrue(errorOf("class Box() { property x { def get() => 1 def set(v = 1) { } } }")
                .contains("значения по умолчанию"));
    }

    @Test
    @DisplayName("у свойства бывают только get и set")
    void onlyGetAndSet() {
        assertTrue(errorOf("class Box() { property x { def read() => 1 } }")
                .contains("только 'get' и 'set'"));
        assertTrue(errorOf("class Box() { property x { def get() => 1 def get() => 2 } }")
                .contains("уже объявлен"));
    }

    @Test
    @DisplayName("'synchronized' у свойства не бывает")
    void noSynchronizedOnProperty() {
        assertTrue(errorOf("class Box() { synchronized property x => 1 }")
                .contains("у свойства не бывает"));
    }

    @Test
    @DisplayName("у свойства в классе аксессор без тела — ошибка, а не требование")
    void classAccessorNeedsBody() {
        assertTrue(errorOf("class Box() { property x { def get() } }").contains("нет тела"));
    }

    @Test
    @DisplayName("скрытому полю короткой формы мало")
    void backingFieldNeedsBlock() {
        assertTrue(errorOf("class Box() { property x = 0 => 1 }").contains("короткой формы"));
    }

    @Test
    @DisplayName("свойство и метод под одним именем — два значения по одному ключу")
    void nameTakenByMethod() {
        assertTrue(errorOf("class Box() { def x() => 1 property x => 2 }")
                .contains("уже объявлен"));
        assertTrue(errorOf("class Box() { property x => 2 def x() => 1 }")
                .contains("уже объявлен"));
    }

    @Test
    @DisplayName("у свойства должно быть тело")
    void propertyNeedsBody() {
        assertTrue(errorOf("class Box() { property x }").contains("нет тела"));
    }
}
