// Сценарий 3. Импорт — обычная инструкция, поэтому он живёт в своей области
// и исчезает вместе с ней.

fun circle(radius) {
    import lib.geometry as g
    return "площадь: " + g.area(radius);
}

fun point(x, y) {
    // Здесь импорт развёрнутый: имена ложатся прямо в тело функции.
    import lib.geometry
    return new Point(x, y).text();
}

println(circle(1))
println(point(1, 2))

{
    import lib.geometry as inBlock
    println("внутри блока: ", inBlock.PI)
}

// Ни одного из этих имён снаружи нет:
// println(g.PI)        // переменная 'g' не определена
// println(PI)          // переменная 'PI' не определена
// println(inBlock.PI)  // переменная 'inBlock' не определена
println("после выхода из функций и блока имён от импорта не осталось")
