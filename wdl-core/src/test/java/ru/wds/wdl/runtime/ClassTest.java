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

/** Поведение классов и трейтов: поля, методы, создание, наследование, {@code is}. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ClassTest {

    /** Запускает скрипт и возвращает напечатанное одной строкой. */
    private static String printed(String code) {
        StringBuilder output = new StringBuilder();
        run(code, output::append);
        return output.toString().replace(System.lineSeparator(), " ").trim();
    }

    private static void run(String code, Output output) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        // Юнитом, а не голой программой: диагностика ищет объявления по дереву файла,
        // а строку в сообщении считает по его исходнику.
        new Interpreter().run(Unit.of(source, program), ExecutionContext.fresh(output));
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    // --- поля и методы -------------------------------------------------------

    @Test
    @DisplayName("заголовок задаёт поля, значения по умолчанию работают")
    void headerIsFields() {
        assertEquals("болт 12 0", printed("""
                class Item(name, price, count = 0)
                item = new Item("болт", 12)
                println(item.name, " ", item.price, " ", item.count)
                """));
    }

    @Test
    @DisplayName("поле — то же обращение, что у объекта: точка, строка и вычисленный ключ")
    void fieldAccessIsOrdinary() {
        assertEquals("20 20 20 x=20 y=30 2", printed("""
                class Point(x, y)
                p = new Point(20, 30)
                field = "x"
                print(p.x, " ", p["x"], " ", p[field], " ")
                for (name in p) print(name, "=", p[name], " ")
                println(len(p))
                """));
    }

    @Test
    @DisplayName("метод правит поле по имени: x и this.x — одна ячейка")
    void methodSeesFields() {
        assertEquals("(30, 25)", printed("""
                class Point(x = 0, y = 0) {
                    def move(dx, dy) { x += dx; y += dy }
                    def text() => "(" + x + ", " + y + ")"
                }
                p = new Point(20, 30)
                p.move(10, -5)
                println(p.text())
                """));
    }

    @Test
    @DisplayName("метод — значение и помнит свой объект")
    void methodRemembersInstance() {
        assertEquals("(1, 2)", printed("""
                class Point(x, y) { def text() => "(" + x + ", " + y + ")" }
                handler = new Point(1, 2).text
                println(handler())
                """));
    }

    @Test
    @DisplayName("методы не попадают ни в перебор, ни в len: они живут в классе")
    void methodsAreNotFields() {
        assertEquals("x y 2", printed("""
                class Point(x, y) { def text() => x }
                p = new Point(1, 2)
                for (name in p) print(name, " ")
                println(len(p))
                """));
    }

    @Test
    @DisplayName("поле перекрывает метод: подмена поведения у одного объекта законна")
    void fieldOverridesMethod() {
        assertEquals("по-своему 3", printed("""
                class Point(x, y) { def text() => "обычно" }
                p = new Point(1, 2)
                p.text = def() => "по-своему"
                println(p.text(), " ", len(p))
                """));
    }

    @Test
    @DisplayName("связанный метод создаётся на каждом чтении: два чтения — два значения")
    void boundMethodIsFreshEachRead() {
        // Плата за то, что отдельного вида значения у метода нет. Для языка это
        // ничего не меняет: функции и без того сравниваются по ссылке
        assertEquals("false true", printed("""
                class Point(x, y) { def text() => x }
                p = new Point(1, 2)
                f = p.text
                println(p.text == p.text, " ", f == f)
                """));
    }

    @Test
    @DisplayName("несуществующее поле даёт null, как у объекта")
    void missingFieldIsNull() {
        assertEquals("null true", printed("""
                class Point(x, y)
                p = new Point(1, 2)
                println(p.missing, " ", p.missing == null)
                """));
    }

    @Test
    @DisplayName("поле со значением null находится и не путается с неизвестным именем")
    void nullFieldIsStillAField() {
        assertEquals("null 33", printed("""
                class User(name, age = null) {
                    def show() => age
                    def grow() { age = 33 }
                }
                u = new User("wdeath")
                print(u.show(), " ")
                u.grow()
                println(u.show())
                """));
    }

    @Test
    @DisplayName("неизвестное имя внутри метода уходит наружу — к глобальным")
    void unknownNameGoesOutside() {
        assertEquals("ставка 7", printed("""
                RATE = 7
                class Order(sum) { def show() { println("ставка ", RATE) } }
                new Order(1).show()
                """));
    }

    // --- область видимости ---------------------------------------------------

    @Test
    @DisplayName("параметр перекрывает поле, this пробивается сквозь него")
    void parameterShadowsField() {
        assertEquals("привет, второй", printed("""
                class User(name) {
                    def rename(name) { this.name = name }
                    def greet() => "привет, " + name
                }
                u = new User("первый")
                u.rename("второй")
                println(u.greet())
                """));
    }

    @Test
    @DisplayName("новое имя в методе полем не становится, this.имя — становится")
    void localNameIsNotAField() {
        assertEquals("Box{\"width\": 3, \"height\": 4, \"area\": 12}", printed("""
                class Box(width, height) {
                    def Box() {
                        half = width * height / 2
                        this.area = half * 2
                    }
                }
                println(new Box(3, 4))
                """));
    }

    @Test
    @DisplayName("вложенная функция видит поля и this: это обычное замыкание")
    void closureSeesFields() {
        assertEquals("10 20", printed("""
                class Basket(rate) {
                    def report() {
                        show = def(n) => println(n * rate)
                        show(1)
                        show(2)
                    }
                }
                new Basket(10).report()
                """));
    }

    @Test
    @DisplayName("объявление класса не перекрывает константу с тем же именем")
    void constantBlocksClassWithSameName() {
        assertTrue(errorOf("const Point = 1\nclass Point(x)")
                .getMessage().contains("уже есть константа"));
        assertTrue(errorOf("const Printable = 1\ntrait Printable")
                .getMessage().contains("уже есть константа"));
    }

    @Test
    @DisplayName("поле экземпляра ближе внешней константы: запись в имя поля разрешена")
    void fieldWinsOverOuterConstant() {
        assertEquals("2 0", printed("""
                const count = 0
                class Counter(count) {
                    def bump() { count += 1 }
                }
                c = new Counter(1)
                c.bump()
                println(c.count, " ", count)
                """));
    }

    // --- создание ------------------------------------------------------------

    @Test
    @DisplayName("конструктор выполняется по готовому объекту и правит поля")
    void constructorRunsOnReadyObject() {
        assertEquals("0x4 = 0 3x4 = 12", printed("""
                class Box(width, height) {
                    def Box() {
                        if (width < 0) width = 0
                        if (height < 0) height = 0
                        this.area = width * height
                    }
                    def text() => width + "x" + height + " = " + area
                }
                println(new Box(-3, 4).text(), " ", new Box(3, 4).text())
                """));
    }

    @Test
    @DisplayName("к телу конструктора объект собран целиком: его можно отдать наружу")
    void constructorSeesWholeObject() {
        assertEquals("1 wdeath", printed("""
                registry = []
                class Session(user) { def Session() { registry += [this] } }
                new Session("wdeath")
                println(len(registry), " ", registry[0].user)
                """));
    }

    @Test
    @DisplayName("число аргументов проверяется по заголовку, до входа в конструктор")
    void arityCheckedBeforeConstructor() {
        assertTrue(errorOf("""
                class Point(x, y) { def Point() { println("не должно печататься") } }
                new Point(1)
                """).getMessage().contains("принимает ровно 2 аргумента"));
    }

    @Test
    @DisplayName("класс без new не вызывается, а не-класс не создаётся")
    void newAndCallAreDifferent() {
        assertTrue(errorOf("class Point(x, y)\nPoint(1, 2)")
                .getMessage().contains("вызвать можно только функцию"));
        assertTrue(errorOf("x = 5\nnew (x)(1)")
                .getMessage().contains("создать экземпляр можно только классом"));
    }

    @Test
    @DisplayName("бесконечное создание останавливает выполнение, а не переполняет стек")
    void endlessCreation() {
        // FatalError, а не ошибка скрипта: «выполнение дальше не идёт» ловить обработчиком
        // нельзя, иначе цикл с try съел бы собственную защиту от зацикливания.
        FatalError fatal = assertThrows(FatalError.class,
                () -> printed("class Node(next = new Node())\nnew Node()"));
        assertTrue(fatal.getMessage().contains("слишком глубокая рекурсия"), fatal.getMessage());
    }

    // --- печать и typeof -----------------------------------------------------

    @Test
    @DisplayName("экземпляр печатается именем класса и полями — по общему правилу объекта")
    void printing() {
        assertEquals("Point{\"x\": 20, \"y\": 30} class Point(x, y) object class", printed("""
                class Point(x = 0, y = 0)
                p = new Point(20, 30)
                println(p, " ", Point, " ", typeof(p), " ", typeof(Point))
                """));
    }

    @Test
    @DisplayName("класс в арифметике и в len — внятная ошибка, а не молчание")
    void classInOperations() {
        assertTrue(errorOf("class Point(x)\nprintln(Point + 1)")
                .getMessage().contains("не применима к типам класс и число"));
        assertTrue(errorOf("class Point(x)\nprintln(len(Point))")
                .getMessage().contains("а здесь класс"));
        // У трейта теперь есть члены (name, methods, requirements), поэтому промах
        // по имени — это «нет такого члена», а не «обращаться нельзя вовсе».
        assertTrue(errorOf("trait T {}\nprintln(T.x)")
                .getMessage().contains("у значения типа трейт нет члена 'x'"));
    }

    @Test
    @DisplayName("трейт — тоже значение со своим типом")
    void traitIsAValue() {
        assertEquals("trait Counted trait", printed("""
                trait Counted(count = 0) { def inc() { count += 1 } }
                println(Counted, " ", typeof(Counted))
                """));
    }

    // --- наследование --------------------------------------------------------

    @Test
    @DisplayName("поля и методы достаются от родителя, свой метод перекрывает родительский")
    void inheritance() {
        assertEquals("круг площадью 78.53975 круг квадрат площадью 16", printed("""
                class Shape(name) {
                    def area() => 0
                    def text() => name + " площадью " + area()
                }
                class Circle(radius) : Shape("круг") {
                    def area() => 3.14159 * radius * radius
                }
                class Square(side) : Shape("квадрат") {
                    def area() => side * side
                }
                c = new Circle(5)
                println(c.text(), " ", c.name, " ", new Square(4).text())
                """));
    }

    @Test
    @DisplayName("аргументы родителя видят параметры потомка")
    void parentArgumentsSeeOwnParams() {
        assertEquals("сторона 4", printed("""
                class Shape(name)
                class Square(side) : Shape("сторона " + side)
                println(new Square(4).name)
                """));
    }

    @Test
    @DisplayName("super зовёт реализацию родителя, а голое имя внутри неё — реализацию потомка")
    void superIsVirtualInside() {
        // Внутри Shape.text() зовётся area() круга — это и есть виртуальность
        assertEquals("круг площадью 78.53975 (радиус 5)", printed("""
                class Shape(name) {
                    def area() => 0
                    def text() => name + " площадью " + area()
                }
                class Circle(radius) : Shape("круг") {
                    def area() => 3.14159 * radius * radius
                    def text() => super.text() + " (радиус " + radius + ")"
                }
                println(new Circle(5).text())
                """));
    }

    @Test
    @DisplayName("area() и this.area() внутри метода от super дают одно и то же")
    void bareNameAndThisAgree() {
        assertEquals("25 25", printed("""
                class Shape(name) {
                    def area() => 0
                    def text() => area() + " " + this.area()
                }
                class Square(side) : Shape("квадрат") {
                    def area() => side * side
                    def text() => super.text()
                }
                println(new Square(5).text())
                """));
    }

    @Test
    @DisplayName("super — обычное значение: тот же объект, только точка отсчёта другая")
    void superIsOrdinaryValue() {
        assertEquals("родитель true true Circle{\"name\": \"круг\", \"radius\": 5}", printed("""
                class Shape(name) { def text() => "родитель" }
                class Circle(radius) : Shape("круг") {
                    def text() => "потомок"
                    def show() {
                        s = super
                        println(s.text(), " ", s == this, " ", s is Circle, " ", s)
                    }
                }
                new Circle(5).show()
                """));
    }

    @Test
    @DisplayName("конструкторы трёх уровней выполняются от корня вниз, каждый по разу")
    void constructorsRunRootFirst() {
        assertEquals("A B C", printed("""
                class A(x) { def A() { print("A") } }
                class B(y) : A(1) { def B() { print(" B") } }
                class C(z) : B(2) { def C() { print(" C") } }
                new C(3)
                """));
    }

    @Test
    @DisplayName("перекрытое поле: позиция от родителя, значение от потомка")
    void overriddenFieldKeepsPosition() {
        assertEquals("Circle{\"name\": \"свой\", \"radius\": 5}", printed("""
                class Shape(name)
                class Circle(radius, name = "свой") : Shape("родительский")
                println(new Circle(5))
                """));
    }

    @Test
    @DisplayName("аргумент родителя вычисляется всегда и ровно один раз")
    void parentArgumentIsAlwaysEvaluated() {
        assertEquals("1", printed("""
                calls = 0
                def mark() { calls += 1; return "имя"; }
                class Shape(name)
                class Circle(radius, name = "свой") : Shape(mark())
                new Circle(5)
                println(calls)
                """));
    }

    @Test
    @DisplayName("родитель объявляется выше потомка")
    void parentGoesFirst() {
        assertEquals("true true", printed("""
                class Shape(name)
                class Circle(radius) : Shape("круг")
                c = new Circle(5)
                println(c is Circle, " ", c is Shape)
                """));
    }

    @Test
    @DisplayName("родитель ниже потомка — ошибка, называющая строку объявления")
    void parentBelowIsAnError() {
        // Родитель ищется среди значений, видимых в этой точке: до своей строки
        // класса ещё нет. Сообщение поэтому говорит не «нет такого имени», а где
        // это имя объявлено на самом деле.
        String message = errorOf("""
                class Circle(radius) : Shape("круг")
                class Shape(name)
                """).getMessage();
        assertTrue(message.contains("неизвестный класс 'Shape'"), message);
        assertTrue(message.contains("объявление стоит ниже, на строке 2"), message);
    }

    @Test
    @DisplayName("круг в наследовании: класс не находит родителя, и переставить их нельзя")
    void inheritanceCycle() {
        // Родитель ищется среди значений, видимых в этой точке, поэтому объявление
        // родителя обязано стоять выше. Здесь такого порядка не существует вовсе —
        // и об этом говорит сообщение: переставить нечего.
        String message = errorOf("class A(x) : B(1)\nclass B(y) : A(1)").getMessage();
        assertTrue(message.contains("неизвестный класс 'B'"), message);
        assertTrue(message.contains("наследуют друг друга"), message);
    }

    @Test
    @DisplayName("родитель и трейты проверяются при выполнении объявления класса")
    void linkErrors() {
        assertTrue(errorOf("class Shape(name)\nclass Circle(r) : Shape(1, 2)")
                .getMessage().contains("принимает ровно 1 аргумент"));
        assertTrue(errorOf("class Circle(r) : Missing()")
                .getMessage().contains("неизвестный класс 'Missing'"));
        assertTrue(errorOf("trait T {}\nclass A(x) : T()")
                .getMessage().contains("трейт, а не класс"));
        assertTrue(errorOf("class C(x)\nclass A(x) with C")
                .getMessage().contains("класс, а не трейт"));
        assertTrue(errorOf("Shape = 5\nclass Circle(r) : Shape(1)")
                .getMessage().contains("наследоваться можно только от класса"));
    }

    // --- трейты --------------------------------------------------------------

    @Test
    @DisplayName("трейт даёт классу поля и методы, и они работают вместе")
    void traits() {
        assertEquals("* 2 шт. из 10 Basket{\"count\": 2, \"items\": [\"болт\", \"гайка\"], \"limit\": 10}"
                + " false true true", printed("""
                trait Printable {
                    def text()
                    def print() => println("* ", text())
                }
                trait Counted(count = 0, limit) {
                    def inc() { count += 1 }
                    def full() => count >= limit
                }
                class Basket(items, limit = 10) with Printable, Counted {
                    def add(item) { items += [item]; inc() }
                    def text() => len(items) + " шт. из " + limit
                }
                b = new Basket([])
                b.add("болт")
                b.add("гайка")
                b.print()
                println(b, " ", b.full(), " ", b is Printable, " ", b is Counted)
                """));
    }

    @Test
    @DisplayName("невыполненное требование трейта — ошибка при объявлении класса")
    void unmetRequirements() {
        // Именно при объявлении, а не при создании экземпляра: класс объявлен —
        // значит, он уже проверен, и до 'new' ошибке ждать незачем.
        String noField = errorOf("""
                trait Counted(count = 0, limit) { def inc() { count += 1 } }
                class Bag(items) with Counted
                """).getMessage();
        assertTrue(noField.contains("не выполняет требование трейта 'Counted'"), noField);
        assertTrue(noField.contains("нет поля 'limit'"), noField);

        assertTrue(errorOf("""
                trait Printable { def text() }
                class Bag(items) with Printable
                """).getMessage().contains("нет метода 'text'"));

        assertTrue(errorOf("""
                trait Printable { def text() }
                class Bag(items) with Printable { def text(extra) => extra }
                """).getMessage().contains("должен принимать"));
    }

    @Test
    @DisplayName("требование трейта проверено до создания экземпляра, а не при нём")
    void requirementsCheckedBeforeInstance() {
        // Разница с abc.ABCMeta в Python, где такой класс объявляется молча и падает
        // на 'new': здесь до строки с созданием дело не доходит вовсе.
        StringBuilder output = new StringBuilder();
        assertThrows(WdlRuntimeError.class, () -> run("""
                trait Printable { def text() }
                def make() {
                    class Bag(items) with Printable
                    println("класс объявлен")
                    return new Bag([]);
                }
                make()
                """, output::append));

        assertEquals("", output.toString());
    }

    @Test
    @DisplayName("требование закрывается предком или другим трейтом")
    void requirementsMetElsewhere() {
        assertEquals("есть есть", printed("""
                trait Printable { def text() }
                trait Loud { def text() => "есть" }
                class FromTrait(x) with Printable, Loud
                class Base(x) { def text() => "есть" }
                class FromParent(x) : Base(1) with Printable
                println(new FromTrait(1).text(), " ", new FromParent(1).text())
                """));
    }

    @Test
    @DisplayName("побеждает последний: родитель, потом трейты слева направо, потом класс")
    void lastWins() {
        assertEquals("тихо ГРОМКО по-своему", printed("""
                trait Loud  { def voice() => "ГРОМКО" }
                trait Quiet { def voice() => "тихо" }
                class A(x) with Loud, Quiet
                class B(x) with Quiet, Loud
                class C(x) with Loud, Quiet { def voice() => "по-своему" }
                println(new A(1).voice(), " ", new B(1).voice(), " ", new C(1).voice())
                """));
    }

    @Test
    @DisplayName("значение поля трейта считается на каждом создании заново")
    void traitDefaultsAreFresh() {
        assertEquals("[1] [2]", printed("""
                trait Log(entries = []) { def add(x) { entries += [x] } }
                class Task(name) with Log
                a = new Task("первая")
                b = new Task("вторая")
                a.add(1)
                b.add(2)
                println(a.entries, " ", b.entries)
                """));
    }

    @Test
    @DisplayName("трейтом экземпляр не создать")
    void traitIsNotCreatable() {
        assertTrue(errorOf("trait Counted(count = 0)\nnew Counted()")
                .getMessage().contains("трейт, экземпляр создаёт класс"));
    }

    // --- is ------------------------------------------------------------------

    @Test
    @DisplayName("is отвечает про класс, предка и трейт, а для не-экземпляра — false")
    void isOperator() {
        assertEquals("true true true false false false", printed("""
                trait Printable { def print() => 1 }
                class Shape(name)
                class Circle(radius) : Shape("круг") with Printable
                class Point(x)
                c = new Circle(5)
                println(c is Circle, " ", c is Shape, " ", c is Printable, " ",
                        c is Point, " ", 42 is Shape, " ", null is Shape)
                """));
    }

    @Test
    @DisplayName("обычный объект — та же карта пар, но классу не принадлежит")
    void plainObjectIsNotAnInstance() {
        assertEquals("object object {\"x\": 1} false", printed("""
                class Point(x)
                plain = {"x": 1}
                p = new Point(1)
                println(typeof(plain), " ", typeof(p), " ", plain, " ", plain is Point)
                """));
    }

    @Test
    @DisplayName("справа от is должен стоять класс, трейт или тип")
    void isNeedsClassOnRight() {
        assertTrue(errorOf("class A(x)\nprintln(new A(1) is 5)")
                .getMessage().contains("справа от 'is' должен стоять класс, трейт или тип"));
    }

    @Test
    @DisplayName("is отвечает и на дескриптор типа, а не только на класс и трейт")
    void isAnswersTypeDescriptorsToo() {
        assertEquals("true true true true true", printed("""
                println(5 is Number, " ", "a" is String, " ", [] is Array, " ",
                        {} is Object, " ", null is Null)
                """));
    }

    @Test
    @DisplayName("экземпляр класса — object: is Object отвечает true, как и is своим классом")
    void instanceIsObjectAndItsOwnClass() {
        assertEquals("true true", printed("""
                class Point(x, y)
                p = new Point(1, 2)
                println(p is Object, " ", p is Point)
                """));
    }

    @Test
    @DisplayName("класс, трейт и функция — тоже значения с собственным дескриптором")
    void classTraitAndFunctionHaveDescriptors() {
        assertEquals("true true true", printed("""
                class Point(x, y)
                trait Printable { def text() }
                println(Point is Class, " ", Printable is Trait, " ", println is Function)
                """));
    }

    @Test
    @DisplayName("дескриптор типа не относится сам к себе: Number is Number — false")
    void descriptorIsNotItsOwnInstance() {
        assertEquals("false", printed("println(Number is Number)"));
    }

    // --- класс как значение --------------------------------------------------

    @Test
    @DisplayName("поле класса — обычная запись по ключу, методы через класс не читаются")
    void classIsAValue() {
        assertEquals("(0, 0) null", printed("""
                class Point(x, y) { def text() => "(" + x + ", " + y + ")" }
                Point.zero = new Point(0, 0)
                println(Point.zero.text(), " ", Point.text)
                """));
    }

    @Test
    @DisplayName("класс можно передать и положить в массив: new берёт значение слева")
    void classCanBePassed() {
        assertEquals("(1, 2) (3, 4)", printed("""
                class Point(x, y) { def text() => "(" + x + ", " + y + ")" }
                def build(cls, a, b) => new cls(a, b)
                kinds = [Point]
                println(new kinds[0](1, 2).text(), " ", build(Point, 3, 4).text())
                """));
    }

    @Test
    @DisplayName("фабрика — способ создания с именем; снаружи то же самое даёт присваивание")
    void factories() {
        assertEquals("wdeath-хеш гость-хеш", printed("""
                class User(login, hash) {
                    def User.of(login, password) => new User(login, password + "-хеш")
                    def text() => hash
                }
                User.guest = def() => new User("гость", "гость-хеш")
                println(User.of("wdeath", "wdeath").text(), " ", User.guest().text())
                """));
    }

    @Test
    @DisplayName("класс из функции замыкает свой вызов, но для is остаётся тем же классом")
    void classInsideFunction() {
        // Два вызова дают два значения класса с разными замыканиями — и один и тот же
        // класс: объявлен-то он в одном и том же месте текста
        assertEquals("10 20 true", printed("""
                def kind(rate) {
                    class Priced(sum) { def total() => sum * rate }
                    return Priced;
                }
                ten = kind(10)
                twenty = kind(20)
                println(new ten(1).total(), " ", new twenty(1).total(), " ", new ten(1) is twenty)
                """));
    }

    @Test
    @DisplayName("имя класса из функции наружу не выходит — как и имя функции")
    void classInsideFunctionStaysInside() {
        assertTrue(errorOf("""
                def make() { class Priced(sum) def ignored() => 1 }
                make()
                new Priced(1)
                """).getMessage().contains("переменная 'Priced' не определена"));
    }

    // --- родитель и трейт выражением -----------------------------------------

    @Test
    @DisplayName("родителя можно достать по ключу, и это обычное обращение")
    void parentFromKey() {
        assertEquals("основа круг", printed("""
                class Base(title) { def text() => "основа " + title }
                registry = {"Base": Base}

                class Circle : registry["Base"]("круг")
                println(new Circle().text())
                """));
    }

    @Test
    @DisplayName("трейт тоже достаётся выражением, вместе со своим требованием")
    void traitFromKey() {
        assertEquals("[метка] есть", printed("""
                trait Logged { def log() => "[" + tag() + "] " def tag() }
                traits = {"Logged": Logged}

                class Note with traits["Logged"] {
                    def tag() => "метка"
                    def text() => log() + "есть"
                }
                println(new Note().text())
                """));
    }

    @Test
    @DisplayName("требование трейта, взятого выражением, проверяется там же — на строке class")
    void traitFromKeyKeepsItsRequirement() {
        assertTrue(errorOf("""
                trait Logged { def tag() }
                traits = {"Logged": Logged}
                class Note with traits["Logged"] { }
                """).getMessage().contains("не выполняет требование трейта 'Logged'"));
    }

    @Test
    @DisplayName("последние скобки — аргументы заголовка, а не вызов родителя")
    void lastParenthesesAreArguments() {
        // ': Base(...)' — это родитель Base с аргументами, и так оно и остаётся, каким бы
        // ни было выражение слева. Чтобы позвать функцию и наследоваться от результата,
        // скобок пишут двое: первые — вызов, вторые — аргументы заголовка.
        assertEquals("основа своя основа взятая", printed("""
                class Base(title) { def text() => "основа " + title }
                def maker() => Base

                class Own : Base("своя")
                class Taken : maker()("взятая")
                println(new Own().text(), " ", new Taken().text())
                """));
    }

    @Test
    @DisplayName("родство считается по форме: два пути к одному классу дают одну родню")
    void expressionKeepsIdentity() {
        assertEquals("true true", printed("""
                class Base(title) { }
                registry = {"Base": Base}

                class ByName : Base("именем")
                class ByKey : registry["Base"]("ключом")
                println(new ByName() is Base, " ", new ByKey() is Base)
                """));
    }

    @Test
    @DisplayName("класс с выражением-родителем появляется на своей строке, а не заранее")
    void expressionParentIsNotHoisted() {
        // Простое имя резолвер расставляет заранее, выражение — нет: по нему не видно,
        // кто от кого зависит, а вычислять его до первой инструкции нечего.
        assertTrue(errorOf("""
                class Base(title) { }
                registry = {"Base": Base}
                new Later("рано")
                class Later(title) : registry["Base"](title)
                """).getMessage().contains("переменная 'Later' не определена"));
    }

    @Test
    @DisplayName("выражение дало не класс — ошибка называет запись целиком")
    void expressionMustGiveAClass() {
        assertTrue(errorOf("""
                registry = {"Base": 42}
                class A : registry["Base"]()
                """).getMessage().contains("'registry[\"Base\"]'"));
    }

    // --- остаток в заголовке -------------------------------------------------

    @Test
    @DisplayName("остаток собирает лишние аргументы создания")
    void headerRestCollectsExtras() {
        assertEquals("1 2 [3, 4] {\"tag\": \"ok\"}", printed("""
                class Point(x, y, *rest, **named) {
                    def Point() {
                        println(x, " ", y, " ", rest, " ", named)
                    }
                }
                new Point(1, 2, 3, 4, tag: "ok")
                """));
    }

    @Test
    @DisplayName("остаток полем не становится: у экземпляра только позиционные параметры")
    void headerRestIsNotAField() {
        assertEquals("2 x,y", printed("""
                class Point(x, y, *rest, **named) { }
                p = new Point(1, 2, 3, tag: "ok")
                keys = ""
                for (k in p) keys = keys == "" ? k : keys + "," + k
                println(len(p), " ", keys)
                """));
    }

    @Test
    @DisplayName("класс-обёртка перебрасывает аргументы родителю")
    void proxyForwardsToParent() {
        assertEquals("В родителе было так: точка(3, 4)", printed("""
                class Point(x, y) {
                    def text() => "точка(" + x + ", " + y + ")"
                }
                class Proxy(*args, **named) : Point(*args, **named) {
                    def text() => "В родителе было так: " + super.text()
                }
                println(new Proxy(3, y: 4).text())
                """));
    }

    @Test
    @DisplayName("с остатком у создания нет верхней границы, обязательные всё равно нужны")
    void headerRestKeepsRequired() {
        assertTrue(errorOf("""
                class Point(x, y, *rest) { }
                new Point(1)
                """).getMessage().contains("обязательный параметр 'y' класса 'Point' не передан"));
    }

    @Test
    @DisplayName("остаток виден в аргументах родителю, а не только в конструкторе")
    void headerRestVisibleInParentArguments() {
        assertEquals("[1, 2] 3", printed("""
                class Base(all, size) {
                    def Base() {
                        println(all, " ", size)
                    }
                }
                class Head(size, *rest) : Base(rest, size) { }
                new Head(3, 1, 2)
                """));
    }
}
