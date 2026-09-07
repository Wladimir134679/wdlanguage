// Все импорты считаются от корня проекта.
import lib.geometry as g

def describePoint(point) => "точка " + point.text() + ", длина " + point.length()

def describeCircle(radius) => "круг радиуса " + radius + ", площадь " + g.area(radius)
