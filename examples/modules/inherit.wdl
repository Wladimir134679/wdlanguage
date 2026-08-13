// Сценарий 6. Наследование и трейты через файлы.
//
// Правило одно: тип из модуля виден ниже своего import. Поэтому и порядок
// выполнения не меняется — модулю незачем выполняться раньше своей инструкции.

import lib.shapes            // отсюда и ниже видны Shape и Countable

class Circle(radius) : Shape("круг") {
    def area() => 3 * radius * radius
}

// describe() написан в модуле, area() — здесь: метод родителя видит переопределение.
println(new Circle(2).describe())

// super уходит в родителя из модуля.
class Ring(radius) : Circle(radius) {
    def text() => super.text() + " с дыркой"
}
println(new Ring(1).text())

// Трейт из модуля подмешивается так же, как свой.
class Bag(items) with Countable {
    def count() => len(items)
}
println("в сумке ", new Bag([1, 2, 3]).count())

// Именованный импорт даёт то же самое через точку.
import lib.shapes as s

class Square(side) : s.Shape("квадрат") with s.Countable {
    def area() => side * side
    def count() => 4
}

sq = new Square(5)
println(sq.describe(), ", сторон ", sq.count())

// Форма класса одна на модуль, сколько бы раз его ни импортировали, — поэтому
// и is через файлы отвечает правду.
println("Square is s.Shape: ", sq is s.Shape)
println("Square is Shape: ", sq is Shape)      // то же самое: импорт был один и тот же
println("Bag is s.Countable: ", new Bag([]) is s.Countable)
println("Circle is Countable: ", new Circle(1) is Countable)   // трейт не подмешан

// --- родитель и трейт выражением ---------------------------------------------

// В заголовке класса стоит выражение, а не имя с точкой. Поэтому родителя можно
// взять откуда угодно: из вложенного модуля, из реестра, из результата вызова.

class ByKey(side) : s.kinds["shape"]("квадрат из реестра") with s.contracts["countable"] {
    def area() => side * side
    def count() => 4
}
println(new ByKey(3).describe())

// Скобок здесь двое, и они разные: первые вызывают kindFor, вторые — аргументы
// заголовка родителя. Одни последние скобки всегда достаются заголовку, иначе
// ': Shape("круг")' значило бы «вызвать Shape».
class ByCall(radius) : s.kindFor("shape")("круг из фабрики") {
    def area() => 3 * radius * radius
}
println(new ByCall(2).describe())

// Родство считается по форме, а не по тому, как до класса дотянулись.
println("ByKey is s.Shape: ", new ByKey(1) is s.Shape)
println("ByCall is Shape: ", new ByCall(1) is Shape)

// --- то же самое внутри функции ----------------------------------------------

// Класс связывается с родителем в тот момент, когда объявление выполняется.
// Поэтому импорт и наследник могут стоять хоть в теле функции, хоть в ветке if —
// и модуль до этого вызова не загрузится.

def describeDot() {
    import lib.shapes
    class Dot : Shape("точка") {
        def area() => 0
    }
    return new Dot().describe();
}

println(describeDot())          // фигура точка, площадь 0
// снаружи нет ни Dot, ни своего Shape — только тот, что импортирован выше

// --- когда появляется имя класса ---------------------------------------------

// Свой класс существует с первой строки скрипта: объявления верхнего уровня
// выполняются заранее, родитель раньше потомка. А класс с родителем из модуля
// заранее связать нечем — он появляется на своей строке:
//
// import lib.shapes
// println(new Late().text())    // ошибка: переменная 'Late' не определена
// class Late : Shape("поздний")
