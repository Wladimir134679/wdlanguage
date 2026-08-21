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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Поведение свойств: чтение и запись, скрытое поле, одна ячейка имени с полем,
 * голое имя внутри метода и требования трейтов.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PropertyTest {

    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        new Interpreter().run(Unit.of(source, program), ExecutionContext.fresh(output::append));
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    // --- чтение --------------------------------------------------------------

    @Test
    @DisplayName("свойство читается обращением: и точкой, и строкой, и вычисленным ключом")
    void readIsOrdinaryAccess() {
        assertEquals("200 200 200", printed("""
                class Rect(w, h) {
                    property area => this.w * this.h
                }
                r = new Rect(10, 20)
                key = "area"
                println(r.area, " ", r["area"], " ", r[key])
                """));
    }

    @Test
    @DisplayName("getter выполняется на каждом чтении")
    void getterRunsEveryRead() {
        assertEquals("1 2 3", printed("""
                reads = 0
                class Counter() {
                    property next {
                        def get() {
                            reads = reads + 1
                            return reads;
                        }
                    }
                }
                c = new Counter()
                println(c.next, " ", c.next, " ", c.next)
                """));
    }

    @Test
    @DisplayName("вычисляемое свойство видит поля, изменившиеся после создания")
    void propertyFollowsFields() {
        assertEquals("200 300", printed("""
                class Rect(w, h) {
                    property area => this.w * this.h
                }
                r = new Rect(10, 20)
                println(r.area)
                r.w = 15
                println(r.area)
                """));
    }

    // --- запись --------------------------------------------------------------

    @Test
    @DisplayName("setter получает записываемое значение")
    void setterReceivesValue() {
        assertEquals("4 4", printed("""
                class Rect(w, h) {
                    property size {
                        def get() => this.w
                        def set(value) {
                            this.w = value
                            this.h = value
                        }
                    }
                }
                r = new Rect(1, 2)
                r.size = 4
                println(r.w, " ", r.h)
                """));
    }

    @Test
    @DisplayName("свойство без setter только для чтения — и в записи, и по вычисленному ключу")
    void readOnlyRefusesWrite() {
        String message = errorOf("""
                class Rect(w, h) { property area => this.w * this.h }
                r = new Rect(1, 2)
                r.area = 5
                """).getMessage();
        assertTrue(message.contains("только для чтения"), message);
        assertTrue(errorOf("""
                class Rect(w, h) { property area => this.w * this.h }
                r = new Rect(1, 2)
                r["area"] = 5
                """).getMessage().contains("только для чтения"));
    }

    @Test
    @DisplayName("составное присваивание: один вызов getter, один setter")
    void compoundCallsEachAccessorOnce() {
        assertEquals("get set 130", printed("""
                class Window(width) {
                    property size {
                        def get() {
                            println("get")
                            return this.width;
                        }
                        def set(value) {
                            println("set")
                            this.width = value
                        }
                    }
                }
                w = new Window(30)
                w.size += 100
                println(w.width)
                """));
    }

    // --- скрытое поле --------------------------------------------------------

    @Test
    @DisplayName("скрытое поле: 'field' читается и пишется внутри аксессоров")
    void backingFieldWorks() {
        assertEquals("0 5 0", printed("""
                class Box() {
                    property x = 0 {
                        def get() => field
                        def set(value) { field = value < 0 ? 0 : value }
                    }
                }
                b = new Box()
                println(b.x)
                b.x = 5
                println(b.x)
                b.x = -3
                println(b.x)
                """));
    }

    @Test
    @DisplayName("скрытое поле не видно ни перебором, ни len, ни печатью")
    void backingFieldIsHidden() {
        assertEquals("0 Box{}", printed("""
                class Box() {
                    property x = 7 {
                        def get() => field
                        def set(value) { field = value }
                    }
                }
                b = new Box()
                count = 0
                for (k in b) count = count + 1
                println(count, " ", b)
                """));
    }

    @Test
    @DisplayName("у каждого экземпляра своё скрытое поле")
    void backingFieldIsPerInstance() {
        assertEquals("1 2", printed("""
                class Box() {
                    property x = 0 {
                        def get() => field
                        def set(value) { field = value }
                    }
                }
                a = new Box()
                b = new Box()
                a.x = 1
                b.x = 2
                println(a.x, " ", b.x)
                """));
    }

    @Test
    @DisplayName("начальное значение скрытого поля видит поля заголовка")
    void backingFieldInitialSeesHeader() {
        assertEquals("20", printed("""
                class Box(w) {
                    property x = w * 2 {
                        def get() => field
                        def set(value) { field = value }
                    }
                }
                println(new Box(10).x)
                """));
    }

    @Test
    @DisplayName("'field' вне свойства со скрытым полем — обычное имя")
    void fieldIsOrdinaryNameOutside() {
        assertEquals("5 12", printed("""
                field = 5
                class Rect(w, h) {
                    property area => this.w * this.h
                }
                println(field, " ", new Rect(3, 4).area)
                """));
    }

    // --- голое имя внутри класса ---------------------------------------------

    @Test
    @DisplayName("внутри метода голое имя свойства читает getter")
    void bareNameReadsProperty() {
        assertEquals("200", printed("""
                class Rect(w, h) {
                    property area => this.w * this.h
                    def report() => area
                }
                println(new Rect(10, 20).report())
                """));
    }

    @Test
    @DisplayName("внутри метода голое имя свойства пишет setter, а не заводит переменную")
    void bareNameWritesProperty() {
        assertEquals("7 7", printed("""
                class Rect(w, h) {
                    property size {
                        def get() => this.w
                        def set(value) {
                            this.w = value
                            this.h = value
                        }
                    }
                    def resize(n) { size = n }
                }
                r = new Rect(1, 2)
                r.resize(7)
                println(r.w, " ", r.h)
                """));
    }

    @Test
    @DisplayName("имя, занятое свойством, локальной переменной не станет — как и имя поля")
    void propertyNameIsTaken() {
        String message = errorOf("""
                class Rect(w, h) {
                    property area => this.w * this.h
                    def report() {
                        area = 5
                        return area;
                    }
                }
                println(new Rect(10, 20).report())
                """).getMessage();
        // То же сообщение, что у 'r.area = 5' снаружи: правило одно, и звучать
        // по-разному в зависимости от формы записи оно не должно.
        assertTrue(message.contains("только для чтения"), message);
    }

    @Test
    @DisplayName("голое имя пишет свойство той же ячейки, а не заводит вторую")
    void bareWriteHitsTheSameCell() {
        assertEquals("30 30", printed("""
                class Rect(w, h) {
                    property size {
                        def get() => this.w
                        def set(value) { this.w = value }
                    }
                    def report() {
                        size = 30
                        return size;
                    }
                }
                r = new Rect(1, 2)
                println(r.report(), " ", r.size)
                """));
    }

    @Test
    @DisplayName("конструктор пишет через setter: объект собран целиком до его тела")
    void constructorUsesSetter() {
        assertEquals("9", printed("""
                class Box() {
                    property x = 0 {
                        def get() => field
                        def set(value) { field = value * 3 }
                    }
                    def Box() { this.x = 3 }
                }
                println(new Box().x)
                """));
    }

    // --- наследование --------------------------------------------------------

    @Test
    @DisplayName("свойство наследуется, как метод")
    void propertyIsInherited() {
        assertEquals("12", printed("""
                class Shape(w, h) { property area => this.w * this.h }
                class Rect(w, h) : Shape(w, h)
                println(new Rect(3, 4).area)
                """));
    }

    @Test
    @DisplayName("потомок заменяет свойство родителя полем: побеждает последний")
    void fieldOverridesInheritedProperty() {
        assertEquals("99", printed("""
                class Shape(w) { property area => this.w * 2 }
                class Rect(w, area) : Shape(w)
                println(new Rect(3, 99).area)
                """));
    }

    @Test
    @DisplayName("потомок заменяет поле родителя свойством: побеждает последний")
    void propertyOverridesInheritedField() {
        assertEquals("6", printed("""
                class Shape(area)
                class Rect(w) : Shape(0) { property area => this.w * 2 }
                println(new Rect(3).area)
                """));
    }

    @Test
    @DisplayName("super читает свойство родителя, перекрытое потомком")
    void superReadsParentProperty() {
        assertEquals("[100]", printed("""
                class Shape(w) { property area => this.w * 10 }
                class Rect(w) : Shape(w) {
                    property area => "[" + super.area + "]"
                }
                println(new Rect(10).area)
                """));
    }

    @Test
    @DisplayName("свойство приходит из трейта")
    void propertyFromTrait() {
        assertEquals("30", printed("""
                trait Sized { property size => this.w * 3 }
                class Rect(w) with Sized
                println(new Rect(10).size)
                """));
    }

    @Test
    @DisplayName("класс-обёртка от декоратора наследует свойства родителя")
    void decoratorProxyKeepsProperties() {
        assertEquals("[12] 12", printed("""
                def traced(meta) {
                    class Proxy(*args, **named) : meta.target(*args, **named) {
                        def mark() => "[" + this.area + "]"
                    }
                    return Proxy;
                }

                @[traced]
                class Rect(w, h) {
                    property area => this.w * this.h
                }
                r = new Rect(3, 4)
                println(r.mark(), " ", r.area)
                """));
    }

    @Test
    @DisplayName("super и this пишут одно и то же скрытое поле")
    void superSharesHiddenField() {
        assertEquals("11 11", printed("""
                class Base() {
                    property v = 1 {
                        def get() => field
                        def set(x) { field = x }
                    }
                }
                class Child() : Base() {
                    def bump() {
                        super.v = super.v + 10
                        return this.v;
                    }
                }
                c = new Child()
                println(c.bump(), " ", c.v)
                """));
    }

    @Test
    @DisplayName("трейт совмещает требование к свойству и готовое свойство поверх него")
    void traitMixesRequirementAndProperty() {
        assertEquals("<42>", printed("""
                trait Doubled {
                    property twice { def get() }
                    property label => "<" + this.twice + ">"
                }
                class Num(n) with Doubled {
                    property twice => this.n * 2
                }
                println(new Num(21).label)
                """));
    }

    // --- требования трейтов --------------------------------------------------

    @Test
    @DisplayName("требование чтения закрывает и поле, и свойство")
    void readRequirementSatisfiedByBoth() {
        assertEquals("4 9", printed("""
                trait Sized { property size { def get() } }
                class Fixed(size) with Sized
                class Computed(w) with Sized { property size => this.w * 3 }
                println(new Fixed(4).size, " ", new Computed(3).size)
                """));
    }

    @Test
    @DisplayName("требование записи не закрывает свойство только для чтения")
    void writeRequirementNeedsSetter() {
        String message = errorOf("""
                trait MutableSized {
                    property size {
                        def get()
                        def set(value)
                    }
                }
                class Computed(w) with MutableSized { property size => this.w }
                """).getMessage();
        assertTrue(message.contains("не умеет записи"), message);
    }

    @Test
    @DisplayName("метод требование к свойству не закрывает")
    void methodDoesNotSatisfyProperty() {
        assertTrue(errorOf("""
                trait Sized { property size { def get() } }
                class Rect(w) with Sized { def size() => this.w }
                """).getMessage().contains("size"));
    }

    @Test
    @DisplayName("свойство с get и set закрывает полевое требование трейта")
    void propertySatisfiesFieldRequirement() {
        assertEquals("21", printed("""
                trait Sized(size) { def report() => this.size + 1 }
                class Rect(w) with Sized {
                    property size {
                        def get() => this.w * 2
                        def set(value) { this.w = value }
                    }
                }
                println(new Rect(10).report())
                """));
    }

    // --- одна ячейка имени ---------------------------------------------------

    @Test
    @DisplayName("свойство и метод под одним именем через наследование — ошибка связывания")
    void propertyAndMethodClash() {
        String message = errorOf("""
                class Shape(w) { def size() => this.w }
                class Rect(w) : Shape(w) { property size => this.w * 2 }
                """).getMessage();
        assertTrue(message.contains("занято и свойством"), message);
    }

    @Test
    @DisplayName("рекурсивный getter останавливается ограничением глубины, а не StackOverflowError")
    void recursiveGetterStops() {
        FatalError stopped = assertThrows(FatalError.class, () -> printed("""
                class Loop() { property x => this.x }
                println(new Loop().x)
                """));
        assertTrue(stopped.getMessage().toLowerCase().contains("глуб"), stopped.getMessage());
    }
}
