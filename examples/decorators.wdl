// Декораторы: функция, применённая к объявлению в момент объявления.
// Запуск: wdl examples/decorators.wdl
//
// Правила — в docs/decorators.md. Коротко: применяются снизу вверх, первым
// аргументом всегда идут метаданные, а вернуть можно замену цели.

import sys.meta as m

// --- регистрация: декоратор ничего не возвращает -----------------------------

commands = {}

def command(meta, name) {
    commands[name] = meta.target
}

@[command]("привет")
def greet(who) => "Привет, " + who + "!"

@[command]("пока")
def farewell(who) => "Пока, " + who + "."

println(commands["привет"]("Аня"))          // Привет, Аня!
println(commands["пока"]("Аня"))            // Пока, Аня.
println(greet("Боря"))                      // цель не подменялась: работает как обычно

// --- обёртка: декоратор возвращает замену ------------------------------------

calls = []

def counted(meta) {
    return like(meta.target, def (*args, **named) {
        calls = calls + [meta.name]
        return meta.target(*args, **named);
    });
}

@[counted]
def sum(a, b = 10) => a + b

println(sum(1))                             // 11
println(sum(a: 1, b: 7))                    // 8 — имена и пропуски проходят сквозь like
println(calls)                              // ["sum", "sum"]

// like оставил цели её контракт: ошибка приходит на строку вызова, а не изнутри обёртки
println(try? sum(1, 2, 3))                  // null, и сообщение назвало бы 'sum'

// --- порядок: наблюдатель ближе к def, обёртка выше --------------------------

def watch(meta, tag) {
    println(tag, " видит ", meta.name, " (", typeof(meta.target), ")")
}

@[counted]
@[watch]("наблюдатель")
def action() => "сделано"

println(action())                           // наблюдатель видит action (function)

// --- метаданные и sys.meta ---------------------------------------------------

def describe(meta) {
    println(meta.name, ": ", typeof(meta.target), ", анонимна: ", meta.isAnonymous)
}

@[describe]
def named(x) => x

anonymous = def (x) => x
describe(m.of(anonymous))                   // декоратор — обычная функция, зовётся руками

// Имя берётся какое есть: у объявления своё, у анонимной — имя переменной.
// Если имени нет вовсе, в name лежит null, а не выдуманное "def".
println(m.of(named).name, " / ", m.of(anonymous).name, " / ", m.of(def (x) => x).name)

// --- классы: обёртка это наследник -------------------------------------------

def traced(meta) {
    class Proxy(*args, **named) : meta.target(*args, **named) {
        def text() => "[trace] " + super.text()
    }
    return Proxy;
}

@[traced]
class Point(x, y) {
    def text() => "(" + x + ", " + y + ")"
}

println(new Point(3, y: 4).text())          // [trace] (3, 4)

// --- декоратор внутри функции срабатывает на каждом проходе ------------------

passes = 0
def tick(meta) {
    passes = passes + 1
}

def outer() {
    @[tick]
    def inner() => 1
    return inner();
}

outer()
outer()
println("проходов: ", passes)               // проходов: 2
