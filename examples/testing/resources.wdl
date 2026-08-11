// Набор проверок по ресурсам: defer, use и трейт Closeable.
// Проверяется то, ради чего эти конструкции заведены: освобождение случается
// при любом выходе, в обратном порядке и только для того, что успели захватить.

import lib.test as t
import sys.io as io

// --- ресурс, который рассказывает о себе ---------------------------------

class Probe(name, log) with Closeable {

    fun close() {
        log.push("закрыт " + name)
    }
}

/** Список с добавлением: массивы в языке неизменяемого размера, поэтому обёртка. */
class Log(entries = []) {

    fun push(entry) {
        entries = entries + [entry]
        return this;
    }

    fun text() {
        out = ""
        for (entry in entries) {
            out = out == "" ? entry : out + ", " + entry
        }
        return out;
    }
}

// === defer ===============================================================

deferring = new t.Suite("defer")

deferring.test("выполняется в обратном порядке", fun() {
    log = new Log()
    {
        defer log.push("первый")
        defer log.push("второй")
        log.push("тело")
    }
    t.assertEquals("тело, второй, первый", log.text())
})

deferring.test("область — блок, а не функция", fun() {
    // В Go defer привязан к функции, и такой цикл копил бы дескрипторы
    // до самого выхода. Привязка к блоку убирает эту ловушку по построению.
    log = new Log()
    for (name in ["a", "b"]) {
        defer log.push("отпущен " + name)
        log.push("взят " + name)
    }
    t.assertEquals("взят a, отпущен a, взят b, отпущен b", log.text())
})

deferring.test("выполняется при return", fun() {
    log = new Log()
    fun body() {
        defer log.push("прибрано")
        return "значение";
    }
    t.assertEquals("значение", body())
    t.assertEquals("прибрано", log.text())
})

deferring.test("выполняется на пути ошибки", fun() {
    log = new Log()
    t.assertThrows(ArithmeticError, fun() {
        {
            defer log.push("прибрано")
            println(1 / 0)
        }
    })
    t.assertEquals("прибрано", log.text())
})

deferring.test("записывается только то, до чего дошло выполнение", fun() {
    // Ровно то, чем defer отличается от finally: блок finally существует
    // независимо от того, дошло ли дело до захвата, и потому обрастает
    // проверками на null. Отложенного действия просто нет, пока open не вернулся.
    log = new Log()
    t.assertThrows(ArithmeticError, fun() {
        {
            log.push("до")
            println(1 / 0)
            defer log.push("не записан")
        }
    })
    t.assertEquals("до", log.text())
})

deferring.test("ошибка из defer не затирает ту, ради которой мы шли наружу", fun() {
    try {
        {
            defer throw new Exception("из defer");
            throw new Exception("основная")
        }
    } catch (e) {
        t.assertEquals("основная", e.message)
        t.assertEquals(1, len(e.suppressed))
        t.assertContains(e.suppressed[0].message, "из defer")
    }
})

deferring.run()

// === Closeable ===========================================================

contract = new t.Suite("трейт Closeable")

contract.test("обычный трейт из прелюдии", fun() {
    t.assertEquals("trait", typeof(Closeable))
    t.assertIs(Closeable, new Probe("p", new Log()))
})

contract.test("требование проверяется при объявлении класса", fun() {
    // Забыли close — ошибка на строке class, а не при первом use. Ради этого
    // трейты и заведены; никакого отдельного механизма для ресурсов нет.
    e = t.assertFails(fun() {
        // Класс внутри функции объявляется на каждом вызове — потому это и работает
        // как проверка: связывание происходит здесь и сейчас.
        class Broken(name) with Closeable
    })
    t.assertContains(e.message, "нет метода 'close'")
})

contract.run()

// === use =================================================================

using = new t.Suite("use")

using.test("закрывает в обратном порядке", fun() {
    log = new Log()
    use (first = new Probe("первый", log), second = new Probe("второй", log)) {
        log.push("тело")
    }
    t.assertEquals("тело, закрыт второй, закрыт первый", log.text())
})

using.test("закрывает на пути ошибки", fun() {
    log = new Log()
    t.assertThrows(ArithmeticError, fun() {
        use (probe = new Probe("p", log)) {
            println(1 / 0)
        }
    })
    t.assertEquals("закрыт p", log.text())
})

using.test("закрывает при return", fun() {
    log = new Log()
    fun body() {
        use (probe = new Probe("p", log)) {
            return "готово";
        }
    }
    t.assertEquals("готово", body())
    t.assertEquals("закрыт p", log.text())
})

using.test("значение без Closeable не принимается", fun() {
    class Point(x)
    e = t.assertThrows(TypeError, fun() {
        use (p = new Point(1)) {
            t.fail("тело не должно выполняться")
        }
    })
    t.assertContains(e.message, "не подмешивает трейт 'Closeable'")
})

using.test("если бросил сам захват, закрывается взятое левее", fun() {
    log = new Log()
    t.assertThrows(IndexError, fun() {
        use (first = new Probe("первый", log), second = [1][9]) {
            t.fail("тело не должно выполняться")
        }
    })
    t.assertEquals("закрыт первый", log.text())
})

using.test("ошибка при закрытии не затирает ту, ради которой мы шли наружу", fun() {
    class Stubborn() with Closeable {
        fun close() {
            throw new Exception("из close");
        }
    }
    try {
        use (probe = new Stubborn()) {
            throw new Exception("из тела")
        }
    } catch (e) {
        t.assertEquals("из тела", e.message)
        t.assertEquals(1, len(e.suppressed))
    }
})

using.run()

// === файлы ===============================================================

files = new t.Suite("потоки sys.io")

path = "build/testing-resources.txt"

files.test("io.create и io.open отдают то, что умеет закрываться", fun() {
    use (out = io.create(path)) {
        out.writeLine("первая").writeLine("вторая")
    }
    use (src = io.open(path)) {
        t.assertIs(Closeable, src)
        t.assertEquals(2, len(src.lines()))
    }
})

files.test("read отдаёт файл целиком", fun() {
    use (src = io.open(path)) {
        t.assertContains(src.read(), "вторая")
    }
})

files.test("readLine отдаёт строки по одной, а конец — это null", fun() {
    use (src = io.open(path)) {
        t.assertEquals("первая", src.readLine())
        t.assertEquals("вторая", src.readLine())
        t.assertNull(src.readLine(), "после последней строки")
    }
})

files.test("после close поток говорит об этом прямо", fun() {
    src = io.open(path)
    src.close()
    e = t.assertThrows(RuntimeError, fun() => src.read())
    t.assertContains(e.message, "поток уже закрыт")
})

files.test("закрыть дважды — не ошибка", fun() {
    src = io.open(path)
    t.assertTrue(src.close(), "первый раз закрыл")
    t.assertFalse(src.close(), "второй раз закрывать было нечего")
})

files.run()

io.remove(path)
