// Движок тестов, написанный на самом wdl.
//
// Ничего от движка языка он не требует: проверка — это обычная функция, которая
// бросает ошибку, а набор тестов — обычный класс, который её ловит. Всё, на чём
// это держится, уже есть в языке: функция как значение, класс, throw/catch
// и то, что у пойманной ошибки есть текст, место и путь по скрипту.
//
//     import lib.test as t
//
//     suite = new t.Suite("арифметика")
//     suite.test("сложение", fun() {
//         t.assertEquals(4, 2 + 2)
//     })
//     suite.run()

// --- ошибка проверки -----------------------------------------------------
// Отдельный класс, а не голый Exception: набор обязан отличать «проверка
// не сошлась» от «скрипт упал по дороге». Первое — это результат теста,
// второе — сломанный тест, и в отчёте они выглядят по-разному.

class AssertionError(message) : Exception(message)

/** Провалить проверку с готовым текстом. */
fun fail(message) {
    throw new AssertionError(message);
}

// --- проверки ------------------------------------------------------------

/** Значение истинно. */
fun assertTrue(actual, what = "значение") {
    if (!actual) fail(what + " должно быть истинным, а оно " + show(actual));
    return actual;
}

/** Значение ложно. */
fun assertFalse(actual, what = "значение") {
    if (actual) fail(what + " должно быть ложным, а оно " + show(actual));
    return actual;
}

/**
 * Равенство по значению, а не по ссылке.
 *
 * Массивы и объекты язык сравнивает по ссылке — это правильно для языка,
 * но бесполезно для теста: ожидаемое значение в тесте всегда новое.
 * Поэтому здесь deepEquals.
 */
fun assertEquals(expected, actual, what = "значение") {
    if (!deepEquals(expected, actual)) {
        fail(what + ": ожидалось " + show(expected) + ", получено " + show(actual));
    }
    return actual;
}

fun assertNotEquals(unexpected, actual, what = "значение") {
    if (deepEquals(unexpected, actual)) {
        fail(what + " не должно было равняться " + show(unexpected));
    }
    return actual;
}

/** Тождество: тот же самый объект, а не такой же. */
fun assertSame(expected, actual, what = "значение") {
    if (expected != actual) {
        fail(what + ": ожидался тот же объект " + show(expected) + ", получен " + show(actual));
    }
    return actual;
}

fun assertNull(actual, what = "значение") {
    if (actual != null) fail(what + " должно быть null, а оно " + show(actual));
    return actual;
}

fun assertNotNull(actual, what = "значение") {
    if (actual == null) fail(what + " не должно быть null");
    return actual;
}

/** Строка содержит подстроку. */
fun assertContains(text, part, what = "текст") {
    if (indexOf(text, part) < 0) {
        fail(what + " " + show(text) + " не содержит " + show(part));
    }
    return text;
}

/** Значение принадлежит классу или трейту. Тип приходит значением — класс это значение. */
fun assertIs(type, actual, what = "значение") {
    if (!(actual is type)) {
        fail(what + " " + show(actual) + " не относится к " + show(type));
    }
    return actual;
}

/**
 * Вызов бросает ошибку заданного класса. Возвращает саму ошибку — чтобы
 * следующей строкой проверить её сообщение или поле.
 *
 *     e = assertThrows(ValueError, fun() { throw new ValueError("плохо"); })
 *     assertContains(e.message, "плохо")
 */
fun assertThrows(type, body, what = "вызов") {
    try {
        body()
    } catch (e) {
        if (e is type) return e;
        fail(what + " бросил " + e.kind + " вместо " + show(type) + ": " + e.message);
    }
    fail(what + " должен был бросить " + show(type) + ", а прошёл без ошибки");
}

/** Вызов бросает хоть что-нибудь ловимое. */
fun assertFails(body, what = "вызов") {
    try {
        body()
    } catch (e) {
        return e;
    }
    fail(what + " должен был упасть, а прошёл без ошибки");
}

