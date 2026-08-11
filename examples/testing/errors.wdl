// Набор проверок по обработке ошибок: бросок, ловля, порядок, трассировка.
// Каждая строка здесь — исполняемое утверждение о языке, а не описание.
//
// Обоснования — в docs/errors.md; этот файл проверяет, что написанное там правда.

import lib.test as t

// --- свои классы ошибок --------------------------------------------------

class ParseError(raw) : Exception("не число: " + raw)

class ConfigError(path, cause) : Exception("конфиг '" + path + "' не прочитан", cause)

trait Retriable {
    fun delayMs()
}

class HttpError(code) : Exception("HTTP " + code) with Retriable {
    fun delayMs() => code >= 500 ? 1000 : 200
}

// === иерархия ============================================================

hierarchy = new t.Suite("иерархия Exception")

hierarchy.test("прелюдия объявлена до первой строки скрипта", fun() {
    t.assertEquals("class", typeof(Exception))
    t.assertEquals("class", typeof(IndexError))
    t.assertEquals("trait", typeof(Closeable))
})

hierarchy.test("ошибки движка наследуют RuntimeError, а тот — Exception", fun() {
    error = new IndexError("вне границ")
    t.assertIs(RuntimeError, error)
    t.assertIs(Exception, error)
})

hierarchy.test("своя ошибка — обычный наследник", fun() {
    error = new ParseError("abc")
    t.assertIs(Exception, error)
    t.assertEquals("не число: abc", error.message)
    t.assertEquals("abc", error.raw, "своё поле никуда не делось")
})

hierarchy.test("ошибка — это объект, а не восьмой тип", fun() {
    t.assertEquals("object", typeof(new Exception("ой")))
})

hierarchy.test("JavaException не под RuntimeError", fun() {
    // Ошибся не движок, а библиотека приложения: catch по RuntimeError
    // не должен ловить чужой NullPointerException заодно с делением на ноль.
    t.assertIs(Exception, new JavaException("оттуда"))
    t.assertFalse(new JavaException("оттуда") is RuntimeError)
})

hierarchy.run()

// === бросок и ловля ======================================================

throwing = new t.Suite("throw и catch")

throwing.test("своя ошибка ловится своим классом", fun() {
    caught = null
    try {
        throw new ParseError("abc")
    } catch (e is ParseError) {
        caught = e
    }
    t.assertNotNull(caught)
    t.assertEquals("ParseError", caught.kind)
})

throwing.test("бросить можно только экземпляр Exception", fun() {
    e = t.assertThrows(TypeError, fun() { throw 5; })
    t.assertContains(e.message, "только экземпляр Exception")
})

throwing.test("ловля по предку и по трейту — то же самое, что is", fun() {
    byParent = false
    byTrait = false
    try { throw new HttpError(503) } catch (e is Exception) { byParent = true }
    try { throw new HttpError(503) } catch (e is Retriable) { byTrait = true }
    t.assertTrue(byParent, "поймано по предку")
    t.assertTrue(byTrait, "поймано по трейту")
})

throwing.test("одна ошибка бывает и классом, и трейтом", fun() {
    // Родитель у класса ровно один, а трейтов сколько угодно — потому
    // «это можно повторить» и выражается трейтом, а не базовым классом.
    error = new HttpError(503)
    t.assertIs(Exception, error)
    t.assertIs(Retriable, error)
    t.assertEquals(1000, error.delayMs())
})

throwing.test("catch без типа ловит всё, что вообще ловится", fun() {
    kinds = []
    try { println(1 / 0) } catch (e) { kinds = kinds + [e.kind] }
    try { throw new ParseError("x") } catch (e) { kinds = kinds + [e.kind] }
    t.assertEquals(["ArithmeticError", "ParseError"], kinds)
})

throwing.test("берётся первый подходящий обработчик, сверху вниз", fun() {
    which = ""
    try {
        println(1 / 0)
    } catch (e is TypeError) {
        which = "первый"
    } catch (e is RuntimeError) {
        which = "второй"
    } catch (e) {
        which = "третий"
    }
    t.assertEquals("второй", which)
})

