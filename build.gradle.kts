// Корневой проект — только агрегатор. Кода здесь нет и не должно быть:
// вся логика живёт в модулях, общие настройки — в buildSrc/wdl.java-conventions.
plugins {
    base
}

description = "wdl — встраиваемый скриптовый язык для JVM"

/**
 * Печатает карту модулей и их зависимостей — удобно, когда проект подрастёт.
 * Запуск: ./gradlew modules
 */
tasks.register("modules") {
    group = "help"
    description = "Показывает состав сборки и назначение каждого модуля."

    val rows = subprojects.map { it.name to (it.description ?: "") }
    doLast {
        val width = rows.maxOf { it.first.length }
        println("Модули сборки:")
        rows.forEach { (name, desc) -> println("  ${name.padEnd(width)}  $desc") }
    }
}