/** Вызов проходит без ошибки. Нужен там, где сама успешность и есть проверка. */
fun assertOk(body, what = "вызов") {
    try {
        return body();
    } catch (e) {
        fail(what + " не должен был падать, а упал: " + e.kind + ": " + e.message);
    }
}

// --- набор тестов --------------------------------------------------------

/**
 * Набор: имя плюс список проверок.
 *
 * Поля со значениями в заголовке — это счётчики, и «[]» здесь вычисляется
 * на каждом создании заново, поэтому два набора не делят один список.
 */
class Suite(name, checks = [], passed = 0, failed = 0, broken = 0, skipped = 0) {

    /** Добавляет проверку. Возвращает себя — чтобы набор собирался цепочкой. */
    fun test(title, body) {
        checks = checks + [{title: title, body: body, skip: false}]
        return this;
    }

    /** Та же запись, но проверка не выполняется: место для отложенного случая. */
    fun skip(title, body) {
        checks = checks + [{title: title, body: body, skip: true}]
        return this;
    }

    /**
     * Прогоняет набор и печатает отчёт.
     *
     * Три исхода, а не два, и разница существенная: «не сошлось» — это результат,
     * а «упало по дороге» — сломанный тест, и у второго стоит показать место
     * и путь по скрипту.
     */
    fun run() {
        println()
        println("── ", name)
        for (check in checks) {
            if (check.skip) {
                skipped = skipped + 1
                println("  skip ", check.title)
                continue
            }
            runOne(check)
        }
        println(summary())
        return failed + broken == 0;
    }

    fun runOne(check) {
        try {
            check.body()
            passed = passed + 1
            println("  ok   ", check.title)
        } catch (e is AssertionError) {
            failed = failed + 1
            println("  FAIL ", check.title)
            println("       ", e.message)
        } catch (e) {
            broken = broken + 1
            println("  ERR  ", check.title)
            println("       ", e.kind, ": ", e.message)
            if (e.at != "") println("       в ", e.at);
            for (frame in e.trace) println("         ", frame);
        }
    }

    fun summary() {
        line = "  итого: " + passed + " ok"
        if (failed > 0) line = line + ", " + failed + " fail";
        if (broken > 0) line = line + ", " + broken + " err";
        if (skipped > 0) line = line + ", " + skipped + " skip";
        return line;
    }
}

// --- вспомогательное -----------------------------------------------------

/**
 * Сравнение по значению: массивы и объекты обходятся вглубь.
 *
 * У объектов сравниваются ключи левого: если в правом их больше, разойдётся
 * длина, а если ключ есть слева и отсутствует справа, справа получится null —
 * и значения не совпадут. Пары «поля нет» и «поле равно null» этот способ
 * не различает, и для теста это честная цена.
 */
fun deepEquals(left, right) {
    if (typeof(left) != typeof(right)) return false;

    if (typeof(left) == "array") {
        if (len(left) != len(right)) return false;
        for (i = 0; i < len(left); i = i + 1) {
            if (!deepEquals(left[i], right[i])) return false;
        }
        return true;
    }

    if (typeof(left) == "object") {
        if (len(left) != len(right)) return false;
        for (key in left) {
            if (!deepEquals(left[key], right[key])) return false;
        }
        return true;
    }

    return left == right;
}

/** Позиция подстроки или -1. Своего поиска в строке у языка пока нет. */
fun indexOf(text, part) {
    if (part == "") return 0;
    last = len(text) - len(part)
    for (start = 0; start <= last; start = start + 1) {
        matched = true
        for (i = 0; i < len(part); i = i + 1) {
            if (text[start + i] != part[i]) {
                matched = false
                break
            }
        }
        if (matched) return start;
    }
    return -1;
}

fun contains(text, part) => indexOf(text, part) >= 0

/** Значение для сообщения: строки в кавычках, остальное как печатается. */
fun show(value) {
    if (typeof(value) == "string") return "\"" + value + "\"";
    return "" + value;
}

fun repeat(text, times) {
    out = ""
    for (i = 0; i < times; i = i + 1) {
        out = out + text
    }
    return out;
}
