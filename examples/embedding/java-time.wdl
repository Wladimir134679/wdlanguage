// Мост в Java: чужие классы, отданные скрипту как есть.
//
// Весь модуль sys.time — это список из семи открытых типов java.time; ни одного
// описанного метода на Java там нет. Как это устроено — в docs/java-interop.md.
//
// Для скрипта разницы с обычным классом никакой: new, точка, is, println.

import sys.time as time

// --- обычный класс, только чужой ---------------------------------------------

d = time.Date.of(2026, 8, 29)       // фабрика — статический метод Java

println(typeof(d), " ", d is time.Date)     // object true
println(d)                                  // 2026-08-29 — печать это toString()
println(d.plusDays(3))                      // 2026-09-01
println(d.getYear(), " ", d.getMonthValue(), " ", d.isLeapYear())

// Объект, вернувшийся из метода, — снова класс языка, а не «что-то из Java»
later = d.plusMonths(1)
println(later is time.Date, " ", later)     // true 2026-09-29

// --- перечисление Java — это строка ------------------------------------------

// Обёртка вокруг DayOfWeek дала бы его методы, но отняла сравнение и печать,
// а этим пользуются на порядок чаще
day = d.getDayOfWeek()
println(day, " ", day == "SATURDAY")        // SATURDAY true

// --- объект одного типа уходит аргументом в метод другого --------------------

format = time.Format.ofPattern("dd.MM.yyyy")
println(d.format(format))                   // 29.08.2026

// --- промежутки ---------------------------------------------------------------

hour = time.Duration.ofMinutes(90)
println(hour.toMinutes(), " минут = ", hour.toHours(), " ч ", hour.toMinutesPart(), " мин")

between = time.Period.between(d, d.plusDays(40))
println("между датами: ", between.getMonths(), " мес ", between.getDays(), " дн")

// --- границы моста ------------------------------------------------------------

// Ошибка самой Java становится обычной ошибкой скрипта — с местом и классом
try {
    time.Date.of(2026, 13, 40)
} catch (e) {
    println("не дата: ", e.message)
}

// Создать то, у чего нет публичных конструкторов, нельзя — и отказ объясняет почему
try {
    new time.Format()
} catch (e) {
    println(e.message)
}

// Открыто ровно семь типов. Метод, возвращающий что-то другое, отвечает отказом,
// а не открывает молча половину JDK — это и есть граница модуля
try {
    d.getChronology()
} catch (e) {
    println("закрыто: ", e.message)
}
