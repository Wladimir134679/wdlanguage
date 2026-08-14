// Что модуль на Java даёт скрипту, кроме функций: контракт, иерархию классов
// и ошибки, которые говорят на языке скрипта.
//
// Смысл здесь один и тот же везде: написанное на Java неотличимо от написанного
// на wdl. Трейт проверяется на строке 'class', наследник отвечает 'is' предку,
// ошибка библиотеки ловится обычным catch по классу.
//
// Как это устроено со стороны Java — в docs/embedding.md.

import sys.json as json
import sys.io as io

// --- трейт от модуля: контракт, который выполняет скрипт ---------------------

// json.Serializable требует ровно одно — 'def toJson()'. Нужен он там, где
// записать надо не то, что лежит: деньги строкой, а секрет не записывать вовсе.
class Money(amount, currency = "RUB") with json.Serializable {
    def toJson() => amount + " " + currency
}

class Order(id, total, secret = "не для JSON") with json.Serializable {
    def toJson() => {id: id, total: total}
}

order = new Order(7, new Money(1250))

println(order is json.Serializable)         // true
println(json.stringify(order))              // {"id":7,"total":"1250 RUB"}

// Требование проверяется при объявлении класса, а не при записи:
// class Broken(id) with json.Serializable { }
//   ошибка: класс 'Broken' не выполняет требование трейта 'Serializable':
//   нет метода 'toJson'

// Без трейта экземпляр пишется как обычный объект — полем в поле
class Plain(id, secret = "видно") {
    def toJson() => {id: id}                // имя ничего не решает: обещания нет
}
println(json.stringify(new Plain(7)))       // {"id":7,"secret":"видно"}

// --- иерархия классов от модуля ----------------------------------------------

path = "build/embedding.txt"

w = io.create(path)
w.writeLine("первая").writeLine("вторая")
w.close()

use (r = io.open(path)) {
    // Reader и Writer наследуют io.Stream — общее у них поле path, close()
    // и само обещание Closeable
    println(r is io.Reader, " ", r is io.Stream, " ", r is io.Writer)
    println(r is Closeable, " ", r.readLine())
}

// --- ошибки библиотеки ловятся по классу -------------------------------------

// Проверка аргументов у всех библиотек одна, поэтому и сообщение одно:
// кого позвали, какой аргумент, чего ждали и что пришло
try {
    io.read(7)
} catch (e is TypeError) {
    println(e.message)      // read(): путь к файлу: ожидалась строка, а здесь число (7)
}

// «Тип тот, а значение не годится» — отдельный класс ошибки, ловится отдельно
try {
    sqrt(-1)
} catch (e is ValueError) {
    println(e.message)      // sqrt(): аргумент: ожидалось неотрицательное число...
}

// --- класс от приложения — обычное значение ----------------------------------

// В поля класса можно писать, как и у класса на wdl
io.Stream.description = "открытый файл"
println(io.Stream.description)

// Но живёт эта запись ровно один запуск: имена библиотеки собираются заново
// на каждый, поэтому следующий скрипт увидит здесь null, а не «открытый файл».
// Ничего от одного запуска другому не достаётся — ни поле, ни класс.

io.remove(path)
