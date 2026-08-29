// Интроспекция: значение рассказывает о себе самому скрипту.
// Описание — в docs/members.md, раздел «Что рассказывает о себе значение».
//
// Ради этого многое и затевалось: тестовый фреймворк (examples/testing/),
// сборка CLI по функции и генерация формы по классу пишутся на самом wdl —
// движок и так знает имена параметров, состав класса и подмешанные трейты.

// --- функция ------------------------------------------------------------------

def total(price, count = 1, *rest) => price * count

println(total.name)                 // total
println(total.anonymous)            // false
println(total.arity.min, "..", total.arity.max)   // 1..null — остаток снимает границу
println(total.rest)                 // rest

// Параметр — объект, а не строка: за именами в контракт придут типы
for (p in total.params) {
    println("  ", p.name, p.required ? " (обязательный)" : " (необязательный)")
}

// «Имён нет» и «параметров нет» — разные ответы
println(println.params)             // null — контракт без имён
def nothing() => 1
println(nothing.params)             // []

// --- сборка вызова по сигнатуре: то, ради чего это и нужно --------------------

def call(f, values) {
    // Значения по именам параметров — простейший разбор аргументов командной строки
    args = []
    for (p in f.params) {
        args.push(values[p.name])
    }
    return f(*args);
}

println(call(total, {price: 100, count: 3}))    // 300

// --- класс и трейт --------------------------------------------------------------

trait Printable {
    def print()
    def describe() => "печатаемое"
}

class Shape(name) {
    def area() => 0
}

class Circle(name, radius) : Shape(name) with Printable {
    property square => 3.14159 * radius * radius

    def area() => square
    def print() { println(name, ": ", area()) }
}

println(Circle.name)                // Circle
println(Circle.parent.name)         // Shape — родитель отдаётся значением
println(Circle.parent is Class)     // true
println(Circle.traits)              // [trait Printable]
println(Circle.methods)             // ["area", "describe", "print"] — плоская таблица
println(Circle.properties)          // ["square"]

// Заголовок класса — это его поля, и он же список параметров создания
names = []
for (p in Circle.params) names.push(p.name)
println(names)                      // ["name", "radius"]

// Трейт вдобавок рассказывает про требования — то, чего у класса нет
println(Printable.requirements)     // ["print"]
println(Printable.methods)          // ["describe"]

// Значения, а не имена: их сразу можно спросить у 'is'
mixin = Circle.traits[0]
circle = new Circle("круг", 2)
println(circle is mixin)            // true
println(circle is Circle.parent)    // true

// --- надёжный путь, когда имя занято данными ------------------------------------

Shape.methods = "своё"              // законная запись в статику класса
println(Shape.methods)              // своё — данные раньше членов
println(Class.methods(Shape))       // ["area"] — путь через дескриптор

// --- честная оговорка: декоратор подменяет значение -----------------------------

def log(meta) => def(*args, **named) => meta.target(*args, **named)

@[log]
def raw(price, count) => price * count

println(raw.params)                 // [] — это параметры обёртки, а не raw

// Спасает like(): она делегирует контракт цели
def kept(meta) => like(meta.target, def(*args, **named) => meta.target(*args, **named))

@[kept]
def wrapped(price, count) => price * count

names = []
for (p in wrapped.params) names.push(p.name)
println(names)                      // ["price", "count"] — имена пережили декоратор