throwing.test("несколько типов в одном обработчике", fun() {
    fun kindOf(body) {
        try {
            body()
        } catch (e is IndexError, ArithmeticError) {
            return e.kind;
        }
    }
    t.assertEquals("IndexError", kindOf(fun() => [1][9]))
    t.assertEquals("ArithmeticError", kindOf(fun() => 1 / 0))
})

throwing.test("не пойманная своим классом ошибка летит дальше", fun() {
    e = t.assertThrows(ArithmeticError, fun() {
        try {
            println(1 / 0)
        } catch (e is IndexError) {
            t.fail("сюда не должно дойти")
        }
    })
    t.assertEquals("ArithmeticError", e.kind)
})

throwing.run()

// === классы ошибок движка ================================================

kinds = new t.Suite("классы ошибок движка")

fun kindOf(body) {
    try {
        body()
    } catch (e) {
        return e.kind;
    }
    return "без ошибки";
}

kinds.test("каждая ошибка движка знает свой класс", fun() {
    t.assertEquals("TypeError", kindOf(fun() => "a" - 1))
    t.assertEquals("NameError", kindOf(fun() => unknownName))
    t.assertEquals("IndexError", kindOf(fun() => [1][9]))
    t.assertEquals("ArithmeticError", kindOf(fun() => 1 / 0))
    t.assertEquals("CallError", kindOf(fun() => (5)()))
})

kinds.test("сообщение и место у пойманной ошибки на месте", fun() {
    try {
        println([1, 2][7])
    } catch (e is IndexError) {
        t.assertContains(e.message, "вне границ")
        t.assertContains(e.at, "errors.wdl", "место броска знает файл")
    }
})

kinds.test("три уровня грубости: класс, семейство, всё", fun() {
    fun catchesAs(type) {
        try {
            println([1][9])
        } catch (e is type) {
            return true;
        } catch (e) {
            return false;
        }
    }
    t.assertTrue(catchesAs(IndexError), "по своему классу")
    t.assertTrue(catchesAs(RuntimeError), "по семейству")
    t.assertTrue(catchesAs(Exception), "по корню")
    t.assertFalse(catchesAs(TypeError), "чужой класс не ловит")
})

kinds.run()

// === finally =============================================================

finallySuite = new t.Suite("finally")

finallySuite.test("выполняется при нормальном выходе", fun() {
    log = []
    try { log = log + ["тело"] } finally { log = log + ["finally"] }
    t.assertEquals(["тело", "finally"], log)
})

finallySuite.test("выполняется при return", fun() {
    log = []
    fun body() {
        try { return "значение"; } finally { log = log + ["finally"] }
    }
    t.assertEquals("значение", body())
    t.assertEquals(["finally"], log)
})

finallySuite.test("выполняется при break", fun() {
    log = []
    for (i in [1, 2, 3]) {
        try { break } finally { log = log + ["finally"] }
    }
    t.assertEquals(["finally"], log)
})

finallySuite.test("выполняется на пути ошибки, которую никто не поймал", fun() {
    log = []
    t.assertThrows(ArithmeticError, fun() {
        try { println(1 / 0) } finally { log = log + ["finally"] }
    })
    t.assertEquals(["finally"], log)
})

finallySuite.test("ошибка из обработчика летит наружу, а finally выполняется", fun() {
    log = []
    e = t.assertThrows(ParseError, fun() {
        try {
            throw new Exception("первая")
        } catch (other) {
            throw new ParseError("вторая")
        } finally {
            log = log + ["finally"]
        }
    })
    t.assertContains(e.message, "вторая")
    t.assertEquals(["finally"], log)
})

finallySuite.test("ошибка в finally не затирает ту, ради которой мы шли наружу", fun() {
    try {
        try {
            throw new Exception("основная")
        } finally {
            throw new ParseError("из finally")
        }
    } catch (e) {
        t.assertEquals("основная", e.message, "летит первая")
        t.assertEquals(1, len(e.suppressed), "вторая подавлена, а не потеряна")
        t.assertContains(e.suppressed[0].message, "из finally")
    }
})

finallySuite.run()

// === короткие формы ======================================================

short = new t.Suite("try? и try!")

