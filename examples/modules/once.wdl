// Сценарий 4. Модуль выполняется один раз за запуск.
// Строку «counter выполняется» печатает сам модуль — и печатает её однажды,
// сколько бы раз его ни импортировали.

def step(size) {
    import lib.counter as c
    return c.bump(size);
}

println("шаг: ", step(1))
println("шаг: ", step(10))

import lib.counter as c
println("счётчик снаружи: ", c.count)

// Состояние модуля общее и живое: c.count видит работу c.bump(),
// хотя вызывали её из другой области.
c.bump()
println("после ещё одного шага: ", c.count)

// Классы тоже одни и те же при любом числе импортов — иначе 'is' лгал бы.
import lib.geometry
import lib.geometry as g
println("Point из двух импортов — один класс: ", new Point(1, 2) is g.Point)
