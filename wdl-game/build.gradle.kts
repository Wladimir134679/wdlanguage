plugins {
    id("wdl.java-conventions")
    `java-library`
    application
}

description = "WDGame — игровой движок на Swing/Graphics2D и пинг-понг на нём: правила игры написаны скриптами на wdl. Площадка, на которой проверяется встраивание."

dependencies {
    // Движок описывает себя скриптом сам: типы строятся построителем моста,
    // а тот работает с значениями ядра.
    api(project(":wdl-core"))
    api(project(":wdl-bridge"))

    // Запуск скрипта идёт через фасад — тем же способом, каким это сделала бы
    // чужая игра: движок здесь не часть языка, а приложение, которое его встроило.
    implementation(project(":wdl-api"))
}

application {
    mainClass.set("ru.wds.wdl.game.GameLauncher")
    applicationName = "wdgame"
    // Тот же довод, что у wdl-cli: JVM-аргументы задачи не достаются запуску из IDE,
    // а русский текст в консоли без этого превращается в мусор.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

// Рабочий каталог — корень репозитория: из него запускают все остальные модули.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
