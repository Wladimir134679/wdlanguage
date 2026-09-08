plugins {
    id("wdl.java-conventions")
    `java-library`
    application
}

description = "Адаптер отладки DAP: перевод сессии wdl-core в Debug Adapter Protocol."

dependencies {
    // Отладку ведёт движок; здесь только перевод её событий в протокол.
    implementation(project(":wdl-api"))
    // Строка редактора в смещение инструкции — BreakpointPlaces.
    implementation(project(":wdl-tools"))
    // Набор модулей запуска — тот же, что у консольного wdl: отлаживают то же,
    // что и запускают.
    implementation(project(":wdl-stdlib"))
    implementation(libs.lsp4j.debug)
}

application {
    mainClass.set("ru.wds.wdl.dap.Main")
    applicationName = "wdl-dap"
    // Сообщения протокола — UTF-8, и вывод отлаживаемого скрипта уходит клиенту
    // тем же. Из IDE адаптер запускают мимо стартовых скриптов, поэтому кодировку
    // задаёт и сам Main.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
