// Встроенные модули: sys.io, sys.json — библиотеки, написанные на Java и отданные
// скрипту через import. Для языка они неотличимы от модулей-файлов.
//
// Как это устроено — в docs/modules.md (раздел «Встроенные модули»)
// и docs/embedding.md (раздел «Модуль на Java»).
//
// sys.net.http здесь не показан: примеры запускаются на сборке, а сеть в ней
// может быть недоступна. Как он выглядит — в docs/modules.md.

import sys.io as io
import sys.json as json

// --- модуль ведёт себя как модуль --------------------------------------------

println(typeof(json), " ", json)        // module module sys/json

// --- json: разобранное — обычные данные языка --------------------------------

data = json.parse("{\"name\": \"Аня\", \"tags\": [\"a\", \"b\"], \"active\": true}")

println(typeof(data), " ", len(data))   // object 3
println(data.name, " ", data["tags"][0], " ", data.active)

// Раз это обычный объект, к нему применимо всё обычное
for (key in data) println("  ", key, " = ", data[key])

// Обратно — из значений языка в текст
println(json.stringify({id: 7, tags: [1, 2]}))          // {"id":7,"tags":[1,2]}
println(json.stringify({id: 7}, 2))                     // с отступом в два пробела

// Экземпляр класса — тот же набор полей, поэтому пишется объектом
class Point(x, y)
println(json.stringify(new Point(1, 2)))                // {"x":1,"y":2}

// --- io: файл одной строкой и файл как объект --------------------------------

path = "build/sys-пример.json"

io.mkdirs("build")
io.write(path, json.stringify({saved: true, tags: ["a", "b"]}, 2))

println(io.exists(path), " ", io.size(path) > 0)
println(json.parse(io.read(path)).tags[1])              // b

// Класс File — тот же самый, что кладёт в область стандартная библиотека
f = new io.File(path)
println(f is io.File, " ", f.name())

// Функции и класс работают вместе: путь остаётся обычной строкой
io.append(path, "\n")
println("строк: ", len(io.lines(path)))

// --- набор модулей задаёт запуск ---------------------------------------------

// Модуля, которого не положили в набор, не существует — обойти это нечем:
// import sys.sql
// ошибка: модуль 'sys/sql' не найден, и встроенного модуля 'sys/sql' тоже нет

// Имя встроенного модуля не зависит от каталога, а файл просится явным путём:
// import "./sys/json"   — это файл sys/json.wdl рядом со скриптом

// Уберём за собой
io.remove(path)
println(io.exists(path))                                // false
