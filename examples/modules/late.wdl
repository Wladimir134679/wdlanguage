// Сценарий 7. Модуль загружается тогда, когда выполняется его import.
//
// Отсюда две вещи: модуля может не быть на диске в момент запуска, а плагин
// можно подгрузить по требованию — вместе с его классами и трейтами.

// --- модуля нет, и это никому не мешает --------------------------------------

def loadGenerated() {
    import plugins.generated        // такого файла в репозитории нет
    return start();
}

println("скрипт работает, plugins/generated ещё не нужен")

// Позовите loadGenerated() — и ошибка «модуль 'plugins/generated' не найден»
// появится на строке import, после того как всё выше уже отработало. А если
// к этому моменту приложение успеет создать файл, модуль просто загрузится.

// --- плагин по требованию ----------------------------------------------------

// lib/plugin сам импортирует lib/shapes и наследуется от класса оттуда.
// Ни один из двух файлов не читается, пока не позвали эту функцию.

def describeTriangle(base, height) {
    import lib.plugin as p
    t = p.make(base, height)
    return t.describe() + ", сторон " + t.count();
}

println("до загрузки плагина")
println(describeTriangle(6, 4))
println("после загрузки")

// --- второй вызов ничего не перезагружает ------------------------------------

// Модуль выполняется один раз за запуск, поэтому строки «[lib/plugin выполняется]»
// второй раз не будет, а класс останется тем же — его можно даже вернуть наружу.

def pluginClass() {
    import lib.plugin as p
    return p.Triangle;                  // класс — обычное значение
}

def pluginMake(base, height) {
    import lib.plugin as p
    return p.make(base, height);
}

println("класс тот же: ", pluginMake(3, 2) is pluginClass())
println("и снаружи им можно пользоваться: ", new (pluginClass())(4, 2).area())
println("фабрика тоже на месте: ", pluginClass().equilateral(3).describe())

// --- наследоваться от плагина можно прямо здесь -------------------------------

def makeMarked() {
    import lib.plugin               // развёрнутый импорт: Triangle ложится сюда
    class Marked(base, height, mark) : Triangle(base, height) {
        def text() => super.text() + " " + mark
    }
    return new Marked(2, 2, "(*)");
}

marked = makeMarked()
println(marked.describe(), " — площадь считает класс из модуля")

// Снаружи функции ни Triangle, ни Marked нет: импорт живёт в своей области.
// println(new Triangle(1, 1))   // переменная 'Triangle' не определена
