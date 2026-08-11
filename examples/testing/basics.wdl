// Как пользоваться движком тестов: сам движок и все его проверки.
// Здесь же видно, что он написан на обычном wdl и ничего особенного не просит.

import lib.test as t

// --- проверяемый код -----------------------------------------------------
// Обычные функции и классы: тест не требует ничего писать по-особому.

fun sum(numbers) {
    total = 0
    for (n in numbers) total = total + n;
    return total;
}

fun divide(a, b) {
    if (b == 0) throw new ArithmeticError("делить на ноль нельзя");
    return a / b;
}

class Basket(items = []) {

    fun add(item) {
        items = items + [item]
        return this;
    }

    fun total() => sum(items)
}

// --- набор ---------------------------------------------------------------
// test(имя, функция) — функция здесь обычное значение, никакой магии.

suite = new t.Suite("основы")

suite.test("равенство чисел и строк", fun() {
    t.assertEquals(10, sum([1, 2, 3, 4]))
    t.assertEquals("привет", "при" + "вет")
    t.assertNotEquals(0, sum([1]))
})

suite.test("массивы и объекты сравниваются по значению", fun() {
    // Язык сравнивает их по ссылке — и правильно делает. Тесту нужно другое,
    // поэтому в движке есть deepEquals.
    t.assertEquals([1, [2, 3]], [1, [2, 3]])
    t.assertEquals({name: "болт", count: 2}, {name: "болт", count: 2})
    t.assertFalse([1, 2] == [1, 2], "два разных массива по ссылке не равны")
})

suite.test("истинность и null", fun() {
    t.assertTrue(sum([1]) > 0)
    t.assertFalse(sum([]) > 0)
    t.assertNull(null)
    t.assertNotNull(0, "ноль — не null")
})

suite.test("тождество отличается от равенства", fun() {
    basket = new Basket()
    t.assertSame(basket, basket.add(1), "add возвращает тот же объект")
})

suite.test("подстрока", fun() {
    t.assertContains("не удалось обратиться к файлу", "к файлу")
    t.assertEquals(2, t.indexOf("abcabc", "ca"))
    t.assertEquals(-1, t.indexOf("abc", "z"))
})

suite.test("принадлежность классу", fun() {
    t.assertIs(Basket, new Basket())
    t.assertIs(Exception, new ArithmeticError("x"))
})

suite.test("ожидаемая ошибка возвращается наружу", fun() {
    // assertThrows отдаёт саму ошибку — дальше её можно расспросить.
    e = t.assertThrows(ArithmeticError, fun() => divide(1, 0))
    t.assertContains(e.message, "на ноль")
    t.assertEquals("ArithmeticError", e.kind)
})

suite.test("успешный вызов тоже бывает проверкой", fun() {
    t.assertEquals(5, t.assertOk(fun() => divide(10, 2)))
})

suite.test("класс собирается цепочкой", fun() {
    basket = new Basket().add(2).add(3).add(5)
    t.assertEquals(10, basket.total())
})

suite.skip("отложенный случай виден в отчёте", fun() {
    t.fail("сюда выполнение не дойдёт")
})

ok = suite.run()

// --- как выглядит провал -------------------------------------------------
// Набор ловит и «не сошлось», и «сломалось по дороге» — и показывает их по-разному.

broken = new t.Suite("нарочно сломанный набор")

broken.test("проверка не сходится", fun() {
    t.assertEquals(4, 2 + 3, "сумма")
})

broken.test("тест падает по дороге", fun() {
    numbers = [1, 2]
    println(numbers[9])
})

broken.run()

println()
println("основной набор прошёл: ", ok)
