// Модуль, который сам импортирует соседа по короткому имени.
// Путь считается от каталога этого файла, поэтому здесь достаточно "geometry",
// хотя из главного скрипта тот же модуль называется lib.geometry.

import geometry as g

fun describePoint(point) => "точка " + point.text() + ", длина " + point.length()

fun describeCircle(radius) => "круг радиуса " + radius + ", площадь " + g.area(radius)
