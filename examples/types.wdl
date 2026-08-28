// Дескрипторы типов: typeof и is по всем десяти типам. Описание — в docs/types.md
// и в разделе «Класс — тоже значение» docs/classes.md.

// --- typeof возвращает дескриптор, а не строку --------------------------------

println(typeof(1))              // number
println(typeof(1.5))            // number
println(typeof(true))           // bool
println(typeof(null))           // null
println(typeof("текст"))        // string
println(typeof([1, 2]))         // array
println(typeof({a: 1}))         // object
println(typeof(println))        // function

// Печатается дескриптор так же, как раньше печаталась строка — это id() типа,
// а не его имя в области видимости (то — с большой буквы, см. ниже).
println(typeof(1) == Number)    // true: дескриптор один на тип
println(typeof(1) == typeof(2)) // true: одно и то же значение

// --- is: то же самое, но по имени, а не через равенство -----------------------

println(5 is Number)            // true
println("a" is String)          // true
println([] is Array)            // true
println({} is Object)           // true
println(null is Null)           // true
println(true is Bool)           // true
println(println is Function)    // true

class Point(x, y)
trait Printable { def text() }

println(Point is Class)         // true — класс тоже значение своего рода
println(Printable is Trait)     // true

p = new Point(1, 2)
println(p is Object)            // true — экземпляр это object
println(p is Point)             // true — а ещё принадлежит своему классу

// --- typeof(x) == Тип и x is Тип совпадают только для дескрипторов ------------
//
// У десяти типов нет иерархии, поэтому для них равенство и 'is' — один вопрос.
// У классов иерархия есть, и вопросы расходятся: typeof(p) — это всегда 'object',
// какой бы класс ни стоял за экземпляром, а is знает про сам класс.

println(typeof(p) == Point)     // false — typeof(p) это Object, а не Point
println(p is Point)             // true

// --- Number is Number: дескриптор не относится сам к себе ---------------------
//
// 'is' спрашивает у правого операнда "принадлежит ли ему значение слева".
// Слева здесь стоит сам дескриптор Number (со своим typeof() == Class),
// а не число, — поэтому ответ false, а не true.

println(Number is Number)       // false

// --- дескриптор — обычное имя, не константа: можно перекрыть ------------------

Number = 5
println(Number, " ", typeof("a"))    // 5 string — typeof и is других имён не касаются

// --- дескриптором нельзя ни создать экземпляр, ни записать в него -------------
//
// new String("a")        // ошибка: 'String' — тип, а не класс
// String.x = 1            // ошибка: в тип 'String' нельзя записать
//
// А вот объявить свой класс с этим именем можно: имя перекрывается, как и любое
// другое, — запрещена только запись в сам дескриптор.