short.test("try? даёт null при ошибке и значение без неё", fun() {
    t.assertNull(try? [1][9])
    t.assertEquals(4, try? 2 + 2)
})

short.test("try? заменяет собой пустой catch, но виден в строке", fun() {
    port = try? [1][9]
    t.assertEquals(8080, port == null ? 8080 : port)
})

short.test("try? связывает как унарная операция", fun() {
    // 'try? f() + 1' — это '(try? f()) + 1': обращение и вызов крепче, сложение слабее.
    t.assertEquals(5, (try? 2 + 2) + 1)
})

short.test("try! пропускает значение", fun() {
    t.assertEquals(4, try! 2 + 2)
})

short.run()

// === цепочка причин и повторный бросок ===================================

chain = new t.Suite("cause и повторный бросок")

chain.test("перезаворачивание сохраняет исходную ошибку", fun() {
    try {
        try {
            println(1 / 0)
        } catch (e is RuntimeError) {
            throw new ConfigError("app.cfg", e)
        }
    } catch (e is ConfigError) {
        t.assertContains(e.message, "не прочитан")
        t.assertEquals("ArithmeticError", e.cause.kind, "причина не потеряна")
        t.assertContains(e.cause.text(), "деление на ноль")
    }
})

chain.test("повторный бросок место и трейс не затирает", fun() {
    fun deep() {
        throw new Exception("ой");
    }
    first = ""
    try {
        try {
            deep()
        } catch (e) {
            first = e.at
            throw e
        }
    } catch (e) {
        t.assertEquals(first, e.at, "место осталось от первого броска")
        t.assertNotEquals("", first)
    }
})

chain.test("report собирает всё, что об ошибке известно", fun() {
    try {
        try {
            println(1 / 0)
        } catch (e is RuntimeError) {
            throw new ConfigError("app.cfg", e)
        }
    } catch (e) {
        report = e.report()
        t.assertContains(report, "не прочитан")
        t.assertContains(report, "причина")
        t.assertContains(report, "ArithmeticError")
    }
})

chain.run()

// === трассировка =========================================================

tracing = new t.Suite("путь по скрипту")

fun level1() => 1 / 0
fun level2() => level1()
fun level3() => level2()

tracing.test("по строке на вызов, от места броска наружу", fun() {
    try {
        level3()
    } catch (e) {
        // Кадров ровно столько, сколько вызовов между броском и этим местом.
        t.assertTrue(len(e.trace) >= 3, "кадров не меньше трёх")
        t.assertContains(e.trace[0], "в level1")
        t.assertContains(e.trace[1], "в level2")
        t.assertContains(e.trace[2], "в level3")
    }
})

tracing.test("кадр называет функцию и место, откуда её позвали", fun() {
    try {
        level3()
    } catch (e) {
        t.assertContains(e.trace[0], "errors.wdl:", "в кадре есть файл и позиция")
    }
})

tracing.test("трейс есть и у ошибки, пойманной в той же функции", fun() {
    // Границы вызова эта ошибка не пересекает — она брошена и поймана здесь же.
    // Но кадры вокруг есть, и обработчик вправе их видеть.
    try {
        println(1 / 0)
    } catch (e) {
        t.assertTrue(len(e.trace) > 0, "кадры вокруг никуда не делись")
        t.assertContains(e.trace[0], "в fun", "самый внутренний кадр — эта лямбда")
    }
})

tracing.test("у ошибки, брошенной скриптом, трейс тот же", fun() {
    fun raise() {
        throw new ParseError("abc");
    }
    try {
        raise()
    } catch (e) {
        t.assertContains(e.trace[0], "в raise")
    }
})

tracing.run()

// === то, что не ловится ==================================================
//
// Прерывание потока, исчерпание стека и слишком глубокая рекурсия — не Exception,
// и обработчик их не видит. Проверить это набором нельзя по построению: тест,
// который «поймал» остановку выполнения, тем самым доказал бы обратное.
// Поэтому такие случаи вынесены в отдельные файлы, которые обязаны падать:
//
//     wdl examples/testing/fatal/recursion.wdl
//     wdl examples/testing/fatal/forced.wdl

println()
println("Что не ловится — в examples/testing/fatal/: эти файлы обязаны падать.")
