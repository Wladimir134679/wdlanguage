// Профиль: где скрипт проводит время.
// Запуск: wdl --profile examples/tooling/profiling.wdl
//         wdl --profile-out profile.json examples/tooling/profiling.wdl

// Рекурсия: вызовов много, каждый дешёвый.
def fib(n) {
    if (n < 2) {
        return n;
    }
    return fib(n - 1) + fib(n - 2);
}

// Цикл: вызов один, работы внутри много.
def sumTo(limit) {
    total = 0
    for (i in 1..limit) {
        total = total + i
    }
    return total;
}

// Функция, которая только раздаёт работу: у неё большое «всего» и почти нулевое «сам».
def report(n, limit) {
    println("fib(", n, ") = ", fib(n))
    println("сумма до ", limit, " = ", sumTo(limit))
}

class Point(x, y) {
    def lengthSquared() => this.x * this.x + this.y * this.y
}

report(20, 100000)

// Создание экземпляра — своя строка профиля, метод класса — обычная функция.
far = new Point(3, 4)
near = new Point(1, 2)
println("длины в квадрате: ", far.lengthSquared(), " и ", near.lengthSquared())
