package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.source.Source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code extend}: скрипт добавляет члены типу или классу.
 * <p>
 * Проверяется не только то, что расширение работает, но и все четыре запрета,
 * из которых оно состоит: встроенное не перекрыть, дважды не объявить, записи нет,
 * внутри функции не объявить. Без них расширение было бы способом сломать чужой код
 * в том же запуске.
 */
class ExtendTest {

    private static String run(String code) {
        StringBuilder printed = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(program, ExecutionContext.fresh(printed::append));
        return printed.toString().strip();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> run(code));
    }

    /** Текст ошибок разбора — для запретов, которые ловятся до выполнения. */
    private static String parseErrors(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertTrue(diagnostics.hasErrors(), "ожидались ошибки разбора");
        return diagnostics.renderAll();
    }

    // --- что расширение умеет ------------------------------------------------

    @Test
    @DisplayName("свойство и метод объявляются теми же словами, что в теле класса")
    void propertyAndMethod() {
        assertEquals("20 [30, 20, 10]", run("""
                extend Array {
                    property second => this[1]

                    def swap(i, j) {
                        t = this[i]
                        this[i] = this[j]
                        this[j] = t
                        return this;
                    }
                }
                a = [10, 20, 30]
                println(a.second, " ", a.swap(0, 2))
                """));
    }

    @Test
    @DisplayName("'this' — получатель, то же слово и то же значение, что в методе класса")
    void thisIsReceiver() {
        assertEquals("СЛОВО", run("""
                extend String {
                    def shout() => this.upper
                }
                println("слово".shout())
                """));
    }

    @Test
    @DisplayName("к получателю обращаются явно: 'this.поле', а не голое имя")
    void receiverIsExplicit() {
        // Правило одно для типа и для класса: у массива и строки полей нет вовсе,
        // и разрешить голое имя только классам значило бы завести два разных extend.
        // Заодно ключ чужого объекта не перекрывает глобальные имена.
        assertEquals("круг", run("""
                class Shape(name)
                extend Shape {
                    property label => this.name
                }
                println(new Shape("круг").label)
                """));
        String message = errorOf("""
                class Shape(name)
                extend Shape {
                    property label => name
                }
                println(new Shape("круг").label)
                """).getMessage();
        assertTrue(message.contains("'name' не определена"), message);
    }

    @Test
    @DisplayName("член от расширения читается и через дескриптор — таблица одна")
    void throughDescriptor() {
        assertEquals("20", run("""
                extend Array {
                    property second => this[1]
                }
                println(Array.second([10, 20]))
                """));
    }

    @Test
    @DisplayName("расширение — свойство запуска: работает и внутри функции, и после неё")
    void visibleEverywhere() {
        assertEquals("2 2", run("""
                extend Number {
                    property doubled => this * 2
                }
                def twice(n) => n.doubled
                println(twice(1), " ", (1).doubled)
                """));
    }

    @Test
    @DisplayName("расширять можно и класс, а его потомок расширение наследует")
    void extendClass() {
        assertEquals("10 11", run("""
                class Shape(name) { def area() => 0 }
                class Circle(name, r) : Shape(name)
                extend Shape {
                    property label => "фигура " + this.name
                }
                c = new Circle("круг", 5)
                println(2 * 5, " ", c.label.size)
                """));
    }

    @Test
    @DisplayName("ключ таблицы — сам класс, а не имя: одноимённые классы независимы")
    void keyIsIdentity() {
        // Второй класс с тем же именем — другое значение, и расширение первого
        // на него не распространяется.
        assertEquals("null", run("""
                class Point(x)
                extend Point {
                    property twice => this.x * 2
                }
                class Point(x)
                println(new Point(2).twice)
                """));
    }

    @Test
    @DisplayName("данные получателя по-прежнему раньше члена")
    void dataStillWins() {
        assertEquals("своё", run("""
                extend Object {
                    property tag => "член"
                }
                println({tag: "своё"}.tag)
                """));
    }

    // --- запреты, из которых расширение состоит ------------------------------

    @Test
    @DisplayName("встроенное перекрыть нельзя — ошибка на строке объявления")
    void builtinIsNotOverridable() {
        String message = errorOf("""
                extend Array {
                    property size => 0
                }
                """).getMessage();
        assertTrue(message.contains("уже есть член 'size'"), message);
    }

    @Test
    @DisplayName("метод класса тоже не перекрыть: у экземпляра он нашёлся бы первым")
    void classMemberIsNotOverridable() {
        String message = errorOf("""
                class Box(w) { def area() => w }
                extend Box {
                    def area() => 0
                }
                """).getMessage();
        assertTrue(message.contains("уже есть член 'area'"), message);
    }

    @Test
    @DisplayName("повторное объявление тем же именем — ошибка, а не тихая победа последнего")
    void duplicateIsAnError() {
        String message = errorOf("""
                extend Array {
                    property second => this[1]
                }
                extend Array {
                    property second => "другое"
                }
                """).getMessage();
        assertTrue(message.contains("уже объявлен в этом запуске"), message);
    }

    @Test
    @DisplayName("записи у члена типа нет: 'def set' — отказ при объявлении")
    void noSetter() {
        String message = errorOf("""
                extend Array {
                    property second {
                        def get() => this[1]
                        def set(value) { this[1] = value }
                    }
                }
                """).getMessage();
        assertTrue(message.contains("не бывает 'def set(value)'"), message);
    }

    @Test
    @DisplayName("'extend' разрешён только на верхнем уровне файла")
    void onlyTopLevel() {
        String errors = parseErrors("""
                def prepare() {
                    extend Array {
                        property second => this[1]
                    }
                }
                """);
        assertTrue(errors.contains("только на верхнем уровне файла"), errors);
    }

    @Test
    @DisplayName("расширять можно тип или класс, а не значение")
    void onlyTypesAndClasses() {
        String message = errorOf("""
                box = {}
                extend box {
                    property second => 1
                }
                """).getMessage();
        assertTrue(message.contains("расширять можно тип или класс"), message);
    }

    @Test
    @DisplayName("конструктора и скрытого поля у расширения не бывает")
    void noConstructorNoField() {
        assertTrue(parseErrors("""
                extend Array {
                    def Array() { }
                }
                """).contains("у расширения нет конструктора"));
        assertTrue(parseErrors("""
                extend Array {
                    property counter = 0 {
                        def get() => field
                    }
                }
                """).contains("не бывает скрытого поля"));
    }

    @Test
    @DisplayName("'extend' остаётся обычным именем: ключевого слова язык не отнял")
    void extendIsNotAKeyword() {
        assertEquals("5", run("extend = 5\nprintln(extend)"));
    }
}
