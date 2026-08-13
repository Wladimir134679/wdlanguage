// Модуль, который сам импортирует соседа по короткому имени.
// Путь считается от каталога этого файла, поэтому здесь достаточно "geometry",
// хотя из главного скрипта тот же модуль называется lib.geometry.

import geometry as g

def describePoint(point) => "точка " + point.text() + ", длина " + point.length()

def describeCircle(radius) => "круг радиуса " + radius + ", площадь " + g.area(radius)
