// Значения параметров по умолчанию: def f(a, b = 10).
// Запуск: wdl examples/defaults.wdl

// Непереданный аргумент берётся из значения по умолчанию
def greet(name, greeting = "привет") => greeting + ", " + name

println(greet("мир"))
println(greet("мир", "здравствуй"))

// --- по умолчанию стоит выражение, а не литерал -----------------------------

// Считается оно при вызове, поэтому вызов функции здесь законен...
def now() => "2026-08-08"
def log(message, at = now()) => println(at, ": ", message)
log("старт")

// ...и не выполняется вовсе, если аргумент передали
def expensive() {
    println("  (считаю значение по умолчанию)")
    return 0;
}
def retry(what, from = expensive()) => println(what, " с ", from)
retry("иду", 5)
retry("иду")

// --- параметры слева видны, справа — нет ------------------------------------

def total(price, count = 1, tax = price * count * 0.2) => price * count + tax

println("итого: ", total(100))
println("итого: ", total(100, 2))
println("итого: ", total(100, 2, 0))

// def broken(a = b, b = 1) => a
// ошибка разбора: 'b' связывается позже, и молча взялась бы внешняя переменная

// --- значение своё на каждый вызов ------------------------------------------

// В Python такой словарь был бы один на все вызовы — здесь он создаётся заново
def box(value, holder = {}) {
    holder.value = value
    return holder;
}
println("коробки: ", box(1).value, " и ", box(2).value)

// Внешняя переменная читается в момент вызова, а не объявления
step = 10
def inc(x, by = step) => x + by
step = 20
println("inc(1) после смены шага: ", inc(1))

// --- анонимные функции ничем не отличаются ----------------------------------

double = def(x, by = 2) => x * by
handlers = {inc: def(x, step = 1) => x + step}
println(double(21), " ", handlers.inc(20))
