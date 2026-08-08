package ru.wds.wdl.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.wds.wdl.ast.Program;
import ru.wds.wdl.diagnostic.Diagnostics;
import ru.wds.wdl.lexer.Lexer;
import ru.wds.wdl.parser.Parser;
import ru.wds.wdl.resolve.Resolution;
import ru.wds.wdl.resolve.Resolver;
import ru.wds.wdl.source.Source;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Поведение классов и типажей: поля, методы, создание, наследование, {@code is}. */
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
        Resolution resolution = Resolver.resolve(program, diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки резолвера:\n" + diagnostics.renderAll());
        new Interpreter().run(program, resolution, ExecutionContext.fresh(output));
    }

    private static WdlRuntimeError errorOf(String code) {
        return assertThrows(WdlRuntimeError.class, () -> printed(code));
    }

    /** Ошибка, найденная до выполнения: резолвером. */
    private static String declarationError(String code) {
        Source source = Source.ofString(code);
        Diagnostics diagnostics = new Diagnostics(source);
        Program program = Parser.parseProgram(Lexer.tokenize(source, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "ошибки разбора:\n" + diagnostics.renderAll());
        Resolver.resolve(program, diagnostics);
        assertTrue(diagnostics.hasErrors(), () -> "ожидалась ошибка объявления для:\n" + code);
        return diagnostics.renderAll();
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
                    fun move(dx, dy) { x += dx; y += dy }
                    fun text() => "(" + x + ", " + y + ")"
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
                class Point(x, y) { fun text() => "(" + x + ", " + y + ")" }
                handler = new Point(1, 2).text
                println(handler())
                """));
    }

    @Test
    @DisplayName("методы не попадают ни в перебор, ни в len: они живут в классе")
    void methodsAreNotFields() {
        assertEquals("x y 2", printed("""
                class Point(x, y) { fun text() => x }
                p = new Point(1, 2)
                for (name in p) print(name, " ")
                println(len(p))
                """));
    }

    @Test
    @DisplayName("поле перекрывает метод: подмена поведения у одного объекта законна")
    void fieldOverridesMethod() {
        assertEquals("по-своему 3", printed("""
                class Point(x, y) { fun text() => "обычно" }
                p = new Point(1, 2)
                p.text = fun() => "по-своему"
                println(p.text(), " ", len(p))
                """));
    }

    @Test
    @DisplayName("связанный метод создаётся на каждом чтении: два чтения — два значения")
    void boundMethodIsFreshEachRead() {
        // Плата за то, что отдельного вида значения у метода нет. Для языка это
        // ничего не меняет: функции и без того сравниваются по ссылке
        assertEquals("false true", printed("""
                class Point(x, y) { fun text() => x }
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
                    fun show() => age
                    fun grow() { age = 33 }
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
                class Order(sum) { fun show() { println("ставка ", RATE) } }
                new Order(1).show()
                """));
    }

    // --- область видимости ---------------------------------------------------

    @Test
    @DisplayName("параметр перекрывает поле, this пробивается сквозь него")
    void parameterShadowsField() {
        assertEquals("привет, второй", printed("""
                class User(name) {
                    fun rename(name) { this.name = name }
                    fun greet() => "привет, " + name
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
                    fun Box() {
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
                    fun report() {
                        show = fun(n) => println(n * rate)
                        show(1)
                        show(2)
                    }
                }
                new Basket(10).report()
                """));
    }

    // --- создание ------------------------------------------------------------

    @Test
    @DisplayName("конструктор выполняется по готовому объекту и правит поля")
    void constructorRunsOnReadyObject() {
        assertEquals("0x4 = 0 3x4 = 12", printed("""
                class Box(width, height) {
                    fun Box() {
                        if (width < 0) width = 0
                        if (height < 0) height = 0
                        this.area = width * height
                    }
                    fun text() => width + "x" + height + " = " + area
                }
                println(new Box(-3, 4).text(), " ", new Box(3, 4).text())
                """));
    }

    @Test
    @DisplayName("к телу конструктора объект собран целиком: его можно отдать наружу")
    void constructorSeesWholeObject() {
        assertEquals("1 wdeath", printed("""
                registry = []
                class Session(user) { fun Session() { registry += [this] } }
                new Session("wdeath")
                println(len(registry), " ", registry[0].user)
                """));
    }

    @Test
    @DisplayName("число аргументов проверяется по заголовку, до входа в конструктор")
    void arityCheckedBeforeConstructor() {
        assertTrue(errorOf("""
                class Point(x, y) { fun Point() { println("не должно печататься") } }
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
    @DisplayName("бесконечное создание — ошибка скрипта, а не переполнение стека")
    void endlessCreation() {
        assertTrue(errorOf("class Node(next = new Node())\nnew Node()")
                .getMessage().contains("слишком глубокая рекурсия"));
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
        assertTrue(errorOf("trait T {}\nprintln(T.x)")
                .getMessage().contains("к значению типа типаж нельзя обратиться"));
    }

    @Test
    @DisplayName("типаж — тоже значение со своим типом")
    void traitIsAValue() {
        assertEquals("trait Counted trait", printed("""
                trait Counted(count = 0) { fun inc() { count += 1 } }
                println(Counted, " ", typeof(Counted))
                """));
    }

    // --- наследование --------------------------------------------------------

    @Test
    @DisplayName("поля и методы достаются от родителя, свой метод перекрывает родительский")
    void inheritance() {
        assertEquals("круг площадью 78.53975 круг квадрат площадью 16", printed("""
                class Shape(name) {
                    fun area() => 0
                    fun text() => name + " площадью " + area()
                }
                class Circle(radius) : Shape("круг") {
                    fun area() => 3.14159 * radius * radius
                }
                class Square(side) : Shape("квадрат") {
                    fun area() => side * side
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
                    fun area() => 0
                    fun text() => name + " площадью " + area()
                }
                class Circle(radius) : Shape("круг") {
                    fun area() => 3.14159 * radius * radius
                    fun text() => super.text() + " (радиус " + radius + ")"
                }
                println(new Circle(5).text())
                """));
    }

    @Test
    @DisplayName("area() и this.area() внутри метода от super дают одно и то же")
    void bareNameAndThisAgree() {
        assertEquals("25 25", printed("""
                class Shape(name) {
                    fun area() => 0
                    fun text() => area() + " " + this.area()
                }
                class Square(side) : Shape("квадрат") {
                    fun area() => side * side
                    fun text() => super.text()
                }
                println(new Square(5).text())
                """));
    }

    @Test
    @DisplayName("super — обычное значение: тот же объект, только точка отсчёта другая")
    void superIsOrdinaryValue() {
        assertEquals("родитель true true Circle{\"name\": \"круг\", \"radius\": 5}", printed("""
                class Shape(name) { fun text() => "родитель" }
                class Circle(radius) : Shape("круг") {
                    fun text() => "потомок"
                    fun show() {
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
                class A(x) { fun A() { print("A") } }
                class B(y) : A(1) { fun B() { print(" B") } }
                class C(z) : B(2) { fun C() { print(" C") } }
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
                fun mark() { calls += 1; return "имя"; }
                class Shape(name)
                class Circle(radius, name = "свой") : Shape(mark())
                new Circle(5)
                println(calls)
                """));
    }

    @Test
    @DisplayName("наследоваться можно от класса, объявленного ниже по тексту")
    void forwardInheritance() {
        assertEquals("true true", printed("""
                class Circle(radius) : Shape("круг")
                class Shape(name)
                c = new Circle(5)
                println(c is Circle, " ", c is Shape)
                """));
    }

    @Test
    @DisplayName("круг в наследовании и лишние аргументы родителю — ошибки до выполнения")
    void declarationErrors() {
        assertTrue(declarationError("class A(x) : B(1)\nclass B(y) : A(1)")
                .contains("циклическое наследование"));
        assertTrue(declarationError("class Shape(name)\nclass Circle(r) : Shape(1, 2)")
                .contains("принимает ровно 1 аргумент"));
        assertTrue(declarationError("class Circle(r) : Missing()")
                .contains("неизвестный класс 'Missing'"));
        assertTrue(declarationError("trait T {}\nclass A(x) : T()")
                .contains("типаж, а не класс"));
    }

    // --- типажи --------------------------------------------------------------

    @Test
    @DisplayName("типаж даёт классу поля и методы, и они работают вместе")
    void traits() {
        assertEquals("* 2 шт. из 10 Basket{\"count\": 2, \"items\": [\"болт\", \"гайка\"], \"limit\": 10}"
                + " false true true", printed("""
                trait Printable {
                    fun text()
                    fun print() => println("* ", text())
                }
                trait Counted(count = 0, limit) {
                    fun inc() { count += 1 }
                    fun full() => count >= limit
                }
                class Basket(items, limit = 10) with Printable, Counted {
                    fun add(item) { items += [item]; inc() }
                    fun text() => len(items) + " шт. из " + limit
                }
                b = new Basket([])
                b.add("болт")
                b.add("гайка")
                b.print()
                println(b, " ", b.full(), " ", b is Printable, " ", b is Counted)
                """));
    }

    @Test
    @DisplayName("невыполненное требование типажа — ошибка при объявлении класса")
    void unmetRequirements() {
        String noField = declarationError("""
                trait Counted(count = 0, limit) { fun inc() { count += 1 } }
                class Bag(items) with Counted
                """);
        assertTrue(noField.contains("не выполняет требование типажа 'Counted'"), noField);
        assertTrue(noField.contains("нет поля 'limit'"), noField);

        assertTrue(declarationError("""
                trait Printable { fun text() }
                class Bag(items) with Printable
                """).contains("нет метода 'text'"));

        assertTrue(declarationError("""
                trait Printable { fun text() }
                class Bag(items) with Printable { fun text(extra) => extra }
                """).contains("должен принимать"));
    }

    @Test
    @DisplayName("требование закрывается предком или другим типажом")
    void requirementsMetElsewhere() {
        assertEquals("есть есть", printed("""
                trait Printable { fun text() }
                trait Loud { fun text() => "есть" }
                class FromTrait(x) with Printable, Loud
                class Base(x) { fun text() => "есть" }
                class FromParent(x) : Base(1) with Printable
                println(new FromTrait(1).text(), " ", new FromParent(1).text())
                """));
    }

    @Test
    @DisplayName("побеждает последний: родитель, потом типажи слева направо, потом класс")
    void lastWins() {
        assertEquals("тихо ГРОМКО по-своему", printed("""
                trait Loud  { fun voice() => "ГРОМКО" }
                trait Quiet { fun voice() => "тихо" }
                class A(x) with Loud, Quiet
                class B(x) with Quiet, Loud
                class C(x) with Loud, Quiet { fun voice() => "по-своему" }
                println(new A(1).voice(), " ", new B(1).voice(), " ", new C(1).voice())
                """));
    }

    @Test
    @DisplayName("значение поля типажа считается на каждом создании заново")
    void traitDefaultsAreFresh() {
        assertEquals("[1] [2]", printed("""
                trait Log(entries = []) { fun add(x) { entries += [x] } }
                class Task(name) with Log
                a = new Task("первая")
                b = new Task("вторая")
                a.add(1)
                b.add(2)
                println(a.entries, " ", b.entries)
                """));
    }

    @Test
    @DisplayName("типажом экземпляр не создать")
    void traitIsNotCreatable() {
        assertTrue(errorOf("trait Counted(count = 0)\nnew Counted()")
                .getMessage().contains("типаж, экземпляр создаёт класс"));
    }

    // --- is ------------------------------------------------------------------

    @Test
    @DisplayName("is отвечает про класс, предка и типаж, а для не-экземпляра — false")
    void isOperator() {
        assertEquals("true true true false false false", printed("""
                trait Printable { fun print() => 1 }
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
    @DisplayName("справа от is должен стоять класс или типаж")
    void isNeedsClassOnRight() {
        assertTrue(errorOf("class A(x)\nprintln(new A(1) is 5)")
                .getMessage().contains("справа от 'is' должен стоять класс или типаж"));
    }

    // --- класс как значение --------------------------------------------------

    @Test
    @DisplayName("поле класса — обычная запись по ключу, методы через класс не читаются")
    void classIsAValue() {
        assertEquals("(0, 0) null", printed("""
                class Point(x, y) { fun text() => "(" + x + ", " + y + ")" }
                Point.zero = new Point(0, 0)
                println(Point.zero.text(), " ", Point.text)
                """));
    }

    @Test
    @DisplayName("класс можно передать и положить в массив: new берёт значение слева")
    void classCanBePassed() {
        assertEquals("(1, 2) (3, 4)", printed("""
                class Point(x, y) { fun text() => "(" + x + ", " + y + ")" }
                fun build(cls, a, b) => new cls(a, b)
                kinds = [Point]
                println(new kinds[0](1, 2).text(), " ", build(Point, 3, 4).text())
                """));
    }

    @Test
    @DisplayName("фабрика — способ создания с именем; снаружи то же самое даёт присваивание")
    void factories() {
        assertEquals("wdeath-хеш гость-хеш", printed("""
                class User(login, hash) {
                    fun User.of(login, password) => new User(login, password + "-хеш")
                    fun text() => hash
                }
                User.guest = fun() => new User("гость", "гость-хеш")
                println(User.of("wdeath", "wdeath").text(), " ", User.guest().text())
                """));
    }

    @Test
    @DisplayName("класс из функции замыкает свой вызов, но для is остаётся тем же классом")
    void classInsideFunction() {
        // Два вызова дают два значения класса с разными замыканиями — и один и тот же
        // класс: объявлен-то он в одном и том же месте текста
        assertEquals("10 20 true", printed("""
                fun kind(rate) {
                    class Priced(sum) { fun total() => sum * rate }
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
                fun make() { class Priced(sum) fun ignored() => 1 }
                make()
                new Priced(1)
                """).getMessage().contains("переменная 'Priced' не определена"));
    }
}
