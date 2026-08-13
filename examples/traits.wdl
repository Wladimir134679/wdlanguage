// Трейты: что класс обязан дать и что он получает готовым.
// Основы классов — в examples/classes.wdl, наследование — в examples/inheritance.wdl.
// Описание правил — в docs/classes.md.
//
// Трейт устроен как класс: заголовок — поля, тело — методы. Разница одна, и она
// определяет всё остальное: и поле, и метод бывают ТРЕБОВАНИЕМ. Объявлено
// без значения и без тела — класс обязан дать это сам.

// --- 1. трейт как интерфейс: одни требования ---------------------------------

trait Comparable {
    def compareTo(other)            // требование: тело не написано
}

class Money(amount) with Comparable {
    def compareTo(other) => amount - other.amount
}

println(new Money(10).compareTo(new Money(4)))      // 6

// Требование проверяется по числу аргументов тоже:
// class Broken(x) with Comparable { def compareTo() => 0 }
// ошибка: метод 'compareTo' должен принимать ровно 1 аргумент, а принимает 0

// --- 2. трейт с готовой реализацией: требование плюс подарок -----------------

trait Printable {
    def text()                              // это класс обязан дать
    def print() => println("* ", text())    // а это он получает готовым
}

class Tag(label) with Printable {
    def text() => "#" + label
}

new Tag("готово").print()           // * #готово

// --- 3. трейт с состоянием: поле со значением --------------------------------

// count = 0 — поле с готовым значением, класс о нём не заботится.
// limit     — требование: класс обязан объявить это поле сам.
trait Counted(count = 0, limit) {
    def inc() { count += 1 }
    def full() => count >= limit
    def left() => limit - count
}

class Basket(items, limit = 10) with Counted {

    def add(item) {
        items += [item]
        inc()                       // метод трейта работает с полем трейта
    }
}

b = new Basket([])
b.add("болт")
b.add("гайка")
println(b.count, " из ", b.limit, ", осталось ", b.left(), ", полна: ", b.full())
println(b)                          // поля трейта — обычные поля экземпляра

// --- 4. несколько трейтов сразу ----------------------------------------------

class Cart(items, limit = 3) with Printable, Counted {

    def add(item) {
        items += [item]
        inc()
    }

    def text() => len(items) + " шт. из " + limit
}

cart = new Cart([])
cart.add("шайба")
cart.print()                        // * 1 шт. из 3
println(cart is Cart, " ", cart is Printable, " ", cart is Counted)

// --- 5. требование можно закрыть предком или другим трейтом ------------------

// Смотрят в готовую плоскую таблицу, поэтому неважно, откуда взялся метод:
// свой, родительский или из соседнего трейта.

trait Loud { def text() => "ГРОМКО" }

class FromNeighbour(x) with Printable, Loud     // text() дал соседний трейт
new FromNeighbour(1).print()                    // * ГРОМКО

class Named(title) { def text() => "имя " + title }
class FromParent(title) : Named(title) with Printable   // text() дал родитель
new FromParent("узел").print()                          // * имя узел

// --- 6. одинаковые имена: побеждает последний --------------------------------

// Порядок записан прямо в объявлении: родитель из ':', затем трейты из 'with'
// слева направо, затем сам класс. Никаких правил линеаризации знать не нужно.

trait Upper { def voice() => "ГРОМКО" }
trait Lower { def voice() => "тихо" }

class A(x) with Upper, Lower                                 // Lower записан позже
class B(x) with Lower, Upper
class C(x) with Upper, Lower { def voice() => "по-своему" }   // класс всегда последний

def voiceOf(who) => who.voice()
println(voiceOf(new A(1)), " ", voiceOf(new B(1)), " ", voiceOf(new C(1)))
// тихо ГРОМКО по-своему

// --- 7. трейт вместе с наследованием -----------------------------------------

trait Serial(serial = "нет") {
    def stamp() => "#" + serial
}

class Device(model) {
    def text() => "устройство " + model
}

class Printer(model, serial) : Device(model) with Serial, Printable {
    // text() достался от родителя, print() — от Printable, stamp() — от Serial
}

pr = new Printer("PX-10", "A1")
pr.print()                          // * устройство PX-10
println(pr.stamp(), " ", pr is Device, " ", pr is Serial, " ", pr is Printable)

// --- 8. значение поля трейта считается на каждом создании --------------------

trait Stamped(at = now()) {
    def when() => at
}

def now() {
    ticks += 1
    return "момент " + ticks;
}
ticks = 0

class Event(name) with Stamped
println(new Event("первое").when(), " / ", new Event("второе").when())
// момент 1 / момент 2 — не один общий снимок на все экземпляры

// --- 9. когда проверяется требование -----------------------------------------

// При объявлении класса — то есть при выполнении инструкции 'class', а не при
// создании экземпляра, как абстрактные методы в Python. Разницу видно здесь:
// класс объявлен внутри функции, и пока функцию не позвали, объявление
// не выполнялось — ошибки нет и быть не может.

def makeBroken() {
    class Broken(items) with Printable      // text() не объявлен
    return new Broken([]);
}

println("функция с испорченным классом объявлена — и это ничему не мешает")
// makeBroken()
// ошибка на строке class: класс 'Broken' не выполняет требование трейта
// 'Printable': нет метода 'text'. До 'new Broken([])' дело не дойдёт.

// --- 10. трейт — значение, но не класс ---------------------------------------

println(typeof(Printable), " ", typeof(Counted))    // trait trait
println(new Tag("x") is Printable, " ", 42 is Printable)    // true false

// new Printable()                  // ошибка: трейт, экземпляр создаёт класс
// class Bag(items) with Money      // ошибка: 'Money' — класс, а не трейт
// class Bag(items) : Printable()   // ошибка: 'Printable' — трейт, а не класс
// class Bag(items) with Counted    // ошибка: нет поля 'limit'
