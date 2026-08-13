// Модуль-плагин: наследуется от класса соседнего модуля и реализует его трейт.
//
// Загружается по требованию — из функции главного скрипта, а не при его запуске.
// Для самого модуля это ничего не меняет: он такой же обычный скрипт.

import shapes                   // сосед по каталогу: lib/shapes

println("  [lib/plugin выполняется — эта строка печатается при загрузке]")

class Triangle(base, height) : Shape("треугольник") with Countable {

    def area() => base * height / 2
    def count() => 3

    // Фабрика — способ создания с именем. Через модуль она тоже видна:
    // p.Triangle.equilateral(2)
    def Triangle.equilateral(side) => new Triangle(side, side)
}

def make(base, height) => new Triangle(base, height)
