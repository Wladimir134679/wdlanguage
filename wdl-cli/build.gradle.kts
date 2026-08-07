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
    // Кодировкой вывода занимается сам Main: JVM-аргументы отсюда достаются только
    // задаче `run` и стартовым скриптам дистрибутива, а из IDE приложение запускают
    // мимо них — и русский текст превращается в мусор.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

// Пути в --args пользователь пишет от корня репозитория, а не от каталога модуля:
// `--args="examples/lexer-check.wdl"` должно работать как есть.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
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
