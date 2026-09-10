// Шпаргалка по выражениям: что и как считается.
//
// Запуск:  ./gradlew :wdl-cli:run --args="examples/language/expressions.wdl"
//          wdl --ast examples/language/expressions.wdl     — посмотреть дерево
//          wdl --tokens examples/language/expressions.wdl  — посмотреть токены

// --- числа: целое остаётся целым, пока не встретилось вещественное -----------
println("2 + 2 * 2      = ", 2 + 2 * 2)        // 6: умножение связывает сильнее
println("(2 + 2) * 2    = ", (2 + 2) * 2)      // 8
println("6 / 3          = ", 6 / 3)            // 2 — делится нацело, значит целое
println("7 / 2          = ", 7 / 2)            // 3.5
println("2 * 3.0        = ", 2 * 3.0)          // 6.0 — вещественное «заражает» пример
println("17 % 5         = ", 17 % 5)           // 2

// --- биты: связывают сильнее сравнений, в отличие от Си ----------------------
println("0xF0 | 0b1111  = ", 0xF0 | 0b1111)    // 255
println("1 << 10        = ", 1 << 10)          // 1024
println("~0             = ", ~0)               // -1

// --- сравнения и логика ------------------------------------------------------
println("1 == 1.0       = ", 1 == 1.0)         // true: числа сравниваются по величине
println("1 == \"1\"       = ", 1 == "1")         // false: типы не приводятся
println("null || \"да\"   = ", null || "да")     // ленивое ИЛИ возвращает операнд
println("17 % 5 > 1     = ", 17 % 5 > 1 ? "больше" : "меньше")

// --- принадлежность: in, has и отрицания -------------------------------------
// Одна реализация на все записи: 'x in a', 'a has x' и 'a.contains(x)' отвечают
// одинаково всегда, а не пока за ними следят.
roles = ["admin", "editor"]
box = {color: "синий", size: 10}

println("\"admin\" in roles = ", "admin" in roles)      // true — значение массива
println("roles has \"admin\" = ", roles has "admin")    // то же самое, от контейнера
println("\"guest\" !in roles = ", "guest" !in roles)    // true
println("\"ход\" in \"переход\" = ", "ход" in "переход")  // true — подстрока
println("\"color\" in box  = ", "color" in box)         // true — КЛЮЧ объекта
println("\"синий\" in box  = ", "синий" in box)         // false: значение не ключ
println("5 !is String    = ", 5 !is String)           // true

// --- диапазон: обычное значение, а не форма записи ---------------------------
workingAge = 18..65
println("18..65         = ", workingAge)               // 18..65
println("typeof         = ", typeof(workingAge))       // range
println("42 in 18..65   = ", 42 in workingAge)         // true, границы включительны
println("(5..1).empty   = ", (5..1).empty)             // true: 5..1 пуст, а не идёт вниз
println("границы        = ", workingAge.from, "..", workingAge.to)

// Диапазон сильнее сравнений и слабее арифметики: 'x in 1..5' — это
// принадлежность диапазону, а '0..n - 1' — это '0..(n - 1)'.
n = 3
println("0..n - 1       = ", 0..n - 1)                 // 0..2

// --- строки ------------------------------------------------------------------
println("конкатенация   = ", "итого: " + 42)
println("символ строки  = ", "привет"[0])
println("длина          = ", len("привет"))

// --- обращение: точка и скобки — одна и та же операция -----------------------
values = [10, 20, 30]
style = {color: "синий", "background": "белый"}

println("values[1]      = ", values[1])
println("style.color    = ", style.color)
println("style[\"color\"]  = ", style["color"])
println("вычисленный ключ = ", style["back" + "ground"])
println("нет ключа      = ", style.size || "поля нет — получился null")

nested = {data: [{name: "болт"}]}
println("цепочка        = ", nested.data[0].name)

// --- присваивание работает через то же обращение ------------------------------
style.color = "зелёный"
values[0] += 5
println("после записи   = ", style.color, ", ", values[0])
