plugins {
    id("wdl.java-conventions")
    `java-library`
    application
}

description = "Консольный запуск скриптов и REPL."

dependencies {
    implementation(project(":wdl-api"))
    implementation(project(":wdl-tools"))
}

application {
    mainClass.set("ru.wds.wdl.cli.Main")
    applicationName = "wdl"
    // stdout.encoding/stderr.encoding нужны для Java 19+, чтобы русский текст
    // не ломался в консоли Windows.
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

/**
 * Интерактивный REPL. Отдельная задача, потому что ей нужен живой stdin,
 * а это несовместимо с configuration cache — для обычного `run` его не теряем.
 *
 * Запуск: ./gradlew :wdl-cli:repl --console=plain
 */
tasks.register<JavaExec>("repl") {
    group = "application"
    description = "Запускает REPL с подключённым вводом с клавиатуры."

    mainClass.set("ru.wds.wdl.cli.Main")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("--repl")
    standardInput = System.`in`
    defaultCharacterEncoding = "UTF-8"
    jvmArgs = application.applicationDefaultJvmArgs.toList()

    notCompatibleWithConfigurationCache("REPL читает stdin интерактивно")
}
