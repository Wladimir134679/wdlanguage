// Модуль с константой, функцией и классом.
// Всё, что объявлено здесь в корне, и есть содержимое модуля.

const PI = 3.14159

def area(radius) => PI * radius * radius

class Point(x, y) {
    def length() => sqrt(x * x + y * y)

    def text() => "(" + x + ", " + y + ")"
}
