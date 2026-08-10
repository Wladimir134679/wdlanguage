// Модуль с константой, функцией и классом.
// Всё, что объявлено здесь в корне, и есть содержимое модуля.

const PI = 3.14159

fun area(radius) => PI * radius * radius

class Point(x, y) {
    fun length() => sqrt(x * x + y * y)

    fun text() => "(" + x + ", " + y + ")"
}
