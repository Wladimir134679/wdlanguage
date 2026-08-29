// Члены значений: то, что значение отвечает на обращение по имени.
// Описание — в docs/members.md, правило обращения — в docs/access.md.
//
// Интроспекция лежит в examples/introspection.wdl, расширение своими членами —
// в examples/extend.wdl.

// --- одно правило: член это то же обращение по ключу --------------------------

text = "Hello"
println(text.size)                  // 5
println(text["size"])               // 5 — та же запись другой формой
key = "si" + "ze"
println(text[key])                  // 5 — имя члена можно вычислить

// Метод — обычное значение: читается в переменную и не теряет получателя
a = [1, 2]
push = a.push
push(3)
println(a)                          // [1, 2, 3]

// --- свойство или метод -------------------------------------------------------
//
// Свойство — ответ о значении, каким оно видно сейчас; изменить его может только
// действие, написанное в тексте. Метод — есть аргументы, меняется получатель,
// ответ приходит из времени, или честное имя для него глагол.

numbers = [3, 1, 2]
println(numbers.size)               // 3   — свойство
println(numbers.first)              // 3   — свойство
println(numbers.sorted)             // [1, 2, 3] — свойство-снимок: новый массив
println(numbers)                    // [3, 1, 2] — получатель не тронут
numbers.sort()                      // метод: переставляет на месте
println(numbers)                    // [1, 2, 3]

before = numbers.size
numbers.push(4)                     // вот оно, действие — оно написано
println(before, " -> ", numbers.size)   // 3 -> 4

// --- массив -------------------------------------------------------------------

items = ["ключ", "болт", "гайка"]
println(items.last)                 // гайка
println(items.empty)                // false
println(items.join(", "))           // ключ, болт, гайка
println(items.indexOf("болт"))      // 1
println(items.contains("шайба"))    // false
println(items.slice(1, 3))          // ["болт", "гайка"]
println(items.reversed)             // снимок, items не меняется

items.insert(0, "шайба")
println(items.remove(1))            // ключ — метод отдаёт удалённое
println(items.pop())                // гайка
println(items)                      // ["шайба", "болт"]

// --- строка: неизменяема, поэтому меняющих членов нет вовсе -------------------

name = "  Иван Петров  "
println(name.trimmed.upper)         // ИВАН ПЕТРОВ — цепочка читается слева направо
println(name.trimmed.split(" "))    // ["Иван", "Петров"]
println("отчёт.txt".endsWith(".txt"))
println("да-да-да".replace("да", "нет"))
println("42".toNumber() + 1)        // 43 — глагол в имени: это метод
println("не число".toNumber())      // null — разбор мог не получиться

// Размер и индекс — в кодовых единицах UTF-16, обход — по кодовым точкам
smile = "😀"
println(smile.size)                 // 2
println(smile.chars.size)           // 1 — символ не разорван пополам

// --- число --------------------------------------------------------------------

println(213.toString() + "!")       // 213! — точка у литерала разбирается как обращение
println((0 - 7).abs)                // 7
println(2.7.floor, " ", 2.7.ceil)   // 2 3
println(3.14159.round(2))           // 3.14
println(42.integer)                 // true
println(120.clamp(0, 100))          // 100

// Осторожно: обращение связывает сильнее унарного минуса
println(-5.abs)                     // -5, это -(5.abs)
println((-5).abs)                   // 5

// --- объект: данные важнее членов ---------------------------------------------

box = {size: "L", color: "red"}
println(box.size)                   // L — это данные, их клали руками
println(len(box))                   // 2 — а это размер
println(Object.size(box))           // 2 — надёжный путь, через дескриптор типа
println(box.keys)                   // ["size", "color"] — имя keys не занято
println(box.has("color"))           // true

// --- дескриптор типа: путь в обход данных -------------------------------------

println(Array.size([1, 2, 3]))      // 3
println(String.upper("тише"))       // ТИШЕ
println(box.type)                   // object
println(Number.info.title)          // число — справка о типе лежит под 'info'

// --- чего у членов нет ---------------------------------------------------------

// Перебором членов не видно: цикл идёт по данным
for (key in box) println("  ", key, " = ", box[key])

// Записи у встроенного члена нет — свойство обещает, что за ним ничего не происходит
try {
    box2 = [1, 2]
    box2.size = 5
} catch (e) {
    println(e.message)
}

// Промах по имени называет тип, промах и похожее имя
try {
    println([1, 2].sze)
} catch (e) {
    println(e.message)
}

// Свойство со скобками говорит именно про скобки
try {
    println([1, 2].size())
} catch (e) {
    println(e.message)
}
