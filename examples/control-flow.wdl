// Ветвления и циклы. Запуск: wdl examples/control-flow.wdl

temperature = 12

if (temperature > 25) {
    println("жарко")
} else if (temperature > 10) {
    println("нормально")
} else {
    println("холодно")
}

// Тело из одной инструкции скобок не требует
if (temperature > 0) println("выше нуля")

// Условное выражение — то же ветвление, но даёт значение
println("знак: ", temperature > 0 ? "плюс" : "минус")

// Цикл с предусловием
left = 3
while (left > 0) {
    println("осталось ", left)
    left -= 1
}

// Цикл со счётчиком: i живёт только внутри цикла
sum = 0
for (i = 1; i <= 5; i += 1) {
    sum += i
}
println("сумма 1..5 = ", sum)

// Перебор диапазона: границы включительны, шаг — единица
for (i in 1..5) print(i, " ")
println()

// 5..1 пуст, а не идёт вниз — ради этого правило и заведено:
// 0..n - 1 при n == 0 обязано дать ноль проходов, а не тихий проход в обратную сторону
n = 0
for (i in 0..n - 1) println("сюда не попадём")

// Перебор массива
cart = [
    {name: "гайка", price: 5},
    {name: "болт", price: 8},
    {name: "шайба", price: 2},
]
total = 0
for (item in cart) {
    println("  ", item.name, " — ", item.price, " руб.")
    total += item.price
}
println("итого: ", total, " руб.")

// Строка перебирается по символам, объект — по ключам
for (letter in "wdl") print(letter, ".")
println()

settings = {color: "белый", size: 10}
for (key in settings) println(key, " = ", settings[key])

// break прерывает цикл, continue — только текущий проход
for (item in cart) {
    if (item.price < 5) continue
    if (item.name == "болт") {
        println("нашли болт, дальше не ищем")
        break
    }
}

// Имя, заведённое в блоке, снаружи не существует — область создаёт блок
score = 0
for (x in [1, 2, 3]) {
    doubled = x * 2        // живёт только внутри этого прохода
    score += doubled       // а score — внешний, и меняется он снаружи
}
println("score = ", score)
