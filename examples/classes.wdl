// Классы: заголовок — поля, тело — методы. Описание — в docs/classes.md.
//
// Наследование лежит в examples/inheritance.wdl, трейты — в examples/traits.wdl,
// классы из других файлов — в examples/modules/inherit.wdl.

// --- класс без тела: структура данных с именем -------------------------------

class Item(name, price, count = 0)

item = new Item("болт", 12)
println(item.name, " по ", item.price, " руб., на складе ", item.count)
println(item)                       // Item{"name": "болт", "price": 12, "count": 0}

// Поле — то же обращение, что у обычного объекта
field = "price"
println(item[field], " ", item["name"])
for (name in item) println("  ", name, " = ", item[name])

// Поля обычные: их можно менять и добавлять снаружи
item.count += 5
item.color = "оцинкованный"
println(item.count, " ", item.color)

// --- методы: поле видно по имени, без this ------------------------------------

class Point(x = 0, y = 0) {

    fun move(dx, dy) {
        x += dx                     // x — это поле: одна ячейка с this.x
        y += dy
    }

    fun length() => sqrt(x * x + y * y)

    fun text() => "(" + x + ", " + y + ")"
}

p = new Point(20, 30)
p.move(10, -5)
println(p.text(), " длина ", p.length())    // (30, 25) длина 39.05124837953327

// Метод — обычное значение и помнит свой объект
handler = p.text
println(handler())                  // (30, 25)

// Метод зовёт метод по голому имени — это тот же объект
class Rect(w, h) {
    fun area() => w * h
    fun report() => "площадь " + area() + ", периметр " + perimeter()
    fun perimeter() => 2 * (w + h)
}
println(new Rect(3, 4).report())    // площадь 12, периметр 14

// --- this нужен там, где имя перекрыто параметром -----------------------------

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
    fun Account.guest() => new Account("guest", "")

    fun text() => login + " (" + hash + ")"
}

a = Account.of("wdeath", "секрет")  // видно, каким способом создан объект
println(a.text())                   // wdeath (секрет:хеш)
println(Account.guest().text())     // guest ()

// --- класс — обычное значение ------------------------------------------------

Point.zero = new Point(0, 0)        // «статическое поле» — обычная запись по ключу
println(Point.zero.text())          // (0, 0)

kinds = [Point, Item]
println(new kinds[0](1, 2).text())  // new берёт обращение и один список аргументов

fun build(cls, a, b) => new cls(a, b)
println(build(Point, 3, 4).text())

println(typeof(Point), " ", typeof(new Point()), " ", new Point(1, 1) is Point)
// class object true

// --- класс, объявленный внутри функции ---------------------------------------

// Он замыкает свой вызов: у двух вызовов разные значения класса, но для 'is'
// это один и тот же класс — объявлен-то он в одном и том же месте текста.
fun priced(rate) {
    class Priced(amount) {
        fun total() => amount * rate
    }
    return Priced;
}

ten = priced(10)
twenty = priced(20)
println(new ten(2).total(), " ", new twenty(2).total())     // 20 40
println(new ten(1) is twenty)                               // true

// --- что не разберётся или упадёт --------------------------------------------

// class Broken(x) { x = 1 }        // в теле класса — только объявления функций
// this.x = 1                       // 'this' вне класса
// new Point(1, 2, 3)               // класс 'Point' принимает от 0 до 2 аргументов
// Point.zero.text                  // это значение метода, вызов — со скобками
