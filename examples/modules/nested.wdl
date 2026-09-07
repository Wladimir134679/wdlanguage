// Модуль импортирует модуль по пути от общего корня проекта.
import lib.strings as text
import lib.geometry as g

println(text.describeCircle(2))
println(text.describePoint(new g.Point(3, 4)))

// Тот же модуль, импортированный из разных файлов, — один модуль:
// ключ у него один, и выполнялся он один раз.
println("geometry у главного скрипта и у lib/strings — один: ", g.PI == 3.14159)
