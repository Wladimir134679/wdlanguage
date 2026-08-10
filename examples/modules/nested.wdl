// Сценарий 5. Модуль импортирует модуль.
// lib/strings.wdl пишет 'import geometry' — по короткому имени, потому что путь
// считается от каталога того файла, где написан import. Отсюда и переносимость:
// папку lib можно положить куда угодно целиком.

import lib.strings as text
import lib.geometry as g

println(text.describeCircle(2))
println(text.describePoint(new g.Point(3, 4)))

// Тот же модуль, найденный двумя разными путями из разных файлов, — один модуль:
// ключ у него один, и выполнялся он один раз.
println("geometry у главного скрипта и у lib/strings — один: ", g.PI == 3.14159)
