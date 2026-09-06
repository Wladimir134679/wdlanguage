// Корневой проект — только агрегатор. Кода здесь нет и не должно быть:
// вся логика живёт в модулях, общие настройки — в buildSrc/wdl.java-conventions.
plugins {
    base
}

description = "wdl — встраиваемый скриптовый язык для JVM"

val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

tasks.register<Exec>("buildIdeaPlugin") {
    group = "build"
    description = "Собирает ZIP плагина IDEA (отдельная сборка, при первом запуске скачивает IDE)."
    workingDir = rootDir
    commandLine(if (windows) listOf("cmd", "/c", "gradlew.bat", "-p", "ide/idea-plugin", "test", "buildPlugin")
        else listOf("sh", "./gradlew", "-p", "ide/idea-plugin", "test", "buildPlugin"))
}

tasks.register("prepareWdl") {
    group = "distribution"
    description = "Собирает и проверяет модули, CLI/LSP дистрибутивы и ZIP плагина IDEA."
    dependsOn(subprojects.map { "${it.path}:build" })
    dependsOn(":wdl-cli:installDist", ":wdl-lsp:installDist", "buildIdeaPlugin")
}

tasks.register<Exec>("installWdl") {
    group = "distribution"
    description = "Собирает CLI/LSP и добавляет их пути из этого checkout в окружение пользователя."
    dependsOn(subprojects.map { "${it.path}:assemble" })
    dependsOn(":wdl-cli:installDist", ":wdl-lsp:installDist")
    workingDir = rootDir
    commandLine(if (windows) listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", file("scripts/install-wdl.ps1").absolutePath, "-Repository", rootDir.absolutePath)
        else listOf("bash", file("scripts/install-wdl.sh").absolutePath, rootDir.absolutePath))
}

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
