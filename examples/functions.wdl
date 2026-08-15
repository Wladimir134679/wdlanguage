// Функции: объявление, return, стрелка, анонимные и замыкания.
// Запуск: wdl examples/functions.wdl

// Объявление начинает существовать со своей строки: вызвать функцию выше её def
// нельзя — сначала объявление, потом вызов.
def sum(a, b) {
    return a + b;
}

println("sum(2, 3) = ", sum(2, 3))

// Тело из одной инструкции — фигурные скобки не нужны
def twice(x) return x * 2;

// Тело-выражение: => вместо return, и точка с запятой не нужна
def area(width, height) => width * height

println("twice(21) = ", twice(21))
println("area(3, 4) = ", area(3, 4))

// Функция без return не возвращает ничего — то есть null
def greet(name) {
    println("Привет, ", name, "!")
}
println("greet вернула ", typeof(greet("wdl")))

// --- рекурсия ---------------------------------------------------------------

def factorial(n) => n <= 1 ? 1 : n * factorial(n - 1)
println("5! = ", factorial(5))

// Взаимная рекурсия: обе функции найдены до выполнения
def even(n) => n == 0 ? true : odd(n - 1)
def odd(n) => n == 0 ? false : even(n - 1)
println("7 нечётное? ", odd(7))

// --- return из глубины ------------------------------------------------------

def indexOf(values, what) {
    for (i = 0; i < len(values); i += 1) {
        if (values[i] == what) {
            return i;            // выходит и из цикла, и из функции
        }
    }
    return -1;
}
println("индекс 'б' = ", indexOf(["а", "б", "в"], "б"))
println("индекс 'я' = ", indexOf(["а", "б", "в"], "я"))

// --- функция это обычное значение -------------------------------------------

operations = {
    "+": def(a, b) => a + b,
    "-": def(a, b) => a - b,
    "*": def(a, b) => a * b
}
for (sign in operations) {
    println("10 ", sign, " 4 = ", operations[sign](10, 4))
}

// Функция, принимающая функцию
def applyTwice(f, value) => f(f(value))
println("twice дважды от 5 = ", applyTwice(twice, 5))

// --- замыкания --------------------------------------------------------------

// Возвращённая функция помнит область, в которой её объявили, — и переменную,
// а не её снимок: каждый вызов tick видит изменение предыдущего.
def counter() {
    count = 0
    return def() {
        count += 1
        return count;
    };
}
tick = counter()
println("тики: ", tick(), tick(), tick())

// Свой счётчик у каждого вызова
other = counter()
println("другой тик: ", other())

// --- область видимости ------------------------------------------------------

outer = "снаружи"

def work() {
    inner = "внутри"
    println("вижу внешнюю: ", outer)
    outer = "изменена изнутри"     // имя есть снаружи — меняется именно оно
    return inner;
}

println("вернула: ", work())
println("outer теперь: ", outer)
// println(inner)   // ошибка: переменная 'inner' не определена
