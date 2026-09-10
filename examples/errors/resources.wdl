// Ресурсы: defer, use и трейт Closeable.
// Подробности и обоснования — в docs/errors.md.

import sys.io as io

// --- defer ---------------------------------------------------------------
// Отложенное действие пишется рядом с захватом и выполняется на выходе
// из своей области — из блока, а не из функции.

def order() {
    defer println("  третьим записан — первым выполнен")
    defer println("  вторым записан — вторым выполнен")
    println("  тело")
}

println("порядок обратный:")
order()

// Область — блок, поэтому в цикле defer срабатывает на каждом проходе.
// В Go, где defer привязан к функции, такой цикл копил бы дескрипторы до выхода.
println("в цикле:")
for (name in ["первый", "второй"]) {
    defer println("  отпущен ", name)
    println("  взят ", name)
}

// Выполняется при любом выходе — в том числе через return.
def withCleanup() {
    defer println("  прибрано")
    return "значение";
}
println("при return:")
println(withCleanup())

// --- Closeable -----------------------------------------------------------
// Обычный трейт из прелюдии. Забыли close — ошибка на строке class,
// тем же текстом и в тот же момент, что у любого другого трейта.

class Connection(host) with Closeable {

    def send(text) => println("  ", host, " ← ", text)

    def close() => println("  закрыто соединение с ", host)
}

// --- use -----------------------------------------------------------------
// Захват слева направо, закрытие справа налево: правый ресурс мог быть взят
// из левого.

println("два ресурса:")
use (first = new Connection("alpha"), second = new Connection("beta")) {
    first.send("привет")
    second.send("и тебе")
}

// Закрытие происходит и на пути ошибки.
println("ошибка в теле:")
try {
    use (conn = new Connection("gamma")) {
        throw new Exception("связь оборвалась")
    }
} catch (e) {
    println("  поймано: ", e.message)
}

// Значение, которое не умеет закрываться, use не примет — и скажет об этом
// до того, как тело отработает.
class Point(x, y)
try {
    use (p = new Point(1, 2)) {
        println("сюда не дойдём")
    }
} catch (e) {
    println("  ", e.message)
}

// --- файлы ---------------------------------------------------------------
// io.read и io.write открывают и закрывают поток внутри — закрывать там нечего.
// io.create и io.open отдают живое: дескриптор держится до close().

path = "build/example-resources.txt"

use (out = io.create(path)) {
    out.writeLine("первая строка").writeLine("вторая строка")
}

use (src = io.open(path)) {
    println("строк в файле: ", len(src.lines()))
}

use (src = io.open(path)) {
    println("символов в файле: ", len(src.read()))
}

io.remove(path)
