// Модуль-плагин: наследуется от класса соседнего модуля и реализует его трейт.
//
// Загружается по требованию — из функции главного скрипта, а не при его запуске.
// Для самого модуля это ничего не меняет: он такой же обычный скрипт.

import shapes                   // сосед по каталогу: lib/shapes

println("  [lib/plugin выполняется — эта строка печатается при загрузке]")

class Triangle(base, height) : Shape("треугольник") with Countable {

    fun area() => base * height / 2
    fun count() => 3

    // Фабрика — способ создания с именем. Через модуль она тоже видна:
    // p.Triangle.equilateral(2)
    fun Triangle.equilateral(side) => new Triangle(side, side)
}

fun make(base, height) => new Triangle(base, height)
