// Остаток в заголовке класса: *args и **named.
// Запуск: wdl examples/class-varargs.wdl
//
// Правила — в docs/classes.md. Коротко: те же, что у функции, плюс одно отличие —
// остаток полем не становится.

// --- собрать лишнее ----------------------------------------------------------

class Box(label, *rest, **options) {
    def Box() {
        println(label, ": ", rest, " ", options)
    }
}

new Box("коробка", 1, 2, 3, hidden: true)   // коробка: [1, 2, 3] {"hidden": true}
new Box("пустая")                           // пустая: [] {}

// --- остаток полем не становится ---------------------------------------------

b = new Box("вещь", 1, 2, tag: "ok")
println(len(b))                             // 1 — поле ровно одно
println(b)                                  // Box{"label": "вещь"}

// Иначе обёртка не была бы незаметной: два лишних ключа увидели бы и перебор,
// и len, и печать.

// --- переброска родителю: ради этого остаток и заводился ---------------------

class Point(x, y = 0) {
    def text() => "(" + x + ", " + y + ")"
}

class Logged(*args, **named) : Point(*args, **named) {
    def Logged() {
        println("создан из ", args, " и ", named)
    }
    def text() => "[log] " + super.text()
}

println(new Logged(3, 4).text())            // [log] (3, 4)
println(new Logged(x: 5, y: 6).text())      // [log] (5, 6)
println(new Logged(7).text())               // [log] (7, 0) — значение по умолчанию цело

// --- остаток виден в аргументах родителю -------------------------------------

class Base(all, size) {
    def text() => size + " шт: " + all
}

class Head(size, *rest) : Base(rest, size) { }

println(new Head(3, "а", "б", "в").text())  // 3 шт: ["а", "б", "в"]

// --- обязательные параметры остаются обязательными ---------------------------

class Pair(left, right, *extra) { }

println(try? new Pair(1))                   // null: 'right' не передан
println(new Pair(1, 2, 3, 4) is Pair)       // true: верхней границы больше нет
