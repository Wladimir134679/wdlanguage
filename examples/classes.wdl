// Классы: заголовок — поля, тело — методы. Описание — в docs/classes.md.

// --- класс без тела: структура данных с именем -------------------------------

class Item(name, price, count = 0)

item = new Item("болт", 12)
println(item.name, " по ", item.price, " руб., на складе ", item.count)
println(item)                       // Item{"name": "болт", "price": 12, "count": 0}

// Поле — то же обращение, что у обычного объекта
field = "price"
println(item[field], " ", item["name"])
for (name in item) println("  ", name, " = ", item[name])

// --- методы: поле видно по имени, без this ------------------------------------

class Point(x = 0, y = 0) {

    fun move(dx, dy) {
        x += dx                     // x — это поле: одна ячейка с this.x
        y += dy
    }

    fun text() => "(" + x + ", " + y + ")"
}

p = new Point(20, 30)
p.move(10, -5)
println(p.text())                   // (30, 25)

// Метод — обычное значение и помнит свой объект
handler = p.text
println(handler())                  // (30, 25)

// this нужен там, где имя перекрыто параметром
class User(name, hash, age = null) {

    fun rename(name) {              // параметр перекрыл поле...
        this.name = name            // ...поэтому здесь без this никак
    }

    fun greet() => "привет, " + name    // а тут перекрывать нечему
}

u = new User("первый", "хеш")
u.rename("wdeath")
println(u.greet())                  // привет, wdeath

// --- конструктор: метод с именем класса, выполняется по готовому объекту ------

class Box(width, height) {

    fun Box() {
        // Поля уже записаны — правится поле, а не параметр
        if (width < 0) width = 0
        if (height < 0) height = 0
        this.area = width * height  // новое поле заводится явно, через this
    }

    fun text() => width + "x" + height + " = " + area
}

println(new Box(3, 4).text())       // 3x4 = 12
println(new Box(-3, 4).text())      // 0x4 = 0
println(new Box(3, 4))              // Box{"width": 3, "height": 4, "area": 12}

// --- разные способы создания: значения по умолчанию и фабрики -----------------

// Разное число аргументов — это значение по умолчанию, а не второй конструктор
u1 = new User("wdeath", "хеш")
u2 = new User("wdeath", "хеш", 33)
println(u1.age, " ", u2.age)        // null 33

// А другой набор данных — это фабрика с именем. Ей this не нужен: она создаёт
class Account(login, hash) {

    fun Account.of(login, password) => new Account(login, password + ":хеш")

    fun text() => login + " (" + hash + ")"
}

a = Account.of("wdeath", "секрет")  // видно, каким способом создан объект
println(a.text())                   // wdeath (секрет:хеш)

// --- наследование ------------------------------------------------------------

class Shape(name) {
    fun area() => 0
    fun text() => name + " площадью " + area()      // area() ≡ this.area()
}

class Circle(radius) : Shape("круг") {
    fun area() => 3.14159 * radius * radius
    fun text() => super.text() + " (радиус " + radius + ")"
}

class Square(side) : Shape("квадрат") {
    fun area() => side * side
}

figures = [new Circle(5), new Square(4)]
for (figure in figures) println(figure.text())

println(figures[0].name)            // круг — поле досталось от родителя

// --- типажи: заголовок — поля, тело — методы, и то и другое бывает требованием

trait Printable {
    fun text()                              // требование к классу
    fun print() => println("* ", text())    // готовая реализация
}

// count = 0 — поле с готовым значением, класс о нём не заботится
// limit    — требование: класс обязан объявить это поле сам
trait Counted(count = 0, limit) {
    fun inc() { count += 1 }
    fun full() => count >= limit
}

class Basket(items, limit = 10) with Printable, Counted {

    fun add(item) {
        items += [item]
        inc()                       // метод типажа работает с полем типажа
    }

    fun text() => len(items) + " шт. из " + limit
}

b = new Basket([])
b.add("болт")
b.add("гайка")
b.print()                           // * 2 шт. из 10
println(b)                          // Basket{"count": 2, "items": [...], "limit": 10}
println(b.full(), " ", b is Counted, " ", b is Printable)

// --- одинаковые имена: побеждает последний в ':' и 'with' ---------------------

trait Loud  { fun voice() => "ГРОМКО" }
trait Quiet { fun voice() => "тихо" }

class A(x) with Loud, Quiet
class B(x) with Quiet, Loud
class C(x) with Loud, Quiet { fun voice() => "по-своему" }

println(new A(1).voice(), " ", new B(1).voice(), " ", new C(1).voice())
// тихо ГРОМКО по-своему

// --- проверка класса ---------------------------------------------------------

c = new Circle(5)
println(c is Circle, " ", c is Shape, " ", c is Point)      // true true false
println(b is Basket, " ", b is Printable, " ", b is Counted)    // true true true
println(42 is Shape)                // false, а не ошибка
println(typeof(b), " ", typeof(Basket), " ", typeof(Counted))   // object class trait

// --- класс — обычное значение ------------------------------------------------

Point.zero = new Point(0, 0)        // «статическое поле» — обычная запись по ключу
println(Point.zero.text())          // (0, 0)

kinds = [Point, Item]
println(new kinds[0](1, 2).text())  // new берёт обращение и один список аргументов

fun build(cls, a, b) => new cls(a, b)
println(build(Point, 3, 4).text())

// --- что не разберётся -------------------------------------------------------

// class Broken(x) { x = 1 }        // в теле класса — только объявления функций
// class Bag(items) with Counted    // не выполнено требование: нет поля 'limit'
// new Printable()                  // типаж, экземпляр создаёт класс
// this.x = 1                       // 'this' вне класса
